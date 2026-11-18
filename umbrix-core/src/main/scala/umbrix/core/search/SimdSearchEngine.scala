package umbrix.core.search

import com.google.re2j.Matcher
import com.google.re2j.Pattern
import java.lang.foreign.MemorySegment
import scala.annotation.tailrec
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.roaringbitmap.longlong.Roaring64NavigableMap
import scala.jdk.CollectionConverters.*
import umbrix.core.model.QueryAst
import umbrix.core.parser.QueryParseError
import umbrix.core.parser.QueryParser
import umbrix.core.types.{ByteOffset, EpochMicros, LineIndex}
import umbrix.index.PhysicalLineIndexer
import umbrix.index.TemporalCheckpointTable
import umbrix.simd.VectorScanner
import umbrix.simd.VectorSubstringScanner
import umbrix.vfs.LogStorageReader
import umbrix.vfs.platform.PlatformMemoryAdvisor

/**
 * Per-chunk line resolver that deduplicates multiple matches occurring on the same physical line.
 */
private final class ChunkLineResolver(
  lineIndexer: PhysicalLineIndexer,
  chunkSlice: MemorySegment,
  chunkStartOffset: Long,
  chunkEndOffset: Long,
  localResultBitmap: Roaring64NavigableMap
) {
  private val totalLinesLimit: Long = lineIndexer.totalLines.value
  private var lastResolvedLineIndex: Long = -1L

  def recordMatch(absoluteMatchOffset: Long): Unit = {
    if (absoluteMatchOffset < chunkEndOffset) {
      val resolvedLine: Long = lineIndexer.lineIndexForByteOffset(ByteOffset(absoluteMatchOffset)).value
      if (resolvedLine != lastResolvedLineIndex && resolvedLine >= 0L && resolvedLine < totalLinesLimit) {
        lastResolvedLineIndex = resolvedLine
        localResultBitmap.add(resolvedLine)
      }
    }
  }

  def finishChunk(): Unit = ()
}


/**
 * High-performance hardware SIMD search and query execution engine.
 *
 * Coordinates parallel chunk-based vector filtering across massive off-heap memory segments:
 * 1. Literal queries utilize `VectorSubstringScanner` (Ripgrep 2-byte candidate filter) at 15–25 GB/s.
 * 2. Regular expressions run with guaranteed O(N * M) linear-time execution via RE2J and `OffHeapCharSequence`.
 * 3. Compound Boolean expressions (AND / OR / NOT) evaluate via compressed RoaringBitmap bitwise operations.
 * 4. Pinned platform carrier threads (`Thread.ofPlatform()`) saturate multi-channel hardware memory bandwidth.
 * 5. Cooperative atomic query cancellation ensures immediate responsiveness upon typing and aborts obsolete searches.
 */
