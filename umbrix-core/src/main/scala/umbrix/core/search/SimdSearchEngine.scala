package umbrix.core.search

import com.google.re2j.Matcher
import com.google.re2j.Pattern
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import scala.annotation.tailrec
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.roaringbitmap.longlong.Roaring64NavigableMap
import scala.jdk.CollectionConverters.*
import umbrix.core.model.LogLevel
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
  val workerThreadsCount: Int = PlatformMemoryAdvisor.recommendedBackgroundWorkerCount
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

  /** Chunk size for parallel search partitioning (16MB). */
  private final val partitionChunkSizeBytes: Long = 16L * 1024L * 1024L

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
    QueryParser.parse(queryText).map(ast => executeProgressive(ast, progressConsumer))
  }

  /**
   * Evaluates a structured `QueryAst` and returns the matching physical lines synchronously.
   */
  def execute(ast: QueryAst): SearchResult = {
    executeProgressive(ast, _ => ())
  }

  /**
   * Evaluates a structured `QueryAst` with progressive notifications on first match and batch updates.
   */
  def executeProgressive(
    ast: QueryAst,
    progressConsumer: SearchResult => Unit
  ): SearchResult = {
    val queryId: Long = currentQueryId.incrementAndGet()
    val startNanos: Long = System.nanoTime()
    val matchingBitmap: Roaring64NavigableMap = evaluateAst(ast, progressConsumer, startNanos, queryId)
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
    queryId: Long
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
            searchLiteralParallel(needleText, progressConsumer, startNanos, queryId)
          } else if (isAscii(needleText)) {
            searchLiteralCaseInsensitiveParallel(needleText, progressConsumer, startNanos, queryId)
          } else {
            val caseInsensitiveRegex: Pattern = Pattern.compile("(?i)" + Pattern.quote(needleText))
            searchRegexParallel(caseInsensitiveRegex, progressConsumer, startNanos, queryId)
          }

        case QueryAst.Regex(pattern) =>
          searchRegexParallel(pattern, progressConsumer, startNanos, queryId)

        case QueryAst.LevelFilter(logLevel) =>
          val levelToken: String = logLevel.toString.toUpperCase
          val levelRegex: Pattern = Pattern.compile("\\b" + Pattern.quote(levelToken) + "\\b")
          searchRegexParallel(levelRegex, progressConsumer, startNanos, queryId)

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

        case QueryAst.And(leftAst, rightAst) =>
          val leftBitmap: Roaring64NavigableMap = evaluateAst(leftAst, _ => (), startNanos, queryId)
          if (leftBitmap.isEmpty || currentQueryId.get() != queryId) {
            leftBitmap
          } else {
            val rightBitmap: Roaring64NavigableMap = evaluateAst(rightAst, _ => (), startNanos, queryId)
            val result: Roaring64NavigableMap = new Roaring64NavigableMap()
            result.or(leftBitmap)
            result.and(rightBitmap)
            result
          }

        case QueryAst.Or(leftAst, rightAst) =>
          val leftBitmap: Roaring64NavigableMap = evaluateAst(leftAst, _ => (), startNanos, queryId)
          if (currentQueryId.get() != queryId) {
            leftBitmap
          } else {
            val rightBitmap: Roaring64NavigableMap = evaluateAst(rightAst, _ => (), startNanos, queryId)
            val result: Roaring64NavigableMap = new Roaring64NavigableMap()
            result.or(leftBitmap)
            result.or(rightBitmap)
            result
          }

        case QueryAst.Not(innerAst) =>
          val innerBitmap: Roaring64NavigableMap = evaluateAst(innerAst, _ => (), startNanos, queryId)
          val allLinesBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
          allLinesBitmap.addRange(0L, lineIndexer.totalLines.value)
          allLinesBitmap.andNot(innerBitmap)
          allLinesBitmap
      }
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
   * @param chunkScanner function scanning a single off-heap chunk slice and recording matches into local bitmap
   * @return combined Roaring64NavigableMap containing all matching line numbers
   */
  private def dispatchParallelSearch(
    overlapBytes: Long,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long
  )(
    chunkScanner: (MemorySegment, Long, Long, Long, Roaring64NavigableMap) => Unit
  ): Roaring64NavigableMap = {
    // Matches past the indexed region cannot be mapped to lines; clamp until indexing completes.
    val fileSize: Long = storageReader.sizeBytes.min(lineIndexer.indexedByteLimit)
    if (fileSize <= 0L || currentQueryId.get() != queryId) {
      new Roaring64NavigableMap()
    } else {
      val chunkCount: Int = ((fileSize + partitionChunkSizeBytes - 1L) / partitionChunkSizeBytes).toInt
      val tasks: java.util.ArrayList[Callable[java.lang.Boolean]] = new java.util.ArrayList[Callable[java.lang.Boolean]](chunkCount)

      val combinedBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
      val stateLock: AnyRef = new AnyRef()
      val completedChunksCount: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)
      val firstMatchNotified: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)
      val lastProgressTimeNanos: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(startNanos)
      val throttleIntervalNanos: Long = 50_000_000L // 50 ms

      var chunkIndex: Int = 0
      while (chunkIndex < chunkCount) {
        val currentChunkIndex: Int = chunkIndex
        val nominalStartOffset: Long = currentChunkIndex.toLong * partitionChunkSizeBytes
        val nominalEndOffset: Long = (nominalStartOffset + partitionChunkSizeBytes).min(fileSize)

        tasks.add(() => {
          val localResultBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
          try {
            if (currentQueryId.get() == queryId && !Thread.currentThread().isInterrupted) {
              // Neighbouring chunks compute the same aligned edge, so every line is owned by exactly one chunk.
              val chunkStartOffset: Long = alignToLineStart(nominalStartOffset, fileSize)
              val chunkEndOffset: Long = alignToLineStart(nominalEndOffset, fileSize)
              if (chunkEndOffset > chunkStartOffset) {
                val extendedChunkLength: Long = (chunkEndOffset - chunkStartOffset + overlapBytes).min(fileSize - chunkStartOffset)
                val chunkSlice: MemorySegment = storageReader.readSlice(chunkStartOffset, extendedChunkLength)
                chunkScanner(chunkSlice, chunkStartOffset, chunkEndOffset, extendedChunkLength, localResultBitmap)
              }
            }

            if (currentQueryId.get() == queryId && !Thread.currentThread().isInterrupted) {
              val hasLocalMatches: Boolean = !localResultBitmap.isEmpty
              val completedSoFar: Int = completedChunksCount.incrementAndGet()

              val shouldNotify: Boolean = stateLock.synchronized {
                if (hasLocalMatches && currentQueryId.get() == queryId) {
                  combinedBitmap.or(localResultBitmap)
                }
                val nowNanos: Long = System.nanoTime()
                val isFirstMatch: Boolean = hasLocalMatches && firstMatchNotified.compareAndSet(false, true)
                val isThrottled: Boolean = (nowNanos - lastProgressTimeNanos.get()) >= throttleIntervalNanos
                val isLastChunk: Boolean = (completedSoFar == chunkCount)

                if ((isFirstMatch || isThrottled) && !isLastChunk && currentQueryId.get() == queryId) {
                  lastProgressTimeNanos.set(nowNanos)
                  true
                } else {
                  false
                }
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
            }
          } finally {
            localResultBitmap.clear()
          }

          java.lang.Boolean.TRUE
        })
        chunkIndex += 1
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
        storageReader.trimWorkingSet()
      }
      combinedBitmap
    }
  }

  /**
   * Snaps `nominalOffset` forward to the first byte of a line (the byte after the next `\n` at or
   * after `nominalOffset - 1`), or to `limit` if no newline remains.
   */
  private def alignToLineStart(nominalOffset: Long, limit: Long): Long = {
    @tailrec
    def firstNewlineIndex(window: MemorySegment, windowLength: Long, index: Long): Long = {
      if (index >= windowLength) {
        -1L
      } else if (window.get(ValueLayout.JAVA_BYTE, index) == 0x0A.toByte) {
        index
      } else {
        firstNewlineIndex(window, windowLength, index + 1L)
      }
    }

    @tailrec
    def findLineStart(scanOffset: Long): Long = {
      if (scanOffset >= limit) {
        limit
      } else {
        val windowLength: Long = (limit - scanOffset).min(65536L)
        val relativeNewline: Long = firstNewlineIndex(storageReader.readSlice(scanOffset, windowLength), windowLength, 0L)
        if (relativeNewline >= 0L) scanOffset + relativeNewline + 1L else findLineStart(scanOffset + windowLength)
      }
    }

    if (nominalOffset <= 0L) 0L else if (nominalOffset >= limit) limit else findLineStart(nominalOffset - 1L)
  }

  /**
   * Records a match into the partition's local result bitmap if the offset falls before `chunkEndOffset`.
   */
  private inline def recordMatch(
    absoluteMatchOffset: Long,
    chunkEndOffset: Long,
    localResultBitmap: Roaring64NavigableMap
  ): Unit = {
    if (absoluteMatchOffset < chunkEndOffset) {
      val lineIndex: LineIndex = lineIndexer.lineIndexForByteOffset(ByteOffset(absoluteMatchOffset))
      if (lineIndex >= LineIndex.Zero && lineIndex < lineIndexer.totalLines) {
        localResultBitmap.addLong(lineIndex.value)
      }
    }
  }

  /**
   * Parallel hardware SIMD literal search using `VectorSubstringScanner`.
   */
  private def searchLiteralParallel(
    needleText: String,
    progressConsumer: SearchResult => Unit,
    startNanos: Long,
    queryId: Long
  ): Roaring64NavigableMap = {
    val needleBytes: Array[Byte] = needleText.getBytes(StandardCharsets.UTF_8)
    val overlapBytes: Long = (needleBytes.length - 1).max(0).toLong

    dispatchParallelSearch(overlapBytes, progressConsumer, startNanos, queryId) {
      (chunkSlice, chunkStartOffset, chunkEndOffset, extendedChunkLength, localResultBitmap) =>
        VectorSubstringScanner.scanLiteral(
          chunkSlice,
          0L,
          extendedChunkLength,
          needleBytes,
          (relativeMatchOffset: Long) => {
            recordMatch(chunkStartOffset + relativeMatchOffset, chunkEndOffset, localResultBitmap)
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
    queryId: Long
  ): Roaring64NavigableMap = {
    val needleBytes: Array[Byte] = needleText.getBytes(StandardCharsets.UTF_8)
    val overlapBytes: Long = (needleBytes.length - 1).max(0).toLong

    dispatchParallelSearch(overlapBytes, progressConsumer, startNanos, queryId) {
      (chunkSlice, chunkStartOffset, chunkEndOffset, extendedChunkLength, localResultBitmap) =>
        VectorSubstringScanner.scanLiteralCaseInsensitive(
          chunkSlice,
          0L,
          extendedChunkLength,
          needleBytes,
          (relativeMatchOffset: Long) => {
            recordMatch(chunkStartOffset + relativeMatchOffset, chunkEndOffset, localResultBitmap)
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
    queryId: Long
  ): Roaring64NavigableMap = {
    dispatchParallelSearch(0L, progressConsumer, startNanos, queryId) {
      (chunkSlice, chunkStartOffset, chunkEndOffset, extendedChunkLength, localResultBitmap) =>
        val offHeapCharSequence: OffHeapCharSequence = new OffHeapCharSequence(chunkSlice, 0L, extendedChunkLength.toInt)
        val matcher: Matcher = pattern.matcher(offHeapCharSequence)
        while (matcher.find()) {
          recordMatch(chunkStartOffset + matcher.start().toLong, chunkEndOffset, localResultBitmap)
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
