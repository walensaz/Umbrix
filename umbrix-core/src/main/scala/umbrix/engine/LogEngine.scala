package umbrix.engine

import java.lang.foreign.MemorySegment
import java.nio.file.Path
import umbrix.core.parser.QueryParseError
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.core.types.{EpochMicros, LineIndex}

/**
 * Clean, high-level, zero-UI public library facade for Umbrix.
 *
 * Provides standalone JVM applications and microservices with direct access to:
 * - Sub-microsecond random access line coordinates and memory segments
 * - SIMD literal and compound Boolean searches at 15–25 GB/s
 * - Sampled wall-clock temporal seeking
 * - Zero-copy sub-file slicing and incident extraction
 * - Zero GC heap memory ingestion
 */
trait LogEngine extends AutoCloseable {

  /** Total physical lines resolved in the document. */
  def totalLines: Long

  /** Total raw file size in bytes. */
  def totalBytes: Long

  /** True if all indexing (Tier 1 physical lines + Tier 2 semantic metadata) has completed. */
  def isIndexingComplete: Boolean

  /**
   * Retrieves line text clamped to `maxLineLength` (preventing heap pollution from multi-MB lines).
   *
   * @param line 0-based physical line index
   * @return decoded string
   */
  def getLineText(line: Long): String

  /**
   * Retrieves an off-heap direct memory segment slice for the given line.
   *
   * @param line 0-based physical line index
   * @return off-heap MemorySegment representing the line's raw bytes
   */
  def getLineSlice(line: Long): MemorySegment

  /**
   * Executes a synchronous SIMD search query across the document.
   *
   * @param query raw search expression
   * @return `Right(SearchResult)` with matching line bitmap or `Left(QueryParseError)` on syntax failure
   */
  def search(query: String): Either[QueryParseError, SearchResult]

  /**
   * Executes a SIMD search query with progressive partial result notifications.
   *
   * @param query raw search expression
   * @param onProgress callback invoked as partial match snapshots are discovered
   * @return `Right(SearchResult)` with final complete result or `Left(QueryParseError)` on syntax failure
   */
  def searchProgressive(query: String, onProgress: SearchResult => Unit): Either[QueryParseError, SearchResult]

  /**
   * Seeks the nearest physical line index for a wall-clock timestamp in epoch microseconds in O(log N) time.
   *
   * @param isoTimestampOrEpochMicros wall-clock time in epoch microseconds
   * @return closest line index
   */
  def seekTimestamp(isoTimestampOrEpochMicros: Long): Long

  /**
   * Exports a contiguous line range [startLine, endLine] to a standalone sub-file via direct DMA transfer.
   *
   * @param startLine 0-based starting line index (inclusive)
   * @param endLine 0-based ending line index (inclusive)
   * @param destination destination filesystem path
   * @return total bytes written
   */
  def exportSlice(startLine: Long, endLine: Long, destination: Path): Long
}

/**
 * Factory and composition root for `LogEngine`.
 */
object LogEngine {

  /**
   * Opens an industrial-grade log engine session for the specified path.
   *
   * @param path target log file path
   * @param options configuration options (defaults to `EngineOptions.Default`)
   * @return active LogEngine instance
   */
  def open(path: Path, options: EngineOptions = EngineOptions.Default): LogEngine = {
    val session: LogSession = LogSession.open(path, options.isLiveStream, options.profile.orNull)
    new LogEngineImpl(session, options)
  }

  private final class LogEngineImpl(
    private val session: LogSession,
    private val options: EngineOptions
  ) extends LogEngine {

    def totalLines: Long = session.totalLines.value

    def totalBytes: Long = session.fileSizeBytes

    def isIndexingComplete: Boolean = session.isIndexingComplete

    def getLineText(line: Long): String = {
      session.getLineText(LineIndex(line), options.maxLineLength)
    }

    def getLineSlice(line: Long): MemorySegment = {
      session.indexer.getLineSlice(LineIndex(line))
    }

    def search(query: String): Either[QueryParseError, SearchResult] = {
      session.search(query)
    }

    def searchProgressive(query: String, onProgress: SearchResult => Unit): Either[QueryParseError, SearchResult] = {
      session.searchProgressive(query, (res: SearchResult) => onProgress(res))
    }

    def seekTimestamp(isoTimestampOrEpochMicros: Long): Long = {
      session.seekNearestLine(EpochMicros(isoTimestampOrEpochMicros)).value
    }

    def exportSlice(startLine: Long, endLine: Long, destination: Path): Long = {
      session.slicer.exportContiguousRange(LineIndex(startLine), LineIndex(endLine), destination).bytesWritten
    }

    def close(): Unit = {
      session.close()
    }
  }
}