class SimdSearchEngine(
  val storageReader: LogStorageReader,
  val lineIndexer: PhysicalLineIndexer,
  val temporalTableOption: Option[TemporalCheckpointTable] = None,
  val workerThreadsCount: Int = PlatformMemoryAdvisor.recommendedBackgroundWorkerCount,
  val partitionChunkSizeBytes: Long = 16L * 1024L * 1024L
) extends AutoCloseable {

  private val threadFactory: ThreadFactory =
    Thread.ofPlatform().name("umbrix-search-worker-", 0).factory()

  private val executorService: ExecutorService =
    Executors.newFixedThreadPool(workerThreadsCount, threadFactory)

  /** Monotonic query generation ID for cooperative cancellation of superseded searches. */
  private val currentQueryId: java.util.concurrent.atomic.AtomicLong =
    new java.util.concurrent.atomic.AtomicLong(0L)

  @volatile private var activeFutures: java.util.List[java.util.concurrent.Future[java.lang.Boolean]] =
    java.util.Collections.emptyList()

  /**
   * Cooperatively aborts any currently executing search across all worker threads.
   * Increments the query generation and cancels active chunk tasks immediately.
   */
  def abortCurrentSearch(): Unit = {
    currentQueryId.incrementAndGet()
    val futuresToCancel = activeFutures
    if (futuresToCancel != null && !futuresToCancel.isEmpty) {
      val iterator = futuresToCancel.iterator()
      while (iterator.hasNext) {
        iterator.next().cancel(true)
      }
    }
  }

  /** Threshold above which working set is trimmed after search (2GB) so normal logs remain hot in OS RAM cache. */
  private final val workingSetTrimThresholdBytes: Long = 2L * 1024L * 1024L * 1024L

  /**
   * Parses the raw query text and executes it across the log document.
   *
   * @param queryText raw search expression
   * @return `Right(SearchResult)` or `Left(QueryParseError)` on syntax failure
   */
  def search(queryText: String): Either[QueryParseError, SearchResult] = {
    searchProgressive(queryText, _ => ())
  }

  /**
   * Parses the raw query text and executes it with progressive partial result notifications.
   *
   * @param queryText raw search expression
   * @param progressConsumer callback receiving incremental SearchResult snapshots as matches are discovered
   * @return `Right(SearchResult)` with final complete result or `Left(QueryParseError)` on syntax failure
   */
  def searchProgressive(
    queryText: String,
    progressConsumer: SearchResult => Unit
  ): Either[QueryParseError, SearchResult] = {
    searchProgressive(queryText, 0L, progressConsumer)
  }

  /**
   * Parses the raw query text and executes it with progressive partial result notifications,
   * prioritizing chunk scanning from the partition covering `anchorByteOffset`.
   *
   * @param queryText raw search expression
   * @param anchorByteOffset absolute byte offset used to prioritize chunk scheduling
   * @param progressConsumer callback receiving incremental SearchResult snapshots as matches are discovered
   * @return `Right(SearchResult)` with final complete result or `Left(QueryParseError)` on syntax failure
   */
  def searchProgressive(
    queryText: String,
    anchorByteOffset: Long,
    progressConsumer: SearchResult => Unit
  ): Either[QueryParseError, SearchResult] = {
    QueryParser.parse(queryText).map(ast => executeProgressive(ast, anchorByteOffset, progressConsumer))
  }

  /**
   * Evaluates a structured `QueryAst` and returns the matching physical lines synchronously.
   */
  def execute(ast: QueryAst): SearchResult = {
    executeProgressive(ast, 0L, _ => ())
  }

  /**
   * Evaluates a structured `QueryAst` with progressive notifications on first match and batch updates.
   */
  def executeProgressive(
    ast: QueryAst,
    progressConsumer: SearchResult => Unit
  ): SearchResult = {
    executeProgressive(ast, 0L, progressConsumer)
  }

  /**
   * Evaluates a structured `QueryAst` with progressive notifications, prioritizing scanning from
   * the partition covering `anchorByteOffset`.
   */
  def executeProgressive(
    ast: QueryAst,
    anchorByteOffset: Long,
    progressConsumer: SearchResult => Unit
  ): SearchResult = {
    val queryId: Long = currentQueryId.incrementAndGet()
    val startNanos: Long = System.nanoTime()
    val matchingBitmap: Roaring64NavigableMap = evaluateAst(ast, progressConsumer, startNanos, queryId, anchorByteOffset, None)
    val durationNanos: Long = System.nanoTime() - startNanos
    val isComplete: Boolean = currentQueryId.get() == queryId
    val finalResult: SearchResult = SearchResult(
      matchingBitmap,
      matchingBitmap.getLongCardinality,
      durationNanos,
      isComplete = isComplete
    )
    if (isComplete) {
      progressConsumer(finalResult)
    }
    finalResult
  }

  private def evaluateAst(
    ast: QueryAst,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  ): Roaring64NavigableMap = {
    if (currentQueryId.get() != queryId) {
      new Roaring64NavigableMap()
    } else {
      ast match {
        case QueryAst.Empty =>
          new Roaring64NavigableMap()

        case QueryAst.Literal(needleText, isCaseSensitive) =>
          if (needleText.isEmpty) {
            new Roaring64NavigableMap()
          } else if (isCaseSensitive) {
            searchLiteralParallel(needleText, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)
          } else if (isAscii(needleText)) {
            searchLiteralCaseInsensitiveParallel(needleText, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)
          } else {
            val caseInsensitiveRegex: Pattern = Pattern.compile("(?i)" + Pattern.quote(needleText))
            searchRegexParallel(caseInsensitiveRegex, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)
          }

        case QueryAst.Regex(pattern) =>
          searchRegexParallel(pattern, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)

        case QueryAst.LevelFilter(logLevel) =>
          val levelToken: String = logLevel.toString.toUpperCase
          val levelRegex: Pattern = Pattern.compile("\\b" + Pattern.quote(levelToken) + "\\b")
          searchRegexParallel(levelRegex, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)

        case QueryAst.TimeRange(sinceMicrosOption, untilMicrosOption) =>
          temporalTableOption match {
            case Some(temporalTable) =>
              val total: LineIndex = lineIndexer.totalLines
              val maxValid: LineIndex = if (total > LineIndex.Zero) total - 1L else LineIndex.Zero
              val startLine: LineIndex = sinceMicrosOption.map(m => temporalTable.seekNearestLine(EpochMicros(m))).getOrElse(LineIndex.Zero)
              val endLine: LineIndex = untilMicrosOption.map(m => temporalTable.seekNearestLine(EpochMicros(m))).getOrElse(maxValid)
              val rangeBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
              if (startLine <= endLine && startLine < total) {
                val endRange: LineIndex = (endLine + 1L).min(total)
                rangeBitmap.addRange(startLine.value, endRange.value)
              }
              rangeBitmap
            case None =>
              val allLinesBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
              allLinesBitmap.addRange(0L, lineIndexer.totalLines.value)
              allLinesBitmap
          }

        case QueryAst.And(QueryAst.Literal(textA, csA), QueryAst.Literal(textB, csB))
            if textA.nonEmpty && textB.nonEmpty && csA == csB && (csA || (isAscii(textA) && isAscii(textB))) =>
          searchDualLiteralParallel(textA, textB, isCaseSensitive = csA, isAnd = true, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)

        case QueryAst.And(leftAst, rightAst) =>
          // Evaluate cheaper/metadata filters (like TimeRange) first so rightAst can skip empty 16MB chunks
          val (firstAst, secondAst) = rightAst match {
            case _: QueryAst.TimeRange if !leftAst.isInstanceOf[QueryAst.TimeRange] => (rightAst, leftAst)
            case _ => (leftAst, rightAst)
          }
          val firstBitmap: Roaring64NavigableMap = evaluateAst(firstAst, _ => (), startNanos, queryId, anchorByteOffset, candidateFilter)
          if (firstBitmap.isEmpty || currentQueryId.get() != queryId) {
            firstBitmap
          } else {
            val rightBitmap: Roaring64NavigableMap = evaluateAst(secondAst, _ => (), startNanos, queryId, anchorByteOffset, Some(firstBitmap))
            val result: Roaring64NavigableMap = new Roaring64NavigableMap()
            result.or(firstBitmap)
            result.and(rightBitmap)
            result
          }

        case QueryAst.Or(QueryAst.Literal(textA, csA), QueryAst.Literal(textB, csB))
            if textA.nonEmpty && textB.nonEmpty && csA == csB && (csA || (isAscii(textA) && isAscii(textB))) =>
          searchDualLiteralParallel(textA, textB, isCaseSensitive = csA, isAnd = false, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter)

        case QueryAst.Or(leftAst, rightAst) =>
          val leftBitmap: Roaring64NavigableMap = evaluateAst(leftAst, _ => (), startNanos, queryId, anchorByteOffset, candidateFilter)
          if (currentQueryId.get() != queryId) {
            leftBitmap
          } else {
            val rightBitmap: Roaring64NavigableMap = evaluateAst(rightAst, _ => (), startNanos, queryId, anchorByteOffset, candidateFilter)
            val result: Roaring64NavigableMap = new Roaring64NavigableMap()
            result.or(leftBitmap)
            result.or(rightBitmap)
            result
          }

        case QueryAst.Not(innerAst) =>
          val innerBitmap: Roaring64NavigableMap = evaluateAst(innerAst, _ => (), startNanos, queryId, anchorByteOffset, None)
          val allLinesBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
          allLinesBitmap.addRange(0L, lineIndexer.totalLines.value)
          allLinesBitmap.andNot(innerBitmap)
          allLinesBitmap

        case QueryAst.CustomFunction(fnName, args) =>
          umbrix.core.plugin.PluginManager.instance.getQueryFunction(fnName) match {
            case Some(queryFn) =>
              val sessionView = umbrix.core.plugin.PluginManager.instance.getWorkspaceController.activeSession
              if (sessionView != null) {
                queryFn.evaluate(sessionView, args.toArray)
              } else {
                new Roaring64NavigableMap()
              }
            case None =>
              new Roaring64NavigableMap()
          }
      }
    }
  }

  /**
   * Checks whether `candidateBitmap` contains at least one line within `[chunkStartOffset, chunkEndOffset)`.
   */
  private def chunkHasCandidateLines(
    candidateBitmap: Roaring64NavigableMap,
    chunkStartOffset: Long,
    chunkEndOffset: Long
  ): Boolean = {
    if (candidateBitmap.isEmpty) {
      false
    } else {
      val chunkStartLine: Long = lineIndexer.lineIndexForByteOffset(ByteOffset(chunkStartOffset)).value
      val chunkEndLine: Long = lineIndexer.lineIndexForByteOffset(ByteOffset((chunkEndOffset - 1L).max(chunkStartOffset))).value
      val beforeStart: Long = if (chunkStartLine > 0L) {
        candidateBitmap.rankLong(chunkStartLine - 1L)
      } else {
        0L
      }
      val atEnd: Long = candidateBitmap.rankLong(chunkEndLine)
      (atEnd - beforeStart) > 0L
    }
  }

  /**
   * Universal chunk-partitioned parallel search coordinator.
   *
   * Dispatches non-blocking carrier platform worker tasks across 16MB file partitions,
   * coordinates thread-safe match aggregation into a Roaring64NavigableMap, streams
   * throttled progressive search updates to `progressConsumer`, and cooperatively aborts
   * upon cancellation.
   *
   * @param overlapBytes trailing overlap bytes to extend chunk slice (e.g. needle.length - 1 for literals)
   * @param progressConsumer callback receiving incremental partial SearchResult snapshots
   * @param startNanos search commencement timestamp in nanoseconds
   * @param queryId unique generation ID identifying this search pass for cancellation checking
   * @param anchorByteOffset absolute byte offset used to prioritize task scheduling order
   * @param candidateFilter optional pre-filtered line bitmap (from left side of AND) to prune empty chunks
   * @param chunkScanner function scanning a single off-heap chunk slice and recording matches into local resolver
   * @return combined Roaring64NavigableMap containing all matching line numbers
   */
  private sealed trait ChunkWorkerState {
    def scanChunk(
      lineIndexer: PhysicalLineIndexer,
      chunkSlice: MemorySegment,
      chunkStartOffset: Long,
      chunkEndOffset: Long,
      extendedChunkLength: Long,
      workerBitmap: Roaring64NavigableMap
    ): Unit
    def cleanup(): Unit
  }

  private sealed trait ChunkScanKernel {
    def createWorkerState(): ChunkWorkerState
  }

  private def dispatchParallelSearch(
    overlapBytes: Long,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  )(
    chunkScanner: (MemorySegment, Long, Long, ChunkLineResolver) => Unit
  ): Roaring64NavigableMap = {
    val sharedWorkerState = new ChunkWorkerState {
      def scanChunk(
        lineIndexer: PhysicalLineIndexer,
        chunkSlice: MemorySegment,
        chunkStartOffset: Long,
        chunkEndOffset: Long,
        extendedChunkLength: Long,
        workerBitmap: Roaring64NavigableMap
      ): Unit = {
        val resolver = new ChunkLineResolver(
          lineIndexer,
          chunkSlice,
          chunkStartOffset,
          chunkEndOffset,
          workerBitmap
        )
        chunkScanner(chunkSlice, chunkStartOffset, extendedChunkLength, resolver)
        resolver.finishChunk()
      }
      def cleanup(): Unit = ()
    }
    val kernel = new ChunkScanKernel {
      def createWorkerState(): ChunkWorkerState = sharedWorkerState
    }
    dispatchParallelSearchKernel(
      overlapBytes,
      progressConsumer,
      startNanos,
      queryId,
      anchorByteOffset,
      candidateFilter,
      kernel
    )
  }

  /**
   * Universal chunk-partitioned parallel coordinator for fused dual-literal (AND / OR) search.
   */
  private def dispatchParallelDualSearch(
    overlapBytes: Long,
    isAnd: Boolean,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  )(
    dualChunkScanner: (MemorySegment, Long, Long, ChunkLineResolver, ChunkLineResolver) => Unit
  ): Roaring64NavigableMap = {
    val kernel = new ChunkScanKernel {
      def createWorkerState(): ChunkWorkerState = new ChunkWorkerState {
        // Allocated once per worker thread and cleared per chunk to eliminate per-chunk heap churn.
        private val chunkBitmapA = new Roaring64NavigableMap()
        private val chunkBitmapB = new Roaring64NavigableMap()

        def scanChunk(
          lineIndexer: PhysicalLineIndexer,
          chunkSlice: MemorySegment,
          chunkStartOffset: Long,
          chunkEndOffset: Long,
          extendedChunkLength: Long,
          workerBitmap: Roaring64NavigableMap
        ): Unit = {
          chunkBitmapA.clear()
          chunkBitmapB.clear()
          val resolverA = new ChunkLineResolver(
            lineIndexer,
            chunkSlice,
            chunkStartOffset,
            chunkEndOffset,
            chunkBitmapA
          )
          val resolverB = new ChunkLineResolver(
            lineIndexer,
            chunkSlice,
            chunkStartOffset,
            chunkEndOffset,
            chunkBitmapB
          )
          dualChunkScanner(chunkSlice, chunkStartOffset, extendedChunkLength, resolverA, resolverB)
          resolverA.finishChunk()
          resolverB.finishChunk()

          if (isAnd) {
            chunkBitmapA.and(chunkBitmapB)
            workerBitmap.or(chunkBitmapA)
          } else {
            workerBitmap.or(chunkBitmapA)
            workerBitmap.or(chunkBitmapB)
          }
        }

        def cleanup(): Unit = {
          chunkBitmapA.clear()
          chunkBitmapB.clear()
        }
      }
    }
    dispatchParallelSearchKernel(
      overlapBytes,
      progressConsumer,
      startNanos,
      queryId,
      anchorByteOffset,
      candidateFilter,
      kernel
    )
  }

  private def dispatchParallelSearchKernel(
    overlapBytes: Long,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long,
    candidateFilter: Option[Roaring64NavigableMap],
    kernel: ChunkScanKernel
  ): Roaring64NavigableMap = {
    // Matches past the indexed region cannot be mapped to lines; clamp until indexing completes.
    val fileSize: Long = storageReader.sizeBytes.min(lineIndexer.indexedByteLimit)
    if (fileSize <= 0L || currentQueryId.get() != queryId) {
      new Roaring64NavigableMap()
    } else {
      val chunkCount: Int = ((fileSize + partitionChunkSizeBytes - 1L) / partitionChunkSizeBytes).toInt
      val activeWorkers: Int = math.min(workerThreadsCount, chunkCount)
      val tasks: java.util.ArrayList[Callable[java.lang.Boolean]] = new java.util.ArrayList[Callable[java.lang.Boolean]](activeWorkers)

      val combinedBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
      val stateLock: AnyRef = new AnyRef()
      val completedChunksCount: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)
      val firstMatchNotified: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)
      val lastProgressTimeNanos: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(startNanos)
      val throttleIntervalNanos: Long = 50_000_000L // 50 ms

      val clampedAnchorOffset: Long = math.max(0L, math.min(fileSize - 1L, anchorByteOffset))
      val anchorChunkIndex: Int = math.max(0, math.min(chunkCount - 1, (clampedAnchorOffset / partitionChunkSizeBytes).toInt))
      val nextStepCursor: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)

      var workerIdx: Int = 0
      while (workerIdx < activeWorkers) {
        tasks.add(() => {
          val workerBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
          val workerState: ChunkWorkerState = kernel.createWorkerState()
          try {
            var step: Int = nextStepCursor.getAndIncrement()
            while (step < chunkCount && currentQueryId.get() == queryId && !Thread.currentThread().isInterrupted) {
              val currentChunkIndex: Int = (anchorChunkIndex + step) % chunkCount
              val nominalStartOffset: Long = currentChunkIndex.toLong * partitionChunkSizeBytes
              val nominalEndOffset: Long = (nominalStartOffset + partitionChunkSizeBytes).min(fileSize)

              val chunkStartOffset: Long = alignToLineStart(nominalStartOffset, fileSize)
              val chunkEndOffset: Long = alignToLineStart(nominalEndOffset, fileSize)
              if (chunkEndOffset > chunkStartOffset) {
                val shouldScanChunk: Boolean = candidateFilter match {
                  case Some(filterBitmap) => chunkHasCandidateLines(filterBitmap, chunkStartOffset, chunkEndOffset)
                  case None => true
                }
                if (shouldScanChunk) {
                  val extendedChunkLength: Long = (chunkEndOffset - chunkStartOffset + overlapBytes).min(fileSize - chunkStartOffset)
                  val chunkSlice: MemorySegment = storageReader.readSlice(chunkStartOffset, extendedChunkLength)
                  workerState.scanChunk(
                    lineIndexer,
                    chunkSlice,
                    chunkStartOffset,
                    chunkEndOffset,
                    extendedChunkLength,
                    workerBitmap
                  )
                }
              }

              val completedSoFar: Int = completedChunksCount.incrementAndGet()
              val hasLocalMatches: Boolean = !workerBitmap.isEmpty
              val nowNanos: Long = System.nanoTime()

              val shouldNotify: Boolean = if (hasLocalMatches && currentQueryId.get() == queryId) {
                val isFirstMatch: Boolean = firstMatchNotified.compareAndSet(false, true)
                val isThrottled: Boolean = (nowNanos - lastProgressTimeNanos.get()) >= throttleIntervalNanos
                val isLastChunk: Boolean = completedSoFar == chunkCount

                if ((isFirstMatch || isThrottled) && !isLastChunk) {
                  stateLock.synchronized {
                    combinedBitmap.or(workerBitmap)
                    lastProgressTimeNanos.set(nowNanos)
                    true
                  }
                } else {
                  false
                }
              } else {
                false
              }

              if (shouldNotify && currentQueryId.get() == queryId) {
                val snapshotBitmap: Roaring64NavigableMap = stateLock.synchronized {
                  val copy = new Roaring64NavigableMap()
                  copy.or(combinedBitmap)
                  copy
                }
                val currentDurationNanos: Long = System.nanoTime() - startNanos
                progressConsumer(
                  SearchResult(
                    snapshotBitmap,
                    snapshotBitmap.getLongCardinality,
                    currentDurationNanos,
                    isComplete = false
                  )
                )
              }

              step = nextStepCursor.getAndIncrement()
            }

            if (!workerBitmap.isEmpty && currentQueryId.get() == queryId) {
              stateLock.synchronized {
                combinedBitmap.or(workerBitmap)
              }
            }
          } finally {
            workerBitmap.clear()
            workerState.cleanup()
          }

          java.lang.Boolean.TRUE
        })
        workerIdx += 1
      }

      try {
        val futures = executorService.invokeAll(tasks)
        activeFutures = futures
        tasks.clear()
        futures.asScala.foreach { future =>
          try {
            future.get()
          } catch {
            case _: InterruptedException =>
              abortCurrentSearch()
              Thread.currentThread().interrupt()
            case executionEx: java.util.concurrent.ExecutionException =>
              if (executionEx.getCause != null && executionEx.getCause.isInstanceOf[InterruptedException]) {
                abortCurrentSearch()
                Thread.currentThread().interrupt()
              }
            case _: java.util.concurrent.CancellationException =>
              // Task was cancelled during shutdown or query abort
          }
        }
      } catch {
        case _: InterruptedException =>
          abortCurrentSearch()
          tasks.clear()
          Thread.currentThread().interrupt()
      } finally {
        activeFutures = java.util.Collections.emptyList()
        if (fileSize >= workingSetTrimThresholdBytes) {
          storageReader.trimWorkingSet()
        }
      }
      combinedBitmap
    }
  }

  /**
   * Parallel hardware SIMD fused dual-literal search (AND / OR) using `VectorSubstringScanner.scanDualLiteral`.
   */
  private def searchDualLiteralParallel(
    textA: String,
    textB: String,
    isCaseSensitive: Boolean,
    isAnd: Boolean,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  ): Roaring64NavigableMap = {
    val needleBytesA: Array[Byte] = textA.getBytes(StandardCharsets.UTF_8)
    val needleBytesB: Array[Byte] = textB.getBytes(StandardCharsets.UTF_8)
    val overlapBytes: Long = math.max((needleBytesA.length - 1).max(0), (needleBytesB.length - 1).max(0)).toLong

    dispatchParallelDualSearch(overlapBytes, isAnd, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter) {
      (chunkSlice, chunkStartOffset, extendedChunkLength, resolverA, resolverB) =>
        if (isCaseSensitive) {
          VectorSubstringScanner.scanDualLiteral(
            chunkSlice,
            0L,
            extendedChunkLength,
            needleBytesA,
            needleBytesB,
            (relativeMatchOffset: Long) => {
              resolverA.recordMatch(chunkStartOffset + relativeMatchOffset)
            },
            (relativeMatchOffset: Long) => {
              resolverB.recordMatch(chunkStartOffset + relativeMatchOffset)
            }
          )
        } else {
          VectorSubstringScanner.scanDualLiteralCaseInsensitive(
            chunkSlice,
            0L,
            extendedChunkLength,
            needleBytesA,
            needleBytesB,
            (relativeMatchOffset: Long) => {
              resolverA.recordMatch(chunkStartOffset + relativeMatchOffset)
            },
            (relativeMatchOffset: Long) => {
              resolverB.recordMatch(chunkStartOffset + relativeMatchOffset)
            }
          )
        }
    }
  }

  /**
   * Snaps `nominalOffset` forward to the first byte of a line (the byte after the next `\n` at or
   * after `nominalOffset - 1`), or to `limit` if no newline remains, using SIMD `findFirstNewline`.
   */
  private def alignToLineStart(nominalOffset: Long, limit: Long): Long = {
    @tailrec
    def findLineStart(scanOffset: Long): Long = {
      if (scanOffset >= limit) {
        limit
      } else {
        val windowLength: Long = (limit - scanOffset).min(65536L)
        val relativeNewline: Long = VectorScanner.findFirstNewline(
          storageReader.readSlice(scanOffset, windowLength),
          0L,
          windowLength
        )
        if (relativeNewline >= 0L) scanOffset + relativeNewline + 1L else findLineStart(scanOffset + windowLength)
      }
    }

    if (nominalOffset <= 0L) 0L else if (nominalOffset >= limit) limit else findLineStart(nominalOffset - 1L)
  }

  /**
   * Parallel hardware SIMD literal search using `VectorSubstringScanner`.
   */
  private def searchLiteralParallel(
    needleText: String,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  ): Roaring64NavigableMap = {
    val needleBytes: Array[Byte] = needleText.getBytes(StandardCharsets.UTF_8)
    val overlapBytes: Long = (needleBytes.length - 1).max(0).toLong

    dispatchParallelSearch(overlapBytes, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter) {
      (chunkSlice, chunkStartOffset, extendedChunkLength, resolver) =>
        VectorSubstringScanner.scanLiteral(
          chunkSlice,
          0L,
          extendedChunkLength,
          needleBytes,
          (relativeMatchOffset: Long) => {
            resolver.recordMatch(chunkStartOffset + relativeMatchOffset)
          }
        )
    }
  }

  /**
   * Parallel hardware SIMD case-insensitive literal search using `VectorSubstringScanner`.
   */
  private def searchLiteralCaseInsensitiveParallel(
    needleText: String,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  ): Roaring64NavigableMap = {
    val needleBytes: Array[Byte] = needleText.getBytes(StandardCharsets.UTF_8)
    val overlapBytes: Long = (needleBytes.length - 1).max(0).toLong

    dispatchParallelSearch(overlapBytes, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter) {
      (chunkSlice, chunkStartOffset, extendedChunkLength, resolver) =>
        VectorSubstringScanner.scanLiteralCaseInsensitive(
          chunkSlice,
          0L,
          extendedChunkLength,
          needleBytes,
          (relativeMatchOffset: Long) => {
            resolver.recordMatch(chunkStartOffset + relativeMatchOffset)
          }
        )
    }
  }

  /**
   * Parallel RE2J linear regular expression search using `OffHeapCharSequence`.
   */
  private def searchRegexParallel(
    pattern: Pattern,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long,
    anchorByteOffset: Long = 0L,
    candidateFilter: Option[Roaring64NavigableMap] = None
  ): Roaring64NavigableMap = {
    dispatchParallelSearch(0L, progressConsumer, startNanos, queryId, anchorByteOffset, candidateFilter) {
      (chunkSlice, chunkStartOffset, extendedChunkLength, resolver) =>
        val offHeapCharSequence: OffHeapCharSequence = new OffHeapCharSequence(chunkSlice, 0L, extendedChunkLength.toInt)
        val matcher: Matcher = pattern.matcher(offHeapCharSequence)
        while (matcher.find()) {
          resolver.recordMatch(chunkStartOffset + matcher.start().toLong)
        }
    }
  }

  /**
   * Fast zero-allocation verification that all characters in `text` are within 7-bit ASCII range (0–127).
   */
  private def isAscii(text: String): Boolean = {
    var index: Int = 0
    val length: Int = text.length
    var allAscii: Boolean = true
    while (index < length && allAscii) {
      if (text.charAt(index) > 127) {
        allAscii = false
      }
      index += 1
    }
    allAscii
  }

  def close(): Unit = {
    abortCurrentSearch()
    executorService.shutdown()
    try {
      if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
        executorService.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        executorService.shutdownNow()
        Thread.currentThread().interrupt()
    }
  }
}
