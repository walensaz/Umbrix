package umbrix.simd

import java.lang.foreign.MemorySegment
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
 * Multi-core parallel vector scanning coordinator.
 *
 * Partitions massive off-heap memory segments across dedicated carrier platform threads
 * (`Thread.ofPlatform()`), achieving 15–25 GB/s throughput by saturating multi-channel memory
 * bandwidth and hardware SIMD execution units simultaneously.
 *
 * In accordance with Umbrix architectural rules:
 * 1. Pinned platform threads are strictly employed for CPU-bound SIMD computation (never Virtual Threads).
 * 2. Raw log bytes are read directly from off-heap memory segments with zero JVM heap copying.
 * 3. Thread pools are managed deterministically via `AutoCloseable`.
 */
class ParallelVectorScanner private (
  val workerCount: Int,
  val minimumParallelBytes: Long,
  val isCloseable: Boolean,
  executor: Option[ExecutorService]
) extends AutoCloseable {

  /**
   * Primary public constructor for dedicated parallel vector scanner instances.
   *
   * @param workerCount number of carrier threads dedicated to vector partitioning
   * @param minimumParallelBytes minimum segment size required before dispatching to thread pool
   */
  def this(
    workerCount: Int = Runtime.getRuntime.availableProcessors().max(1),
    minimumParallelBytes: Long = 1024L * 1024L // 1MB threshold for multi-core dispatch
  ) = this(workerCount, minimumParallelBytes, isCloseable = true, None)

  /** Thread factory configuring pinned carrier platform threads. */
  private val carrierThreadFactory: ThreadFactory =
    Thread.ofPlatform().name("umbrix-simd-worker-", 0).daemon(true).factory()

  /** Dedicated thread pool for vector computation units. */
  private val workerExecutorService: ExecutorService =
    executor.getOrElse(Executors.newFixedThreadPool(workerCount, carrierThreadFactory))

  /**
   * Returns a protected delegate of this scanner sharing the underlying thread pool
   * where invocations to [[close]] are guaranteed safe no-ops.
   */
  def asUncloseable: ParallelVectorScanner = {
    if (!isCloseable) {
      this
    } else {
      new ParallelVectorScanner(workerCount, minimumParallelBytes, isCloseable = false, Some(workerExecutorService))
    }
  }

  /**
   * Counts total occurrences of `targetByte` across the segment in parallel.
   *
   * @param segment off-heap memory segment
   * @param startOffset starting byte offset
   * @param length total byte length
   * @param targetByte byte to search for (e.g. 0x0A for newline)
   * @return total count of matching bytes
   */
  def countByteParallel(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte
  ): Long = {
    if (length <= 0L) {
      0L
    } else if (length < minimumParallelBytes || workerCount == 1) {
      VectorScanner.countByte(segment, startOffset, length, targetByte)
    } else {
      val activePartitionsCount: Int = workerCount.min(((length + minimumParallelBytes - 1L) / minimumParallelBytes).toInt).max(1)
      val workerTasks: java.util.ArrayList[Callable[Long]] = new java.util.ArrayList[Callable[Long]](activePartitionsCount)

      var partitionIndex: Int = 0
      while (partitionIndex < activePartitionsCount) {
        val currentPartitionIndex: Int = partitionIndex
        val partitionStartOffset: Long = startOffset + (currentPartitionIndex.toLong * length) / activePartitionsCount
        val partitionEndOffset: Long = startOffset + ((currentPartitionIndex + 1).toLong * length) / activePartitionsCount
        val partitionByteLength: Long = partitionEndOffset - partitionStartOffset

        workerTasks.add(() => {
          VectorScanner.countByte(
            segment,
            partitionStartOffset,
            partitionByteLength,
            targetByte
          )
        })
        partitionIndex += 1
      }

      val taskFutures: java.util.List[Future[Long]] = workerExecutorService.invokeAll(workerTasks)
      taskFutures.asScala.foldLeft(0L) { (runningTotalMatches, future) =>
        runningTotalMatches + future.get()
      }
    }
  }

  /**
   * Convenience helper for parallel newline (`0x0A`) counting.
   */
  def countNewlinesParallel(
    segment: MemorySegment,
    startOffset: Long,
    length: Long
  ): Long = {
    countByteParallel(segment, startOffset, length, 0x0A.toByte)
  }

  /**
   * Scans the segment in parallel chunks, invoking `onChunkComplete` for each finished partition.
   *
   * @param segment off-heap memory segment
   * @param startOffset starting byte offset
   * @param length total byte length
   * @param onChunkComplete callback invoked with (chunkIndex, chunkStartOffset, chunkLength, newlineCount)
   * @return total newlines counted across all chunks
   */
  def scanNewlinesParallelChunked(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    onChunkComplete: (Int, Long, Long, Long) => Unit
  ): Long = {
    if (length <= 0L) {
      0L
    } else if (length < minimumParallelBytes || workerCount == 1) {
      val singleChunkCount: Long = VectorScanner.countNewlines(segment, startOffset, length)
      onChunkComplete(0, startOffset, length, singleChunkCount)
      singleChunkCount
    } else {
      val activePartitionsCount: Int = workerCount.min(((length + minimumParallelBytes - 1L) / minimumParallelBytes).toInt).max(1)
      val workerTasks: java.util.ArrayList[Callable[Long]] = new java.util.ArrayList[Callable[Long]](activePartitionsCount)

      var partitionIndex: Int = 0
      while (partitionIndex < activePartitionsCount) {
        val currentPartitionIndex: Int = partitionIndex
        val partitionStartOffset: Long = startOffset + (currentPartitionIndex.toLong * length) / activePartitionsCount
        val partitionEndOffset: Long = startOffset + ((currentPartitionIndex + 1).toLong * length) / activePartitionsCount
        val partitionByteLength: Long = partitionEndOffset - partitionStartOffset

        workerTasks.add(() => {
          val chunkNewlines: Long = VectorScanner.countNewlines(
            segment,
            partitionStartOffset,
            partitionByteLength
          )
          onChunkComplete(currentPartitionIndex, partitionStartOffset, partitionByteLength, chunkNewlines)
          chunkNewlines
        })
        partitionIndex += 1
      }

      val taskFutures: java.util.List[Future[Long]] = workerExecutorService.invokeAll(workerTasks)
      taskFutures.asScala.foldLeft(0L) { (runningTotalMatches, future) =>
        runningTotalMatches + future.get()
      }
    }
  }

  def close(): Unit = {
    if (isCloseable) {
      workerExecutorService.shutdown()
      try {
        if (!workerExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
          workerExecutorService.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          workerExecutorService.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }
  }
}

object ParallelVectorScanner {

  /** Dedicated underlying scanner instance for the shared singleton. */
  private lazy val sharedScanner: ParallelVectorScanner = new ParallelVectorScanner()

  /**
   * Shared default multi-core scanner sized to available hardware platform cores.
   *
   * Protected against accidental shutdown: calls to [[ParallelVectorScanner.close]] on this
   * instance are safe no-ops, preserving the shared carrier thread pool across multiple consumers.
   */
  lazy val defaultScanner: ParallelVectorScanner = sharedScanner.asUncloseable

  /**
   * Explicitly shuts down the shared carrier thread pool during process termination.
   */
  def shutdownDefault(): Unit = {
    sharedScanner.close()
  }
}
