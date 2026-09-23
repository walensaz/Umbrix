package umbrix.simd

import munit.FunSuite
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.util.Random

class ParallelVectorScannerTest extends FunSuite {

  test("small segment falls back gracefully to sequential execution") {
    val scanner: ParallelVectorScanner = new ParallelVectorScanner(workerCount = 4, minimumParallelBytes = 1024L * 1024L)
    try {
      val smallText: String = "Line 1\nLine 2\nLine 3\n"
      val testBytes: Array[Byte] = smallText.getBytes("UTF-8")
      val segment: MemorySegment = MemorySegment.ofArray(testBytes)

      val count: Long = scanner.countNewlinesParallel(segment, 0L, testBytes.length.toLong)
      assertEquals(count, 3L)
    } finally {
      scanner.close()
    }
  }

  test("parallel scan accurately counts newlines across multi-megabyte buffer") {
    val scanner: ParallelVectorScanner = new ParallelVectorScanner(workerCount = 4, minimumParallelBytes = 64L * 1024L) // 64KB threshold for test
    try {
      val bufferSize: Int = 1024 * 1024 * 4 + 73 // 4MB + 73 unaligned bytes
      val testBytes: Array[Byte] = new Array[Byte](bufferSize)
      val random: Random = new Random(12345L)
      random.nextBytes(testBytes)

      // Sprinkle newlines at ~1% frequency
      var expectedNewlineCount: Long = 0L
      for (index <- 0 until bufferSize) {
        if (random.nextDouble() < 0.01) {
          testBytes(index) = 0x0A.toByte
          expectedNewlineCount += 1L
        } else if (testBytes(index) == 0x0A.toByte) {
          testBytes(index) = ' '.toByte // Clear random newlines to maintain exact count
        }
      }

      val segment: MemorySegment = MemorySegment.ofArray(testBytes)

      val parallelCount: Long = scanner.countNewlinesParallel(segment, 0L, bufferSize.toLong)
      val scalarCount: Long = ScalarScanner.countNewlines(segment, 0L, bufferSize.toLong)

      assertEquals(parallelCount, expectedNewlineCount)
      assertEquals(parallelCount, scalarCount)
    } finally {
      scanner.close()
    }
  }

  test("scanNewlinesParallelChunked executes contiguous non-overlapping partitions") {
    val scanner: ParallelVectorScanner = new ParallelVectorScanner(workerCount = 4, minimumParallelBytes = 32L * 1024L)
    try {
      val bufferSize: Int = 1024 * 256 + 17 // 256KB + 17 bytes
      val testBytes: Array[Byte] = new Array[Byte](bufferSize)
      val random: Random = new Random(777L)
      random.nextBytes(testBytes)

      for (index <- 0 until bufferSize) {
        if (random.nextDouble() < 0.05) {
          testBytes(index) = 0x0A.toByte
        }
      }

      val segment: MemorySegment = MemorySegment.ofArray(testBytes)
      val chunkStarts: ConcurrentHashMap[Int, Long] = new ConcurrentHashMap[Int, Long]()
      val chunkLengths: ConcurrentHashMap[Int, Long] = new ConcurrentHashMap[Int, Long]()
      val accumulatedChunkNewlines: AtomicLong = new AtomicLong(0L)

      val totalCount: Long = scanner.scanNewlinesParallelChunked(
        segment,
        0L,
        bufferSize.toLong,
        (chunkIndex: Int, chunkStart: Long, chunkLength: Long, chunkNewlines: Long) => {
          chunkStarts.put(chunkIndex, chunkStart)
          chunkLengths.put(chunkIndex, chunkLength)
          accumulatedChunkNewlines.addAndGet(chunkNewlines)
        }
      )

      val scalarCount: Long = ScalarScanner.countNewlines(segment, 0L, bufferSize.toLong)
      assertEquals(totalCount, scalarCount)
      assertEquals(accumulatedChunkNewlines.get(), scalarCount)

      // Verify partition continuity
      val partitionCount: Int = chunkStarts.size()
      var expectedNextStart: Long = 0L
      for (chunkIndex <- 0 until partitionCount) {
        val actualStart: Long = chunkStarts.get(chunkIndex)
        val actualLength: Long = chunkLengths.get(chunkIndex)
        assertEquals(actualStart, expectedNextStart)
        expectedNextStart += actualLength
      }

      assertEquals(expectedNextStart, bufferSize.toLong)
    } finally {
      scanner.close()
    }
  }

  test("defaultScanner is protected against close() and remains functional") {
    val defaultScanner: ParallelVectorScanner = ParallelVectorScanner.defaultScanner
    assertEquals(defaultScanner.isCloseable, false)

    val sampleBytes: Array[Byte] = "Alpha\nBeta\nGamma\nDelta\n".getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(sampleBytes)

    val initialCount: Long = defaultScanner.countNewlinesParallel(segment, 0L, sampleBytes.length.toLong)
    assertEquals(initialCount, 4L)

    // Attempting to close the default scanner must be a safe no-op
    defaultScanner.close()

    // Scanner must still be fully functional after close()
    val postCloseCount: Long = defaultScanner.countNewlinesParallel(segment, 0L, sampleBytes.length.toLong)
    assertEquals(postCloseCount, 4L)
  }

  test("asUncloseable delegates share thread pool and guard against premature close") {
    val dedicatedScanner: ParallelVectorScanner = new ParallelVectorScanner(workerCount = 2, minimumParallelBytes = 1024L)
    assertEquals(dedicatedScanner.isCloseable, true)

    val uncloseableDelegate: ParallelVectorScanner = dedicatedScanner.asUncloseable
    assertEquals(uncloseableDelegate.isCloseable, false)

    val sampleBytes: Array[Byte] = "Row 1\nRow 2\nRow 3\n".getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(sampleBytes)

    assertEquals(uncloseableDelegate.countNewlinesParallel(segment, 0L, sampleBytes.length.toLong), 3L)

    // Closing the uncloseable delegate does not terminate the underlying pool
    uncloseableDelegate.close()
    assertEquals(dedicatedScanner.countNewlinesParallel(segment, 0L, sampleBytes.length.toLong), 3L)
    assertEquals(uncloseableDelegate.countNewlinesParallel(segment, 0L, sampleBytes.length.toLong), 3L)

    // Closing dedicated scanner shuts down the underlying carrier pool
    dedicatedScanner.close()
  }
}
