package umbrix.core.session

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import it.unimi.dsi.fastutil.longs.LongArrayList
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.classifier.EventBoundaryClassifier
import umbrix.core.index.FusedStreamingIndexer
import umbrix.core.minimap.{MinimapDensityTable, MinimapScanner}
import umbrix.core.parser.QueryParseError
import umbrix.core.plugin.PluginManager
import umbrix.core.profile.{JavaJvmProfile, LanguageProfileRegistry, LogLanguageProfile}
import umbrix.core.search.SearchResult
import umbrix.core.search.SimdSearchEngine
import umbrix.core.slicing.LogSlicer
import umbrix.core.slicing.SliceResult
import umbrix.core.temporal.{LatencyGapResult, LatencyGapScanner}
import umbrix.core.types.{ByteOffset, EpochMicros, LineIndex}
import umbrix.core.view.FilteredLineView
import umbrix.index.{FramedRecordIndexerImpl, PersistentIndexCache, PhysicalLineIndexer, PhysicalLineIndexerImpl, PieceTable, TemporalCheckpointTable, TwoLevelSparseIndex}
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.format.FormatProbeResult
import umbrix.simd.VectorScanner
import umbrix.vfs.LogStorageReader

/**
 * Unified high-level log analysis workstation session.
 *
 * Coordinates:
 * - Off-heap dual-path storage reader (`:umbrix-vfs`)
 * - Hardware SIMD-accelerated two-level sparse index (`:umbrix-index`)
 * - Temporal checkpoint table for O(log N) wall-clock seeks
 * - Multi-core SIMD and RE2J search engine (`SimdSearchEngine`)
 * - Zero-copy log slicing and sub-file generation engine (`LogSlicer`)
 * - Progressive asynchronous logical event classifier (`EventBoundaryClassifier`)
 *
 * Invariant: 0 bytes of raw log text are ingested into the JVM Garbage Collector heap.
 */
