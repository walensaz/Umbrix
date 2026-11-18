package umbrix.core.temporal

import it.unimi.dsi.fastutil.longs.{Long2LongOpenHashMap, LongArrayList}
import umbrix.core.types.LineIndex

/**
 * Immutable lookup model containing detected inter-record latency pauses and gaps.
 *
 * Backed by primitive off-heap/fastutil structures to guarantee zero JVM object
 * allocation when the viewport gutter queries whether a rendered row represents
 * an abnormal latency delay.
 *
 * @param gapLines ordered line indices where latency gaps originate
 * @param gapDurationsUs corresponding gap durations measured in microseconds
 * @param gapMap O(1) primitive map from lineIndex -> gapDurationUs
 */
class LatencyGapResult(
  val gapLines: LongArrayList,
  val gapDurationsUs: LongArrayList,
  private val gapMap: Long2LongOpenHashMap
) {

  /** Total count of identified latency gaps exceeding the threshold. */
  def gapCount: Int = gapLines.size()

  /** True if no latency gaps were detected. */
  def isEmpty: Boolean = gapLines.isEmpty

  /**
   * O(1) query checking if a given line index marks the beginning of an abnormal latency gap.
   */
  def hasGapAt(line: LineIndex): Boolean = {
    gapMap.containsKey(line.value)
  }

  /**
   * O(1) query returning the gap duration in microseconds for a given line, or 0L if no gap.
   */
  def gapDurationAt(line: LineIndex): Long = {
    gapMap.get(line.value)
  }

  /**
   * Retrieves the starting line index for gap at index `i`.
   */
  def gapLineAt(index: Int): Long = {
    gapLines.getLong(index)
  }

  /**
   * Retrieves the duration in microseconds for gap at index `i`.
   */
  def gapDurationAtIndex(index: Int): Long = {
    gapDurationsUs.getLong(index)
  }
}

object LatencyGapResult {

  /** Empty instance for progressive unindexed fallback or when no gaps exist. */
  val empty: LatencyGapResult = {
    val emptyMap = new Long2LongOpenHashMap(0)
    emptyMap.defaultReturnValue(0L)
    new LatencyGapResult(new LongArrayList(0), new LongArrayList(0), emptyMap)
  }
}
