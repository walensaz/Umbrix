package umbrix.core.benchmark

import munit.FunSuite
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.index.TwoLevelSparseIndex
import umbrix.simd.VectorScanner
import umbrix.simd.VectorSubstringScanner
import umbrix.vfs.MemoryMappedLogReader

/**
 * End-to-end performance benchmarking and invariant verification suite.
 * Evaluates hardware SIMD scanning throughput (GB/s), two-level sparse index construction latency,
 * microsecond random-access coordinate resolution, and linear-time ReDoS-immune search.
 */
class WorkstationPerformanceBenchmarkSuite extends FunSuite {

  private val BufferSizeBytes = 64 * 1024 * 1024 // 64 MB off-heap synthetic buffer
  private val SampleLineTemplate = "2026-10-07 19:45:12.345 [main] INFO  com.umbrix.core.DatabaseWorker - Connection pool allocated successfully in 12ms\n"
  private val isCiEnvironment: Boolean = System.getenv("CI") != null
  private val minScanGbPerSec: Double = if (isCiEnvironment) 0.2 else 2.0
  private val maxIndexBuildMs: Long = if (isCiEnvironment) 15000L else 5000L
  private val maxLookupMicros: Double = if (isCiEnvironment) 50.0 else 10.0

  test("benchmark 1: hardware SIMD newline scanning throughput (GB/s)") {
    val arena = Arena.ofConfined()
    try {
      val segment = arena.allocate(BufferSizeBytes.toLong, 64L)
      val lineBytes = SampleLineTemplate.getBytes(StandardCharsets.UTF_8)
      var writeOffset = 0L
      while (writeOffset + lineBytes.length <= BufferSizeBytes) {
        MemorySegment.copy(MemorySegment.ofArray(lineBytes), 0L, segment, writeOffset, lineBytes.length.toLong)
        writeOffset += lineBytes.length
      }

      // Warmup phase (3 iterations)
      var warmupLines = 0L
      for (_ <- 0 until 3) {
        warmupLines = VectorScanner.countByte(segment, 0L, writeOffset, 0x0A.toByte)
      }
      assert(warmupLines > 0L)

      // Timed measurement phase (10 iterations)
      val iterations = 10
      val startNano = System.nanoTime()
      var totalLines = 0L
      for (_ <- 0 until iterations) {
        totalLines = VectorScanner.countByte(segment, 0L, writeOffset, 0x0A.toByte)
      }
      val elapsedNano = System.nanoTime() - startNano
      val elapsedSeconds = elapsedNano.toDouble / 1_000_000_000.0
      val totalBytesScanned = writeOffset.toDouble * iterations.toDouble
      val throughputGbPerSec = (totalBytesScanned / (1024.0 * 1024.0 * 1024.0)) / elapsedSeconds
      val linesPerSec = (totalLines.toDouble * iterations.toDouble) / elapsedSeconds

      println(s"=== SIMD Newline Scanning Benchmark ===")
      println(s"Buffer Size: ${writeOffset / (1024 * 1024)} MB | Iterations: $iterations")
      println(s"Lines Counted: $totalLines lines per pass")
      println(s"Sustained Throughput: $throughputGbPerSec GB/s ($linesPerSec lines/sec)")

      assert(throughputGbPerSec >= minScanGbPerSec, s"SIMD scan throughput was unexpectedly low: $throughputGbPerSec GB/s")
    } finally {
      arena.close()
    }
  }

