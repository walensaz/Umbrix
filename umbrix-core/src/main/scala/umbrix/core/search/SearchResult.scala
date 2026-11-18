package umbrix.core.search

import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.types.LineIndex

/**
 * Result of a search or query evaluation across the log file.
 *
 * Backed by an off-heap / compressed Roaring64NavigableMap of physical 0-based line indices.
 * Provides microsecond forward and backward hit navigation and O(1) set operations across 64-bit coordinates.
 *
 * @param matchingLines compressed Roaring64NavigableMap containing all matching 0-based physical line indices
 * @param totalMatches count of matching physical lines
 * @param searchDurationNanos duration of the search in nanoseconds
 */
case class SearchResult(
  matchingLines: Roaring64NavigableMap,
  totalMatches: Long,
  searchDurationNanos: Long,
  isComplete: Boolean = true
) {

  /**
   * True if no lines matched the query.
   */
  def isEmpty: Boolean = matchingLines.isEmpty

  /**
   * True if at least one line matched the query.
   */
  def nonEmpty: Boolean = !matchingLines.isEmpty

  /**
   * Constrains this search result to an inclusive range of physical lines [startLine, endLine].
   *
   * Executes via fast Roaring64NavigableMap bitwise range intersection without materializing line objects.
   */
  def constrainToRange(startLine: Long, endLine: Long): SearchResult = {
    val startIdx = math.max(0L, startLine)
    val endIdx = math.max(0L, endLine)
    if (matchingLines.isEmpty || startIdx > endIdx) {
      copy(matchingLines = new Roaring64NavigableMap(), totalMatches = 0L)
    } else {
      val boundedBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
      boundedBitmap.addRange(startIdx, endIdx + 1L)
      boundedBitmap.and(matchingLines)
      copy(
        matchingLines = boundedBitmap,
        totalMatches = boundedBitmap.getLongCardinality
      )
    }
  }

  /**
   * Finds the first matching physical line index at or after `startLine` (Forward search).
   *
   * Executes in sub-microsecond O(log M) time via Roaring64NavigableMap branchless rank-select trees.
   * Zero heap allocation / zero boxing: returns the primitive 0-based line index, or -1L if no match exists.
   *
   * @param startLine minimum line index to search from (inclusive)
   * @return primitive 0-based line index, or -1L if no match exists
   */
  def findNextLine(startLine: Long): Long = {
    val s = math.max(0L, startLine)
    if (matchingLines.isEmpty || s > matchingLines.last) {
      -1L
    } else if (matchingLines.contains(s)) {
      s
    } else {
      val rank: Long = matchingLines.rankLong(s)
      if (rank < matchingLines.getLongCardinality) {
        matchingLines.select(rank)
      } else {
        -1L
      }
    }
  }

  /**
   * Finds the last matching physical line index at or before `startLine` (Backward search).
   *
   * Executes in sub-microsecond O(log M) time via Roaring64NavigableMap branchless rank-select trees.
   * Zero heap allocation / zero boxing: returns the primitive 0-based line index, or -1L if no match exists.
   *
   * @param startLine maximum line index to search backward from (inclusive)
   * @return primitive 0-based line index, or -1L if no match exists
   */
  def findPreviousLine(startLine: Long): Long = {
    if (matchingLines.isEmpty || startLine < 0L || startLine < matchingLines.first) {
      -1L
    } else if (matchingLines.contains(startLine)) {
      startLine
    } else {
      val rank: Long = matchingLines.rankLong(startLine)
      if (rank > 0L) {
        matchingLines.select(rank - 1L)
      } else {
        -1L
      }
    }
  }

  /**
   * Finds the first matching line at or after `startLine` (Forward search).
   *
   * @param startLine minimum line index to search from (inclusive)
   * @return Some(lineIndex) of the next match, or None if no forward matches exist
   */
  def findNext(startLine: LineIndex): Option[LineIndex] = {
    val result: Long = findNextLine(startLine.value)
    if (result >= 0L) Some(LineIndex(result)) else None
  }

  /**
   * Finds the last matching line at or before `startLine` (Backward search).
   *
   * @param startLine maximum line index to search backward from (inclusive)
   * @return Some(lineIndex) of the previous match, or None if no backward matches exist
   */
  def findPrevious(startLine: LineIndex): Option[LineIndex] = {
    val result: Long = findPreviousLine(startLine.value)
    if (result >= 0L) Some(LineIndex(result)) else None
  }
}