class LogSession(
  val path: Path,
  val storageReader: LogStorageReader,
  val indexer: PhysicalLineIndexer,
  val temporalIndexFuture: CompletableFuture[TemporalCheckpointTable],
  val searchEngine: SimdSearchEngine,
  val slicer: LogSlicer,
  val eventClassifierFuture: CompletableFuture[EventBoundaryClassifier],
  val indexingDurationMs: Long,
  private val temporalProgressSupplier: () => Double = () => 1.0,
  private val classifierProgressSupplier: () => Double = () => 1.0,
  val physicalIndexFuture: CompletableFuture[TwoLevelSparseIndex] = CompletableFuture.completedFuture(null),
  private val physicalProgressSupplier: () => Double = () => 1.0,
  private val bytesScannedSupplier: () => Long = () => 0L,
  val minimapFuture: CompletableFuture[MinimapDensityTable] = CompletableFuture.completedFuture(null),
  private val minimapProgressSupplier: () => Double = () => 1.0,
  val languageProfile: LogLanguageProfile = JavaJvmProfile,
  val latencyGapFuture: CompletableFuture[LatencyGapResult] = CompletableFuture.completedFuture(LatencyGapResult.empty),
  val isFusedIndexing: Boolean = false,
  val activeFormatProbeResult: Option[FormatProbeResult] = None,
  val activeFormatProviderId: Option[String] = None
) extends AutoCloseable {

  val pieceTable: PieceTable = new PieceTable(storageReader, indexer.totalLines.value)
  lazy val pluginSessionView: PluginSessionView = new PluginSessionViewImpl(this, pieceTable)

  /**
   * Compatibility constructor for callers providing a completed TemporalCheckpointTable.
   */
  def this(
    path: Path,
    storageReader: LogStorageReader,
    indexer: PhysicalLineIndexerImpl,
    temporalIndex: TemporalCheckpointTable,
    searchEngine: SimdSearchEngine,
    slicer: LogSlicer,
    eventClassifierFuture: CompletableFuture[EventBoundaryClassifier],
    indexingDurationMs: Long
  ) = this(
    path,
    storageReader,
    indexer,
    CompletableFuture.completedFuture(temporalIndex),
    searchEngine,
    slicer,
    eventClassifierFuture,
    indexingDurationMs,
    () => 1.0,
    () => 1.0,
    CompletableFuture.completedFuture(indexer.sparseIndex),
    () => 1.0,
    () => storageReader.sizeBytes
  )

  /**
   * Compatibility constructor for callers providing a fully-evaluated EventBoundaryClassifier.
   */
  def this(
    path: Path,
    storageReader: LogStorageReader,
    indexer: PhysicalLineIndexerImpl,
    temporalIndex: TemporalCheckpointTable,
    searchEngine: SimdSearchEngine,
    slicer: LogSlicer,
    eventClassifier: EventBoundaryClassifier,
    indexingDurationMs: Long
  ) = this(
    path,
    storageReader,
    indexer,
    CompletableFuture.completedFuture(temporalIndex),
    searchEngine,
    slicer,
    CompletableFuture.completedFuture(eventClassifier),
    indexingDurationMs,
    () => 1.0,
    () => 1.0,
    CompletableFuture.completedFuture(indexer.sparseIndex),
    () => 1.0,
    () => storageReader.sizeBytes
  )

  /** Local file system path of the active log file. */
  def filePath: Path = path

  /** File name formatted for UI tab and title bar presentation. */
  def fileName: String = {
    val fileNamePath = path.getFileName
    if (fileNamePath != null) fileNamePath.toString else path.toString
  }

  /** Total file size in bytes. */
  def fileSizeBytes: Long = storageReader.sizeBytes

  /** Total physical line count resolved via SIMD indexing as LineIndex. */
  def totalLines: LineIndex = indexer.totalLines

  /** Hardware SIMD register byte width detected by the vector scanning engine (e.g. 64 for AVX-512, 32 for AVX2, 16 for NEON). */
  def simdVectorWidth: Int = VectorScanner.vectorByteWidth

  /** True if background physical SIMD newline indexing has fully finished. */
  def isPhysicalIndexComplete: Boolean = physicalIndexFuture.isDone

  /** Physical SIMD indexing progress percentage from 0.0 to 100.0. */
  def physicalIndexProgressPercent: Double = {
    if (isPhysicalIndexComplete) {
      100.0
    } else {
      SessionStatusFormatter.clampPercent(physicalProgressSupplier())
    }
  }

  /** Bytes scanned so far by the physical indexer. */
  def bytesScanned: Long = {
    if (isPhysicalIndexComplete) {
      fileSizeBytes
    } else {
      bytesScannedSupplier()
    }
  }

  /** True if background logical event boundary classification has fully finished. */
  def isClassifierComplete: Boolean = eventClassifierFuture.isDone

  /** True if background temporal checkpoint indexing has fully finished. */
  def isTemporalIndexComplete: Boolean = temporalIndexFuture.isDone

  /** True if Tier 2 metadata indexing (events + temporal) has finished. */
  def isTier2Complete: Boolean = isClassifierComplete && isTemporalIndexComplete

  /** Tier 2 metadata indexing progress percentage from 0.0 to 100.0. */
  def tier2ProgressPercent: Double = {
    SessionStatusFormatter.tier2ProgressPercent(
      isTier2Complete = isTier2Complete,
      isTemporalIndexComplete = isTemporalIndexComplete,
      isClassifierComplete = isClassifierComplete,
      temporalProgress = if (isTemporalIndexComplete) 1.0 else temporalProgressSupplier(),
      classifierProgress = if (isClassifierComplete) 1.0 else classifierProgressSupplier()
    )
  }

  /** True if all indexing (Tier 1 physical newline scan + Tier 2 semantic metadata) has completed. */
  def isIndexingComplete: Boolean = isPhysicalIndexComplete && isTier2Complete

  /**
   * Combined background indexing progress percentage from 0.0 to 100.0.
   * Physical indexing represents the first 70% of work; Tier 2 metadata represents the remaining 30%.
   */
  def indexingProgressPercent: Double = {
    SessionStatusFormatter.indexingProgressPercent(
      isIndexingComplete = isIndexingComplete,
      isFusedIndexing = isFusedIndexing,
      isPhysicalIndexComplete = isPhysicalIndexComplete,
      physicalPercent = if (isPhysicalIndexComplete) 100.0 else physicalIndexProgressPercent,
      tier2Percent = if (isTier2Complete) 100.0 else tier2ProgressPercent
    )
  }

  /**
   * Human-readable diagnostic status string describing current indexing activity and metrics.
   */
  def indexingStatusText: String = {
    val complete = isIndexingComplete
    SessionStatusFormatter.indexingStatusText(
      fileName = fileName,
      fileSizeBytes = fileSizeBytes,
      bytesScanned = bytesScanned,
      totalLines = totalLines.value,
      totalEvents = totalEvents,
      isIndexingComplete = complete,
      isFusedIndexing = isFusedIndexing,
      isPhysicalIndexComplete = isPhysicalIndexComplete,
      physicalPercent = if (isPhysicalIndexComplete) 100.0 else physicalIndexProgressPercent,
      tier2Percent = if (isTier2Complete) 100.0 else tier2ProgressPercent
    )
  }

  /**
   * Active TemporalCheckpointTable instance.
   * Returns completed table if ready, or an empty table during progressive background indexing.
   */
  def temporalIndex: TemporalCheckpointTable = {
    if (temporalIndexFuture.isDone) {
      try {
        temporalIndexFuture.get()
      } catch {
        case _: Throwable => TemporalCheckpointTable.empty
      }
    } else {
      TemporalCheckpointTable.empty
    }
  }

  private lazy val fallbackClassifier: EventBoundaryClassifier =
    EventBoundaryClassifier.unclassified(indexer)

  /**
   * Active EventBoundaryClassifier instance.
   * Returns completed classifier if background indexing is done, or fallback unclassified instance
   * where all lines are valid event headers until classification completes.
   */
  def eventClassifier: EventBoundaryClassifier = {
    if (eventClassifierFuture.isDone) {
      try {
        eventClassifierFuture.get()
      } catch {
        case _: Throwable => fallbackClassifier
      }
    } else {
      fallbackClassifier
    }
  }

  /** Total logical events identified by the event classifier. */
  def totalEvents: Long = {
    if (eventClassifierFuture.isDone) {
      eventClassifier.totalEvents
    } else {
      indexer.totalLines.value
    }
  }

  /**
   * Retrieves line text formatted for UI display, clamped to `maxCharacters` to protect the
   * presentation layer from multi-megabyte single lines.
   */
  def getLineText(lineIndex: LineIndex, maxCharacters: Int = 4096): String = {
    indexer.getLineText(lineIndex, maxCharacters)
  }

  /**
   * Earliest recorded wall-clock timestamp in epoch microseconds, or None if temporal index is unavailable.
   */
  def firstTimestamp: Option[Long] = {
    temporalIndex.firstTimestamp.map(_.value)
  }

  /**
   * Latest recorded wall-clock timestamp in epoch microseconds, or None if temporal index is unavailable.
   */
  def lastTimestamp: Option[Long] = {
    temporalIndex.lastTimestamp.map(_.value)
  }

  /**
   * True if temporal checkpoints are fully populated with at least two samples to enable range navigation.
   */
  def isTemporalDataAvailable: Boolean = {
    isTemporalIndexComplete && temporalIndex.checkpointCount >= 2
  }

  /**
   * True if the document's detected timestamp format includes an explicit calendar date;
   * false for single-day time-of-day logs (e.g. 00:00:00.000 to 24:00:00.00).
   */
  def hasExplicitDate: Boolean = {
    temporalIndex.hasDateComponent
  }

  /**
   * Indicates whether document timestamps are strictly chronological (monotonic).
   * Returns true for standard in-order logs (99%), or false if out-of-order blocks were detected and auto-sorted.
   */
  def isLogChronologicallyOrdered: Boolean = {
    temporalIndex.isMonotonic
  }

  /**
   * Seeks the nearest line index for a wall-clock timestamp in epoch microseconds in O(log N) time.
   */
  def seekNearestLine(epochMicros: EpochMicros): LineIndex = {
    temporalIndex.seekNearestLine(epochMicros)
  }

  /**
   * Resolves the bounding line indices [startLine, endLine] for a given wall-clock time range in microseconds.
   *
   * @param startMicros start timestamp in epoch microseconds
   * @param endMicros end timestamp in epoch microseconds
   * @return primitive long array of length 2: [startLine, endLine]
   */
  def seekLineRangeBounds(startMicros: Long, endMicros: Long): Array[Long] = {
    val (startLine, endLine) = temporalIndex.seekLineRange(EpochMicros(startMicros), EpochMicros(endMicros))
    Array(startLine.value, endLine.value)
  }

  /**
   * Calculates estimated line count within the specified temporal range.
   */
  def lineCountInTimeRange(startMicros: Long, endMicros: Long): Long = {
    val range = seekLineRangeBounds(startMicros, endMicros)
    math.max(0L, range(1) - range(0) + 1L)
  }

  /**
   * Active LatencyGapResult instance if ready, or an empty result during background analysis.
   */
  def latencyGapResult: LatencyGapResult = {
    if (latencyGapFuture.isDone) {
      try {
        latencyGapFuture.get()
      } catch {
        case _: Throwable => LatencyGapResult.empty
      }
    } else {
      LatencyGapResult.empty
    }
  }

  /**
   * O(1) check determining if a line marks the beginning of an abnormal latency gap.
   */
  def hasLatencyGapAt(lineIndex: Long): Boolean = {
    latencyGapResult.hasGapAt(LineIndex(lineIndex))
  }

  /**
   * Retrieves latency gap duration in microseconds for a line, or 0L if no gap.
   */
  def latencyGapDurationAt(lineIndex: Long): Long = {
    latencyGapResult.gapDurationAt(LineIndex(lineIndex))
  }

  /**
   * Executes a search query across the log session.
   *
   * @param queryText raw search expression
   * @return `Right(SearchResult)` with matching line bitsets or `Left(QueryParseError)` on syntax error
   */
  def search(queryText: String): Either[QueryParseError, SearchResult] = {
    searchEngine.search(queryText)
  }

  /**
   * Executes a search query across the log session with progressive partial result notifications.
   *
   * @param queryText raw search expression
   * @param progressConsumer callback receiving incremental SearchResult snapshots as matches are discovered
   * @return `Right(SearchResult)` with final complete result or `Left(QueryParseError)` on syntax failure
   */
  def searchProgressive(
    queryText: String,
    progressConsumer: java.util.function.Consumer[SearchResult]
  ): Either[QueryParseError, SearchResult] = {
    searchProgressive(queryText, 0L, progressConsumer)
  }

  /**
   * Executes a search query across the log session with progressive partial result notifications,
   * prioritizing chunk scanning from the partition covering `anchorLine`.
   *
   * @param queryText raw search expression
   * @param anchorLine 0-based physical line index used to prioritize chunk scheduling
   * @param progressConsumer callback receiving incremental SearchResult snapshots as matches are discovered
   * @return `Right(SearchResult)` with final complete result or `Left(QueryParseError)` on syntax failure
   */
  def searchProgressive(
    queryText: String,
    anchorLine: Long,
    progressConsumer: java.util.function.Consumer[SearchResult]
  ): Either[QueryParseError, SearchResult] = {
    val total: Long = indexer.totalLines.value
    val anchorByte: Long = if (anchorLine >= 0L && anchorLine < total) {
      indexer.byteOffsetForLine(LineIndex(anchorLine)).value
    } else {
      0L
    }
    searchEngine.searchProgressive(queryText, anchorByte, (res: SearchResult) => progressConsumer.accept(res))
  }

  /**
   * Cooperatively aborts any currently active search operation across all worker threads.
   */
  def abortSearch(): Unit = {
    searchEngine.abortCurrentSearch()
  }

  /**
   * Constructs an O(1) virtual coordinate view mapping for filtered search results.
   */
  def createFilteredView(matchingBitmap: Roaring64NavigableMap): FilteredLineView = {
    new FilteredLineView(matchingBitmap, indexer)
  }

  /**
   * Exports a contiguous line range [startLine, endLine] to a standalone sub-file via zero-copy DMA.
   */
  def exportContiguousRange(startLine: LineIndex, endLine: LineIndex, destinationPath: Path): SliceResult = {
    slicer.exportContiguousRange(startLine, endLine, destinationPath)
  }

  /**
   * Exports a discontiguous set of filtered lines to a standalone sub-file.
   */
  def exportFilteredLines(matchingLines: Roaring64NavigableMap, destinationPath: Path): SliceResult = {
    slicer.exportFilteredLines(matchingLines, destinationPath)
  }

  /**
   * True if line represents a logical event header (e.g. not a continuation frame).
   */
  def isEventHeader(lineIndex: LineIndex): Boolean = {
    if (eventClassifierFuture.isDone) {
      eventClassifier.isEventHeader(lineIndex)
    } else {
      true
    }
  }

  /**
   * Resolves the parent event header line index for any line in a stack trace.
   */
  def parentEventForLine(lineIndex: LineIndex): LineIndex = {
    if (eventClassifierFuture.isDone) {
      eventClassifier.parentEventForLine(lineIndex)
    } else {
      lineIndex
    }
  }

  /**
   * True if background minimap density calculation has finished.
   */
  def isMinimapComplete: Boolean = minimapFuture != null && minimapFuture.isDone

  /**
   * Minimap density calculation progress percentage from 0.0 to 100.0.
   */
  def minimapProgressPercent: Double = {
    if (isMinimapComplete) {
      100.0
    } else {
      math.min(99.9, math.max(0.0, minimapProgressSupplier() * 100.0))
    }
  }

  /**
   * Active MinimapDensityTable instance if ready.
   */
  def minimapTable: Option[MinimapDensityTable] = {
    if (isMinimapComplete) {
      try {
        Option(minimapFuture.get())
      } catch {
        case _: Throwable => None
      }
    } else {
      None
    }
  }

  def minimapTableOrNull: MinimapDensityTable = {
    minimapTable.getOrElse(null)
  }

  private def findAnomaly(startLine: Long, forward: Boolean): Long = {
    val table = minimapTableOrNull
    val total = totalLines.value
    if (table == null || total <= 0L || (!forward && startLine <= 0L)) {
      -1L
    } else {
      val initialLine: Long = if (forward) (startLine + 1L).max(0L) else startLine - 1L
      var binIndex: Int = table.binForLine(initialLine, total)
      val binStep: Int = if (forward) 1 else -1
      var foundLine: Long = -1L

      while (binIndex >= 0 && binIndex < table.binCount && foundLine < 0L) {
        if (table.hasAnomalies(binIndex)) {
          val (binStart, binEnd) = table.lineRangeForBin(binIndex, total)
          var lineCursor: Long = if (forward) math.max(startLine + 1L, binStart) else math.min(startLine - 1L, binEnd)
          val lineStep: Long = if (forward) 1L else -1L
          while (((forward && lineCursor <= binEnd) || (!forward && lineCursor >= binStart)) && foundLine < 0L) {
            val lineText = getLineText(LineIndex(lineCursor), 256)
            val severity = languageProfile match {
              case base: umbrix.core.profile.BaseLogLanguageProfile => base.classifyLineSeverity(lineText)
              case _ => umbrix.core.profile.LineSeverity.Normal
            }
            if (severity == umbrix.core.profile.LineSeverity.Error || severity == umbrix.core.profile.LineSeverity.Warn) {
              foundLine = lineCursor
            } else {
              lineCursor += lineStep
            }
          }
        }
        binIndex += binStep
      }
      foundLine
    }
  }

  /**
   * Fast seek to the next error or warning line after `startLine`.
   * Leverages the 1,000-bin density table to skip non-anomaly regions in O(1) time.
   * Returns -1L if no anomaly is found.
   */
  def findNextAnomaly(startLine: Long): Long = {
    findAnomaly(startLine, forward = true)
  }

  /**
   * Fast seek to the previous error or warning line before `startLine`.
   * Leverages the 1,000-bin density table to skip non-anomaly regions in O(1) time.
   * Returns -1L if no anomaly is found.
   */
  def findPreviousAnomaly(startLine: Long): Long = {
    findAnomaly(startLine, forward = false)
  }

  /**
   * Trims physical memory pages committed to the OS process working set from this log file session.
   */
  def trimWorkingSet(): Unit = {
    storageReader.trimWorkingSet()
  }

  private def runQuietly(action: => Unit): Unit = {
    try {
      action
    } catch {
      case _: Throwable => ()
    }
  }

  private def cancelIfPending(future: CompletableFuture[?]): Unit = {
    runQuietly {
      if (future != null && !future.isDone) {
        future.cancel(true)
      }
    }
  }

  def close(): Unit = {
    runQuietly { PluginManager.instance.notifySessionClosing(pluginSessionView) }
    runQuietly { pieceTable.close() }
    cancelIfPending(physicalIndexFuture)
    cancelIfPending(latencyGapFuture)
    cancelIfPending(minimapFuture)
    cancelIfPending(temporalIndexFuture)
    cancelIfPending(eventClassifierFuture)
    runQuietly { searchEngine.close() }
    runQuietly { indexer.close() }
    runQuietly { storageReader.close() }
  }
}