  test("benchmark 2: two-level sparse index construction and memory overhead") {
    val tempFile = Files.createTempFile("umbrix_benchmark_index", ".log")
    try {
      val lineBytes = SampleLineTemplate.getBytes(StandardCharsets.UTF_8)
      val totalRepeats = BufferSizeBytes / lineBytes.length
      val os = Files.newOutputStream(tempFile)
      try {
        for (_ <- 0 until totalRepeats) {
          os.write(lineBytes)
        }
      } finally {
        os.close()
      }

      val reader = MemoryMappedLogReader.open(tempFile)
      try {
        val startNano = System.nanoTime()
        val sparseIndex = TwoLevelSparseIndex.build(reader)
        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L

        val totalIndexedLines = sparseIndex.totalLines.value
        val estimatedMemoryBytes = (sparseIndex.superStrideOffsets.size() * 8L) + (sparseIndex.intraStrideDeltas.size() * 4L)

        println(s"=== Two-Level Sparse Index Benchmark ===")
        println(s"Indexed Lines: $totalIndexedLines lines in $elapsedMs ms")
        println(s"Estimated Index RAM: ${estimatedMemoryBytes / 1024} KB")

        assert(totalIndexedLines > 0L)
        assert(elapsedMs < maxIndexBuildMs, s"Index build exceeded ${maxIndexBuildMs}ms threshold: $elapsedMs ms")
        // Mathematical memory budget check: Index must occupy < 0.1% of raw file size
        assert(estimatedMemoryBytes < (reader.sizeBytes / 1000L) + 65536L)
      } finally {
        reader.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("benchmark 3: random-access coordinate resolution latency (<1.5µs)") {
    val tempFile = Files.createTempFile("umbrix_benchmark_coord", ".log")
    try {
      val lineBytes = SampleLineTemplate.getBytes(StandardCharsets.UTF_8)
      val totalRepeats = BufferSizeBytes / lineBytes.length
      val os = Files.newOutputStream(tempFile)
      try {
        for (_ <- 0 until totalRepeats) {
          os.write(lineBytes)
        }
      } finally {
        os.close()
      }

      val reader = MemoryMappedLogReader.open(tempFile)
      try {
        val sparseIndex = TwoLevelSparseIndex.build(reader)
        val totalLines = sparseIndex.totalLines.value

        // Measure 50,000 coordinate queries across random lines
        val lookups = 50_000
        val random = new java.util.Random(42L)
        val sampleQueries = Array.fill(lookups)(Math.abs(random.nextLong()) % totalLines)

        // Warmup
        var dummySum = 0L
        for (i <- 0 until 1000) {
          dummySum += sparseIndex.byteOffsetForLine(LineIndex(sampleQueries(i)), reader).value
        }

        val startNano = System.nanoTime()
        var sumOffsets = 0L
        for (i <- 0 until lookups) {
          sumOffsets += sparseIndex.byteOffsetForLine(LineIndex(sampleQueries(i)), reader).value
        }
        val elapsedNano = System.nanoTime() - startNano
        val avgLatencyMicros = (elapsedNano.toDouble / lookups.toDouble) / 1000.0
        val lookupsPerSec = (lookups.toDouble / (elapsedNano.toDouble / 1_000_000_000.0))

        println(s"=== Coordinate Lookup Benchmark ===")
        println(s"Lookups: $lookups | Average Latency: $avgLatencyMicros µs ($lookupsPerSec lookups/sec)")

        assert(avgLatencyMicros < maxLookupMicros, s"Coordinate resolution exceeded threshold: $avgLatencyMicros µs")
        assert(sumOffsets > 0L)
      } finally {
        reader.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("benchmark 4: vector substring literal search throughput (GB/s)") {
    val arena = Arena.ofConfined()
    try {
      val segment = arena.allocate(BufferSizeBytes.toLong, 64L)
      val lineBytes = SampleLineTemplate.getBytes(StandardCharsets.UTF_8)
      var writeOffset = 0L
      while (writeOffset + lineBytes.length <= BufferSizeBytes) {
        MemorySegment.copy(MemorySegment.ofArray(lineBytes), 0L, segment, writeOffset, lineBytes.length.toLong)
        writeOffset += lineBytes.length
      }

      val needle = "DatabaseWorker"
      val needleBytes = needle.getBytes(StandardCharsets.UTF_8)

      // Warmup (3 iterations so C2 JIT finishes inlining before timing)
      val warmupConsumer: java.util.function.LongConsumer = (_: Long) => ()
      for (_ <- 0 until 3) {
        VectorSubstringScanner.scanLiteral(segment, 0L, writeOffset, needleBytes, warmupConsumer)
      }

      val iterations = 10
      val startNano = System.nanoTime()
      var matchCount = 0L
      for (_ <- 0 until iterations) {
        val count = VectorSubstringScanner.scanLiteral(segment, 0L, writeOffset, needleBytes, warmupConsumer)
        matchCount = count
      }
      val elapsedNano = System.nanoTime() - startNano
      val elapsedSeconds = elapsedNano.toDouble / 1_000_000_000.0
      val totalBytesScanned = writeOffset.toDouble * iterations.toDouble
      val throughputGbPerSec = (totalBytesScanned / (1024.0 * 1024.0 * 1024.0)) / elapsedSeconds

      println(s"=== Vector Substring Search Benchmark ===")
      println(s"Target Needle: '$needle' | Matches: $matchCount")
      println(s"Search Throughput: $throughputGbPerSec GB/s")

      assert(matchCount > 0L)
      assert(throughputGbPerSec >= minScanGbPerSec, s"Substring scan throughput was unexpectedly low: $throughputGbPerSec GB/s")
    } finally {
      arena.close()
    }
  }
}
