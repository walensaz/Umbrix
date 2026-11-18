package umbrix.core.temporal

import it.unimi.dsi.fastutil.longs.{Long2LongOpenHashMap, LongArrayList}
import java.util.concurrent.{CompletableFuture, ForkJoinPool}
import java.util.function.DoubleConsumer
import umbrix.core.types.{EpochMicros, LineIndex}
import umbrix.index.TemporalCheckpointTable

/**
 * Asynchronous scanner identifying abnormal time gaps (Δt > threshold) between consecutive log timestamps.
 *
 * Highlights hidden unlogged thread blocking, network timeouts, or JVM stop-the-world
 * GC pauses without ingesting raw log text into the JVM Garbage Collector heap.
 */
object LatencyGapScanner {

  /** Default warning threshold: 1,000 milliseconds (1,000,000 microseconds). */
  final val DefaultThresholdMicros: Long = 1000L * 1000L

  /** Severe latency threshold: 5,000 milliseconds (5,000,000 microseconds). */
  final val SevereThresholdMicros: Long = 5000L * 1000L

  /**
   * Scans the temporal checkpoint table to discover inter-record time gaps exceeding `thresholdMicros`.
   *
   * @param table populated TemporalCheckpointTable
   * @param thresholdMicros minimum time difference in microseconds to trigger a gap detection
   * @param progressConsumer optional callback for indexing progress [0.0, 1.0]
   * @return LatencyGapResult containing fastutil indexed line positions and gap durations
   */
  def scan(
    table: TemporalCheckpointTable,
    thresholdMicros: Long = DefaultThresholdMicros,
    progressConsumer: DoubleConsumer = _ => ()
  ): LatencyGapResult = {
    val count: Int = table.checkpointCount
    if (count < 2) {
      progressConsumer.accept(1.0)
      LatencyGapResult.empty
    } else {
      val estimatedGaps: Int = math.min(1024, count)
      val gapLines: LongArrayList = new LongArrayList(estimatedGaps)
      val gapDurationsUs: LongArrayList = new LongArrayList(estimatedGaps)
      val gapMap: Long2LongOpenHashMap = new Long2LongOpenHashMap(estimatedGaps)
      gapMap.defaultReturnValue(0L)

      var currentIndex: Int = 0
      val limit: Int = count - 1
      val totalSteps: Double = math.max(1.0, limit.toDouble)

      while (currentIndex < limit && !Thread.currentThread().isInterrupted) {
        val currentTimestamp: EpochMicros = table.timestampAtCheckpoint(currentIndex)
        val nextTimestamp: EpochMicros = table.timestampAtCheckpoint(currentIndex + 1)
        val deltaMicros: Long = nextTimestamp.value - currentTimestamp.value

        if (deltaMicros >= thresholdMicros) {
          val lineIndex: LineIndex = table.lineAtCheckpoint(currentIndex)
          gapLines.add(lineIndex.value)
          gapDurationsUs.add(deltaMicros)
          gapMap.put(lineIndex.value, deltaMicros)
        }

        currentIndex += 1
        if ((currentIndex & 127) == 0) {
          progressConsumer.accept(currentIndex.toDouble / totalSteps)
        }
      }

      progressConsumer.accept(1.0)
      new LatencyGapResult(gapLines, gapDurationsUs, gapMap)
    }
  }

  /**
   * Asynchronously schedules latency gap detection once the underlying TemporalCheckpointTable completes.
   */
  def buildAsync(
    tableFuture: CompletableFuture[TemporalCheckpointTable],
    thresholdMicros: Long = DefaultThresholdMicros,
    progressConsumer: DoubleConsumer = _ => (),
    executor: java.util.concurrent.Executor = ForkJoinPool.commonPool()
  ): CompletableFuture[LatencyGapResult] = {
    tableFuture.thenApplyAsync(
      (table: TemporalCheckpointTable) => scan(table, thresholdMicros, progressConsumer),
      executor
    )
  }
}