object LogSession {

  /**
   * Opens an industrial-grade log analysis session for the given file path.
   *
   * Rapidly scans physical newlines via SIMD (Tier 1) for sub-second viewport availability,
   * while asynchronously indexing logical event boundaries and temporal checkpoints in background
   * worker fibers (Tier 2, IntelliJ-style progressive indexing).
   *
   * @param path target log file path
   * @param isLiveStream if true, opens via positional dual-path I/O; if false, maps into 64-bit continuous address space
   * @return active LogSession ready for interactive exploration and sub-second searching
   */
  /**
   * Lightweight off-heap format probe inspecting at most 4,096 header bytes without
   * creating an index or firing plugin session lifecycle listeners.
   */
  def probeFormat(path: Path): Option[FormatProbeResult] = {
    val storageReader: LogStorageReader = LogStorageReader.open(path, false)
    try {
      val headerSlice = storageReader.readSlice(0L, math.min(storageReader.sizeBytes, 4096L))
      PluginManager.instance
        .probeFormatWithProvider(path, headerSlice, storageReader.sizeBytes)
        .map { case (_, probeResult) => probeResult }
    } finally {
      storageReader.close()
    }
  }

  def open(path: Path): LogSession = open(path, false, null, allowFormatProviders = true)

  def open(path: Path, isLiveStream: Boolean): LogSession = open(path, isLiveStream, null, allowFormatProviders = true)

