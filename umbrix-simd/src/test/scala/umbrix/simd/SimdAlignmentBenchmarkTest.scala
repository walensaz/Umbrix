package umbrix.simd

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class SimdAlignmentBenchmarkTest extends FunSuite {

  test("benchmark: SIMD alignment performance on real log data") {
    val thunderbirdPath = Paths.get("C:\\Users\\zwale\\Downloads\\Thunderbird\\Thunderbird.log")
    if (Files.exists(thunderbirdPath)) {
      val arena = Arena.ofShared()
      try {
        val channel = FileChannel.open(thunderbirdPath, StandardOpenOption.READ)
        try {
          // Map a 500MB slice for benchmarking
          val benchmarkSize = math.min(500L * 1024L * 1024L, channel.size())
          val segment = channel.map(FileChannel.MapMode.READ_ONLY, 0L, benchmarkSize, arena)

          println(s"=== SIMD Alignment Benchmark on Thunderbird.log (${benchmarkSize / (1024 * 1024)} MB) ===")
          println(s"Host SIMD vector width: ${VectorScanner.vectorByteWidth} bytes")

          // Warmup
          VectorScanner.countNewlines(segment, 0L, benchmarkSize)

          // 1. Aligned scan (offset = 0)
          val iterations = 5
          var alignedNanos = 0L
          var alignedLines = 0L
          for (_ <- 0 until iterations) {
            val t0 = System.nanoTime()
            alignedLines = VectorScanner.countNewlines(segment, 0L, benchmarkSize)
            alignedNanos += (System.nanoTime() - t0)
          }
          val avgAlignedSec = (alignedNanos.toDouble / iterations) / 1e9
          val alignedThroughputGbps = (benchmarkSize.toDouble / (1024 * 1024 * 1024)) / avgAlignedSec

          // 2. Deliberately misaligned scans (offsets +1, +7, +13, +31)
          val testOffsets = List(1L, 7L, 13L, 31L)
          for (offset <- testOffsets) {
            val scanLength = benchmarkSize - offset
            var unalignedNanos = 0L
            var unalignedLines = 0L
            for (_ <- 0 until iterations) {
              val t0 = System.nanoTime()
              unalignedLines = VectorScanner.countNewlines(segment, offset, scanLength)
              unalignedNanos += (System.nanoTime() - t0)
            }
            val avgUnalignedSec = (unalignedNanos.toDouble / iterations) / 1e9
            val unalignedThroughputGbps = (scanLength.toDouble / (1024 * 1024 * 1024)) / avgUnalignedSec

            println(f"Offset +$offset%2d: Throughput = $unalignedThroughputGbps%6.2f GB/s (Lines found: $unalignedLines)")
          }

          println(f"Aligned (offset 0): Throughput = $alignedThroughputGbps%6.2f GB/s (Lines found: $alignedLines)")
          assert(alignedThroughputGbps > 1.0, "SIMD scanning must exceed 1.0 GB/s")
        } finally {
          channel.close()
        }
      } finally {
        arena.close()
      }
    } else {
      println("Thunderbird.log not found, skipping large-file benchmark")
    }
  }
}
