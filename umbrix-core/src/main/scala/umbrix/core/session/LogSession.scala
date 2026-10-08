package umbrix.core.session

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.classifier.EventBoundaryClassifier
import umbrix.core.minimap.{MinimapDensityTable, MinimapScanner}
import umbrix.core.parser.QueryParseError
import umbrix.core.profile.{JavaJvmProfile, LanguageProfileRegistry, LogLanguageProfile}
import umbrix.core.search.SearchResult
import umbrix.core.search.SimdSearchEngine
import umbrix.core.slicing.LogSlicer
import umbrix.core.slicing.SliceResult
import umbrix.core.temporal.{LatencyGapResult, LatencyGapScanner}
import umbrix.core.types.{ByteOffset, EpochMicros, LineIndex}
import umbrix.core.view.FilteredLineView
import umbrix.index.{PersistentIndexCache, PhysicalLineIndexerImpl, TemporalCheckpointTable, TwoLevelSparseIndex}
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
  val indexer: PhysicalLineIndexerImpl,
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
  val latencyGapFuture: CompletableFuture[LatencyGapResult] = CompletableFuture.completedFuture(LatencyGapResult.empty)
) extends AutoCloseable {

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
      math.min(99.9, math.max(0.0, physicalProgressSupplier() * 100.0))
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
    if (isTier2Complete) {
      100.0
    } else {
      val tProg = if (isTemporalIndexComplete) 1.0 else temporalProgressSupplier()
      val cProg = if (isClassifierComplete) 1.0 else classifierProgressSupplier()
      val combined = (tProg + cProg) / 2.0
      math.min(99.9, math.max(0.0, combined * 100.0))
    }
  }

  /** True if all indexing (Tier 1 physical newline scan + Tier 2 semantic metadata) has completed. */
  def isIndexingComplete: Boolean = isPhysicalIndexComplete && isTier2Complete

  /**
   * Combined background indexing progress percentage from 0.0 to 100.0.
   * Physical indexing represents the first 70% of work; Tier 2 metadata represents the remaining 30%.
   */
  def indexingProgressPercent: Double = {
    if (isIndexingComplete) {
      100.0
    } else if (!isPhysicalIndexComplete) {
      val phys = physicalIndexProgressPercent
      math.min(69.9, phys * 0.70)
    } else {
      val t2 = tier2ProgressPercent
      math.min(99.9, 70.0 + (t2 * 0.30))
    }
  }

  private def formatByteSize(bytes: Long): String = {
    if (bytes >= 1024L * 1024L * 1024L) {
      val gb = bytes.toDouble / (1024.0 * 1024.0 * 1024.0)
      f"$gb%.2f GB"
    } else if (bytes >= 1024L * 1024L) {
      val mb = bytes.toDouble / (1024.0 * 1024.0)
      f"$mb%.2f MB"
    } else if (bytes >= 1024L) {
      val kb = bytes.toDouble / 1024.0
      f"$kb%.1f KB"
    } else {
      s"${bytes} B"
    }
  }

  private def formatNumberWithCommas(value: Long): String = {
    java.text.NumberFormat.getIntegerInstance.format(value)
  }

  /**
   * Human-readable diagnostic status string describing current indexing activity and metrics.
   */
  def indexingStatusText: String = {
    if (isIndexingComplete) {
      s"Ready — ${fileName} (${formatByteSize(fileSizeBytes)}, ${formatNumberWithCommas(totalLines.value)} lines, ${formatNumberWithCommas(totalEvents)} events)"
    } else if (!isPhysicalIndexComplete) {
      val pct = physicalIndexProgressPercent.toInt
      val scanned = formatByteSize(bytesScanned)
      val total = formatByteSize(fileSizeBytes)
      val lines = formatNumberWithCommas(totalLines.value)
      s"Indexing ${fileName} (SIMD): ${pct}% (${scanned} / ${total} — ${lines} lines)"
    } else {
      val pct = tier2ProgressPercent.toInt
      s"Analyzing ${fileName}: ${pct}% (RoaringBitmap event boundaries & checkpoints)"
    }
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
        case _: Throwable => EventBoundaryClassifier.unclassified(indexer)
      }
    } else {
      EventBoundaryClassifier.unclassified(indexer)
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
    searchEngine.searchProgressive(queryText, (res: SearchResult) => progressConsumer.accept(res))
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

  /**
   * Fast seek to the next error or warning line after `startLine`.
   * Leverages the 1,000-bin density table to skip non-anomaly regions in O(1) time.
   * Returns -1L if no anomaly is found.
   */
  def findNextAnomaly(startLine: Long): Long = {
    val table = minimapTableOrNull
    val total = totalLines.value
    if (table != null && total > 0L) {
      val startBin = table.binForLine((startLine + 1L).max(0L), total)
      var foundLine = -1L
      var b = startBin
      while (b < table.binCount && foundLine < 0L) {
        if (table.hasAnomalies(b)) {
          val (bStart, bEnd) = table.lineRangeForBin(b, total)
          var line = math.max(startLine + 1L, bStart)
          while (line <= bEnd && foundLine < 0L) {
            val text = getLineText(LineIndex(line), 256)
            val sev = languageProfile match {
              case base: umbrix.core.profile.BaseLogLanguageProfile => base.classifyLineSeverity(text)
              case _ => umbrix.core.profile.LineSeverity.Normal
            }
            if (sev == umbrix.core.profile.LineSeverity.Error || sev == umbrix.core.profile.LineSeverity.Warn) {
              foundLine = line
            } else {
              line += 1L
            }
          }
        }
        b += 1
      }
      foundLine
    } else {
      -1L
    }
  }

  /**
   * Fast seek to the previous error or warning line before `startLine`.
   * Leverages the 1,000-bin density table to skip non-anomaly regions in O(1) time.
   * Returns -1L if no anomaly is found.
   */
  def findPreviousAnomaly(startLine: Long): Long = {
    val table = minimapTableOrNull
    val total = totalLines.value
    if (table != null && total > 0L && startLine > 0L) {
      val startBin = table.binForLine(startLine - 1L, total)
      var foundLine = -1L
      var b = startBin
      while (b >= 0 && foundLine < 0L) {
        if (table.hasAnomalies(b)) {
          val (bStart, bEnd) = table.lineRangeForBin(b, total)
          var line = math.min(startLine - 1L, bEnd)
          while (line >= bStart && foundLine < 0L) {
            val text = getLineText(LineIndex(line), 256)
            val sev = languageProfile match {
              case base: umbrix.core.profile.BaseLogLanguageProfile => base.classifyLineSeverity(text)
              case _ => umbrix.core.profile.LineSeverity.Normal
            }
            if (sev == umbrix.core.profile.LineSeverity.Error || sev == umbrix.core.profile.LineSeverity.Warn) {
              foundLine = line
            } else {
              line -= 1L
            }
          }
        }
        b -= 1
      }
      foundLine
    } else {
      -1L
    }
  }

  /**
   * Trims physical memory pages committed to the OS process working set from this log file session.
   */
  def trimWorkingSet(): Unit = {
    storageReader.trimWorkingSet()
  }

  def close(): Unit = {
    try {
      if (physicalIndexFuture != null && !physicalIndexFuture.isDone) {
        physicalIndexFuture.cancel(true)
      }
    } catch {
      case _: Throwable => ()
    }

    try {
      if (latencyGapFuture != null && !latencyGapFuture.isDone) {
        latencyGapFuture.cancel(true)
      }
    } catch {
      case _: Throwable => ()
    }

    try {
      if (minimapFuture != null && !minimapFuture.isDone) {
        minimapFuture.cancel(true)
      }
    } catch {
      case _: Throwable => ()
    }

    try {
      if (!temporalIndexFuture.isDone) {
        temporalIndexFuture.cancel(true)
      }
    } catch {
      case _: Throwable => ()
    }

    try {
      if (!eventClassifierFuture.isDone) {
        eventClassifierFuture.cancel(true)
      }
    } catch {
      case _: Throwable => ()
    }

    try {
      searchEngine.close()
    } catch {
      case _: Throwable => ()
    }

    try {
      indexer.close()
    } catch {
      case _: Throwable => ()
    }

    try {
      storageReader.close()
    } catch {
      case _: Throwable => ()
    }
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
  def open(path: Path): LogSession = open(path, false, null)

  def open(path: Path, isLiveStream: Boolean): LogSession = open(path, isLiveStream, null)

  def open(path: Path, isLiveStream: Boolean, profile: LogLanguageProfile): LogSession = {
    val startNanos: Long = System.nanoTime()

    val storageReader: LogStorageReader = LogStorageReader.open(path, isLiveStream)
    try {
      val resolvedProfile: LogLanguageProfile = if (profile != null) profile else LanguageProfileRegistry.autoDetect(storageReader)

      @volatile var physicalProgress: Double = 0.0
      @volatile var bytesScanned: Long = 0L

      @volatile var temporalProgress: Double = 0.0
      @volatile var classifierProgress: Double = 0.0
      @volatile var minimapProgress: Double = 0.0

      val temporalFuturePromise = new CompletableFuture[TemporalCheckpointTable]()
      val classifierFuturePromise = new CompletableFuture[EventBoundaryClassifier]()
      val minimapFuturePromise = new CompletableFuture[MinimapDensityTable]()
      val latencyGapFuturePromise = new CompletableFuture[LatencyGapResult]()

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

      var indexerRef: PhysicalLineIndexerImpl = null

      val (indexer, physicalFuture): (PhysicalLineIndexerImpl, CompletableFuture[TwoLevelSparseIndex]) =
        if (storageReader.path != null && java.nio.file.Files.exists(storageReader.path) && !isLiveStream) {
          val cachedIndexOpt = PersistentIndexCache.tryLoad(storageReader.path, storageReader)
          if (cachedIndexOpt.isDefined) {
            val cachedIdx = cachedIndexOpt.get
            val createdIndexer = new PhysicalLineIndexerImpl(storageReader, cachedIdx)
            bytesScanned = storageReader.sizeBytes
            physicalProgress = 1.0
            triggerTier2(createdIndexer)
            (createdIndexer, CompletableFuture.completedFuture(cachedIdx))
          } else {
            var initialSnapshot: TwoLevelSparseIndex = null
            val pFuture = TwoLevelSparseIndex.buildStreamingProgressive(
              storageReader.path,
              onInitialChunkReady = (snapshot: TwoLevelSparseIndex) => {
                initialSnapshot = snapshot
              },
              onChunkUpdate = (snapshot: TwoLevelSparseIndex, scanned: Long, total: Long, lines: Long) => {
                bytesScanned = scanned
                if (total > 0L) {
                  physicalProgress = scanned.toDouble / total.toDouble
                }
                if (indexerRef != null) {
                  indexerRef.updateSparseIndex(snapshot)
                }
              }
            )
            val createdIndexer = new PhysicalLineIndexerImpl(storageReader, initialSnapshot)
            indexerRef = createdIndexer

            pFuture.whenComplete((finalIdx, err) => {
              if (err == null && finalIdx != null) {
                createdIndexer.updateSparseIndex(finalIdx)
                bytesScanned = storageReader.sizeBytes
                physicalProgress = 1.0
                PersistentIndexCache.save(storageReader.path, storageReader, finalIdx)
                triggerTier2(createdIndexer)
              } else if (err != null) {
                temporalFuturePromise.completeExceptionally(err)
                classifierFuturePromise.completeExceptionally(err)
                minimapFuturePromise.completeExceptionally(err)
                latencyGapFuturePromise.complete(LatencyGapResult.empty)
              }
            })
            (createdIndexer, pFuture)
          }
        } else if (storageReader.path != null && java.nio.file.Files.exists(storageReader.path)) {
          var initialSnapshot: TwoLevelSparseIndex = null
          val pFuture = TwoLevelSparseIndex.buildStreamingProgressive(
            storageReader.path,
            onInitialChunkReady = (snapshot: TwoLevelSparseIndex) => {
              initialSnapshot = snapshot
            },
            onChunkUpdate = (snapshot: TwoLevelSparseIndex, scanned: Long, total: Long, lines: Long) => {
              bytesScanned = scanned
              if (total > 0L) {
                physicalProgress = scanned.toDouble / total.toDouble
              }
              if (indexerRef != null) {
                indexerRef.updateSparseIndex(snapshot)
              }
            }
          )
          val createdIndexer = new PhysicalLineIndexerImpl(storageReader, initialSnapshot)
          indexerRef = createdIndexer

          pFuture.whenComplete((finalIdx, err) => {
            if (err == null && finalIdx != null) {
              createdIndexer.updateSparseIndex(finalIdx)
              bytesScanned = storageReader.sizeBytes
              physicalProgress = 1.0
              triggerTier2(createdIndexer)
            } else if (err != null) {
              temporalFuturePromise.completeExceptionally(err)
              classifierFuturePromise.completeExceptionally(err)
              minimapFuturePromise.completeExceptionally(err)
              latencyGapFuturePromise.complete(LatencyGapResult.empty)
            }
          })
          (createdIndexer, pFuture)
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

      new LogSession(
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
        latencyGapFuturePromise
      )
    } catch {
      case t: Throwable =>
        storageReader.close()
        throw t
    }
  }
}