  def open(path: Path, isLiveStream: Boolean, profile: LogLanguageProfile): LogSession = {
    open(path, isLiveStream, profile, allowFormatProviders = true)
  }

  def openPlain(path: Path): LogSession = open(path, false, null, allowFormatProviders = false)

  def openPlain(path: Path, isLiveStream: Boolean): LogSession = open(path, isLiveStream, null, allowFormatProviders = false)

  def open(
    path: Path,
    isLiveStream: Boolean,
    profile: LogLanguageProfile,
    allowFormatProviders: Boolean
  ): LogSession = {
    val startNanos: Long = System.nanoTime()

    val storageReader: LogStorageReader = LogStorageReader.open(path, isLiveStream)
    try {
      val resolvedProfile: LogLanguageProfile = if (profile != null) profile else LanguageProfileRegistry.autoDetect(storageReader)

      val headerSlice = storageReader.readSlice(0L, math.min(storageReader.sizeBytes, 4096L))
      val formatProbeOpt = if (allowFormatProviders) {
        PluginManager.instance.probeFormatWithProvider(path, headerSlice, storageReader.sizeBytes)
      } else {
        None
      }

      if (formatProbeOpt.isDefined) {
        val (formatProvider, probeResult) = formatProbeOpt.get
        val framer = formatProvider.createRecordFramer(path, headerSlice)
        val formatter = formatProvider.createRecordFormatter(path, headerSlice)
        val framedIndexer = FramedRecordIndexerImpl.build(storageReader, framer, formatter)
        val durationMs: Long = (System.nanoTime() - startNanos) / 1000000L
        val searchEngine: SimdSearchEngine = new SimdSearchEngine(storageReader, framedIndexer, None)
        val slicer: LogSlicer = new LogSlicer(storageReader, framedIndexer)
        val emptyTemporal = new TemporalCheckpointTable(new LongArrayList(), new LongArrayList(), framedIndexer.totalLines)
        val emptyClassifier = new EventBoundaryClassifier(framedIndexer, new org.roaringbitmap.longlong.Roaring64NavigableMap())
        val session = new LogSession(
          path = path,
          storageReader = storageReader,
          indexer = framedIndexer,
          temporalIndexFuture = CompletableFuture.completedFuture(emptyTemporal),
          searchEngine = searchEngine,
          slicer = slicer,
          eventClassifierFuture = CompletableFuture.completedFuture(emptyClassifier),
          indexingDurationMs = durationMs,
          temporalProgressSupplier = () => 1.0,
          classifierProgressSupplier = () => 1.0,
          physicalIndexFuture = CompletableFuture.completedFuture(framedIndexer.sparseIndex),
          physicalProgressSupplier = () => 1.0,
          bytesScannedSupplier = () => storageReader.sizeBytes,
          minimapFuture = CompletableFuture.completedFuture(null),
          minimapProgressSupplier = () => 1.0,
          languageProfile = resolvedProfile,
          latencyGapFuture = CompletableFuture.completedFuture(LatencyGapResult.empty),
          isFusedIndexing = false,
          activeFormatProbeResult = Some(probeResult),
          activeFormatProviderId = Some(formatProvider.id)
        )
        PluginManager.instance.notifySessionOpened(session.pluginSessionView)
        try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
        session
      } else {
        @volatile var physicalProgress: Double = 0.0
        @volatile var bytesScanned: Long = 0L

        @volatile var temporalProgress: Double = 0.0
        @volatile var classifierProgress: Double = 0.0
        @volatile var minimapProgress: Double = 0.0

        val temporalFuturePromise = new CompletableFuture[TemporalCheckpointTable]()
      val classifierFuturePromise = new CompletableFuture[EventBoundaryClassifier]()
      val minimapFuturePromise = new CompletableFuture[MinimapDensityTable]()
      val latencyGapFuturePromise = new CompletableFuture[LatencyGapResult]()

      def triggerMinimapAndLatencyGap(finalIndexer: PhysicalLineIndexerImpl, tFuture: CompletableFuture[TemporalCheckpointTable]): Unit = {
        val gFuture = LatencyGapScanner.buildAsync(tFuture, LatencyGapScanner.DefaultThresholdMicros)
        gFuture.whenComplete((gRes, gErr) => {
          if (gErr != null) {
            latencyGapFuturePromise.complete(LatencyGapResult.empty)
          } else {
            latencyGapFuturePromise.complete(gRes)
          }
        })

        val mFuture = MinimapScanner.buildAsync(
          finalIndexer,
          resolvedProfile,
          (p: Double) => minimapProgress = p
        )
        mFuture.whenComplete((res, err) => {
          if (err != null) {
            minimapFuturePromise.completeExceptionally(err)
          } else {
            minimapFuturePromise.complete(res)
          }
        })
      }

      def triggerTier2(finalIndexer: PhysicalLineIndexerImpl): Unit = {
        val tFuture = TemporalCheckpointTable.buildAsync(
          finalIndexer,
          (p: Double) => temporalProgress = p
        )
        tFuture.whenComplete((res, err) => {
          if (err != null) {
            temporalFuturePromise.completeExceptionally(err)
            latencyGapFuturePromise.complete(LatencyGapResult.empty)
          } else {
            temporalFuturePromise.complete(res)
            val gFuture = LatencyGapScanner.buildAsync(tFuture, LatencyGapScanner.DefaultThresholdMicros)
            gFuture.whenComplete((gRes, gErr) => {
              if (gErr != null) {
                latencyGapFuturePromise.complete(LatencyGapResult.empty)
              } else {
                latencyGapFuturePromise.complete(gRes)
              }
            })
          }
        })

        val cFuture = EventBoundaryClassifier.buildAsync(
          finalIndexer,
          (p: Double) => classifierProgress = p
        )
        cFuture.whenComplete((res, err) => {
          if (err != null) {
            classifierFuturePromise.completeExceptionally(err)
          } else {
            classifierFuturePromise.complete(res)
            try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
            if (storageReader.path != null && java.nio.file.Files.exists(storageReader.path) && !isLiveStream) {
              tFuture.whenComplete((tRes, tErr) => {
                if (tErr == null && tRes != null) {
                  CompletableFuture.runAsync(() => {
                    PersistentIndexCache.saveTier2(storageReader.path, storageReader, tRes, res.continuationLinesBitmap)
                    try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
                  })
                }
              })
            }
          }
        })

        val mFuture = MinimapScanner.buildAsync(
          finalIndexer,
          resolvedProfile,
          (p: Double) => minimapProgress = p
        )
        mFuture.whenComplete((res, err) => {
          if (err != null) {
            minimapFuturePromise.completeExceptionally(err)
          } else {
            minimapFuturePromise.complete(res)
          }
        })
      }

      // AtomicReference guarantees immediate cross-thread publication from the background fused worker.
      val indexerRef = new java.util.concurrent.atomic.AtomicReference[PhysicalLineIndexerImpl]()
      var isFusedExecution: Boolean = false

      def startFusedIndexing(shouldPersistCache: Boolean): (PhysicalLineIndexerImpl, CompletableFuture[TwoLevelSparseIndex]) = {
        isFusedExecution = true
        var initialSnapshot: TwoLevelSparseIndex = null
        val physicalPromise = new CompletableFuture[TwoLevelSparseIndex]()
        val fusedFuture = FusedStreamingIndexer.buildStreamingProgressive(
          storageReader.path,
          storageReader = storageReader,
          onInitialChunkReady = (snapshot: TwoLevelSparseIndex) => {
            initialSnapshot = snapshot
          },
          onChunkUpdate = (snapshot: TwoLevelSparseIndex, scanned: Long, total: Long, _: Long) => {
            bytesScanned = scanned
            if (total > 0L) {
              val frac = scanned.toDouble / total.toDouble
              physicalProgress = frac
              temporalProgress = frac
              classifierProgress = frac
            }
            val publishedIndexer = indexerRef.get()
            if (snapshot != null && publishedIndexer != null) {
              publishedIndexer.updateSparseIndex(snapshot)
            }
          }
        )
        val createdIndexer = new PhysicalLineIndexerImpl(storageReader, initialSnapshot)
        indexerRef.set(createdIndexer)

        fusedFuture.whenComplete((fusedRes, err) => {
          if (err == null && fusedRes != null) {
            val finalIdx = fusedRes.sparseIndex
            createdIndexer.updateSparseIndex(finalIdx)
            bytesScanned = storageReader.sizeBytes
            physicalProgress = 1.0
            temporalProgress = 1.0
            classifierProgress = 1.0
            physicalPromise.complete(finalIdx)
            temporalFuturePromise.complete(fusedRes.temporalTable)
            classifierFuturePromise.complete(new EventBoundaryClassifier(createdIndexer, fusedRes.continuationBitmap))
            triggerMinimapAndLatencyGap(createdIndexer, CompletableFuture.completedFuture(fusedRes.temporalTable))
            try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
            if (shouldPersistCache) {
              CompletableFuture.runAsync(() => {
                PersistentIndexCache.save(storageReader.path, storageReader, finalIdx)
                PersistentIndexCache.saveTier2(storageReader.path, storageReader, fusedRes.temporalTable, fusedRes.continuationBitmap)
                try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
              })
            }
          } else if (err != null) {
            physicalPromise.completeExceptionally(err)
            temporalFuturePromise.completeExceptionally(err)
            classifierFuturePromise.completeExceptionally(err)
            minimapFuturePromise.completeExceptionally(err)
            latencyGapFuturePromise.complete(LatencyGapResult.empty)
          }
        })
        (createdIndexer, physicalPromise)
      }

      val (indexer, physicalFuture): (PhysicalLineIndexerImpl, CompletableFuture[TwoLevelSparseIndex]) =
        if (storageReader.path != null && java.nio.file.Files.exists(storageReader.path) && !isLiveStream) {
          val cachedIndexOpt = PersistentIndexCache.tryLoad(storageReader.path, storageReader)
          if (cachedIndexOpt.isDefined) {
            val cachedIdx = cachedIndexOpt.get
            val createdIndexer = new PhysicalLineIndexerImpl(storageReader, cachedIdx)
            bytesScanned = storageReader.sizeBytes
            physicalProgress = 1.0

            val cachedTier2Opt = PersistentIndexCache.tryLoadTier2(storageReader.path, storageReader, cachedIdx.totalLinesCount)
            if (cachedTier2Opt.isDefined) {
              val cachedTier2 = cachedTier2Opt.get
              temporalProgress = 1.0
              classifierProgress = 1.0
              temporalFuturePromise.complete(cachedTier2.temporalTable)
              classifierFuturePromise.complete(new EventBoundaryClassifier(createdIndexer, cachedTier2.continuationLinesBitmap))
              triggerMinimapAndLatencyGap(createdIndexer, CompletableFuture.completedFuture(cachedTier2.temporalTable))
              try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
            } else {
              triggerTier2(createdIndexer)
            }
            (createdIndexer, CompletableFuture.completedFuture(cachedIdx))
          } else {
            startFusedIndexing(shouldPersistCache = true)
          }
        } else if (storageReader.path != null && java.nio.file.Files.exists(storageReader.path)) {
          startFusedIndexing(shouldPersistCache = false)
        } else {
          val idx = TwoLevelSparseIndex.build(storageReader)
          val createdIndexer = new PhysicalLineIndexerImpl(storageReader, idx)
          physicalProgress = 1.0
          bytesScanned = storageReader.sizeBytes
          triggerTier2(createdIndexer)
          (createdIndexer, CompletableFuture.completedFuture(idx))
        }

      val durationMs: Long = (System.nanoTime() - startNanos) / 1000000L
      val searchEngine: SimdSearchEngine = new SimdSearchEngine(storageReader, indexer, None)
      val slicer: LogSlicer = new LogSlicer(storageReader, indexer)

        val session = new LogSession(
          path,
          storageReader,
          indexer,
          temporalFuturePromise,
          searchEngine,
          slicer,
          classifierFuturePromise,
          durationMs,
          () => temporalProgress,
          () => classifierProgress,
          physicalFuture,
          () => physicalProgress,
          () => bytesScanned,
          minimapFuturePromise,
          () => minimapProgress,
          resolvedProfile,
          latencyGapFuturePromise,
          isFusedIndexing = isFusedExecution
        )
        PluginManager.instance.notifySessionOpened(session.pluginSessionView)
        try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
        session
      }
    } catch {
      case t: Throwable =>
        storageReader.close()
        throw t
    }
  }
}
