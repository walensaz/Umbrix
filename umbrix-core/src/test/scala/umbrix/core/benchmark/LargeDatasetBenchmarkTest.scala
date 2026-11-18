package umbrix.core.benchmark

import munit.FunSuite
import java.nio.file.{Files, Path, Paths}
import java.lang.management.ManagementFactory
import java.util.Random
import umbrix.core.classifier.EventBoundaryClassifier
import umbrix.core.session.LogSession
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.index.{PersistentIndexCache, TwoLevelSparseIndex}
import umbrix.vfs.LogStorageReader

/**
 * On-demand benchmark suite evaluating Umbrix on real-world multi-gigabyte log datasets.
 *
 * Benchmarks:
 * 1. hadoop-hdfs-namenode-mesos-01.log (~5.42 GB)
 * 2. Thunderbird.log (~31.79 GB / 211M+ physical lines)
 *
 * Measures:
 * - Cold Full Indexing (100% Ready: Tier 1 + Tier 2 via FusedStreamingIndexer)
 * - Warm Cached Open (100% Ready: Tier 1 .idx + Tier 2 .t2 hit)
 * - Mathematical memory footprint of the sparse index in RAM
 * - Microsecond random-access coordinate resolution latency across 100,000 samples
 * - Single-literal high-cardinality SIMD search throughput (GB/s)
 * - Fused dual-literal compound SIMD search throughput (AND / OR)
 * - Process heap memory and RSS footprint
 */
class LargeDatasetBenchmarkTest extends FunSuite {

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(30, scala.concurrent.duration.MINUTES)

  private val BenchmarkDir: Path = Paths.get(sys.env.getOrElse("UMBRIX_BENCHMARK_LOG_DIR", System.getProperty("user.home") + "/Downloads/Thunderbird"))
  private val HdfsLogPath: Path = BenchmarkDir.resolve("hadoop-hdfs-namenode-mesos-01.log")
  private val ThunderbirdLogPath: Path = BenchmarkDir.resolve("Thunderbird.log")

  private def formatBytes(bytes: Long): String = {
    if (bytes >= 1024L * 1024L * 1024L) {
      f"${bytes.toDouble / (1024L * 1024L * 1024L)}%.2f GB ($bytes%,d bytes)"
    } else if (bytes >= 1024L * 1024L) {
      f"${bytes.toDouble / (1024L * 1024L)}%.2f MB ($bytes%,d bytes)"
    } else {
      f"${bytes / 1024L} KB ($bytes%,d bytes)"
    }
  }

  private def runDatasetBenchmark(
    label: String,
    path: Path,
    singleQuery: String,
    compoundAndQuery: String,
    compoundOrQuery: String
  ): Unit = {
    if (!Files.exists(path)) {
      println(s"Skipping benchmark for $label: file not found at $path")
    } else {
      val fileSizeBytes: Long = Files.size(path)
      val memoryBean = ManagementFactory.getMemoryMXBean

      println("\n" + "=" * 80)
      println(s"  UMBRIX PERFORMANCE BENCHMARK: $label")
      println(s"  File Path: $path")
      println(s"  Raw File Size: ${formatBytes(fileSizeBytes)}")
      println("=" * 80)

      // Ensure cache files are cleared first for TRUE cold run
      val dummyReader = LogStorageReader.open(path, isLive = false)
      val fingerprint = PersistentIndexCache.computeFingerprint(path, dummyReader)
      dummyReader.close()
      val idxFile = PersistentIndexCache.getCacheFilePath(fingerprint)
      val t2File = PersistentIndexCache.getTier2CacheFilePath(fingerprint)
      Files.deleteIfExists(idxFile)
      Files.deleteIfExists(t2File)

      // [1] COLD FULL INDEXING (Tier 1 + Tier 2 via FusedStreamingIndexer)
      val coldStartNano: Long = System.nanoTime()
      val coldSession: LogSession = LogSession.open(path, isLiveStream = false)
      val coldOpenElapsedMs: Long = (System.nanoTime() - coldStartNano) / 1_000_000L

      try {
        val firstLine: String = coldSession.getLineText(LineIndex(0L))
        println(f"  [1] Cold Time-to-First-Render (Tier 1 Chunk 0): $coldOpenElapsedMs%4d ms")
        println(s"      First line preview: [${firstLine.take(80)}...]")

        val sparseIndex: TwoLevelSparseIndex = coldSession.physicalIndexFuture.join()
        val temporalTable = coldSession.temporalIndexFuture.join()
        val classifier = coldSession.eventClassifierFuture.join()
        val minimap = coldSession.minimapFuture.join()

        val coldTotalElapsedNano: Long = System.nanoTime() - coldStartNano
        val coldTotalElapsedMs: Long = coldTotalElapsedNano / 1_000_000L
        val coldSec: Double = coldTotalElapsedNano.toDouble / 1_000_000_000.0
        val coldThroughputGbPerSec: Double = (fileSizeBytes.toDouble / (1024.0 * 1024.0 * 1024.0)) / coldSec
        val totalLines: Long = sparseIndex.totalLines.value

        println(f"  [2] Cold Full Ingestion (100%% Ready: Tier 1 + Tier 2): $coldTotalElapsedMs%,d ms (${coldSec}%.2f s)")
        println(f"      Total Physical Lines:                  $totalLines%,d lines")
        println(f"      Cold Single-Pass Ingestion Throughput: $coldThroughputGbPerSec%.2f GB/s")

        // Wait a moment for background cache persistence to complete
        var cacheWait = 0
        while ((!Files.exists(idxFile) || !Files.exists(t2File)) && cacheWait < 50) {
          Thread.sleep(100)
          cacheWait += 1
        }
      } finally {
        coldSession.close()
      }

      // [2] WARM CACHED OPEN (Tier 1 .idx + Tier 2 .t2 hit)
      val warmStartNano: Long = System.nanoTime()
      val warmSession: LogSession = LogSession.open(path, isLiveStream = false)
      val warmSparseIndex = warmSession.physicalIndexFuture.join()
      val warmTemporal = warmSession.temporalIndexFuture.join()
      val warmClassifier = warmSession.eventClassifierFuture.join()
      val warmMinimap = warmSession.minimapFuture.join()
      val warmTotalElapsedNano: Long = System.nanoTime() - warmStartNano
      val warmTotalElapsedMs: Long = warmTotalElapsedNano / 1_000_000L

      try {
        println(f"  [3] Warm Cached Open (100%% Ready: .idx + .t2 hit): $warmTotalElapsedMs%,d ms")
        println(f"      Warm Total Physical Lines:             ${warmSparseIndex.totalLines.value}%,d lines")
        println(f"      Warm Total Events Identified:          ${warmClassifier.totalEvents}%,d events")

        // [4] Mathematical Sparse Index Memory Footprint
        val superTableEntries: Long = warmSparseIndex.superStrideOffsets.size().toLong
        val intraTableEntries: Long = warmSparseIndex.intraStrideDeltas.size().toLong
        val indexMemoryBytes: Long = (superTableEntries * 8L) + (intraTableEntries * 4L)
        val memoryRatioPercent: Double = (indexMemoryBytes.toDouble / fileSizeBytes.toDouble) * 100.0

        println(f"  [4] Sparse Index Memory in RAM:            ${formatBytes(indexMemoryBytes)}")
        println(f"      Super-Stride Table (64-bit offsets):   $superTableEntries%,d entries (${superTableEntries * 8L / 1024} KB)")
        println(f"      Intra-Stride Table (32-bit deltas):    $intraTableEntries%,d entries (${intraTableEntries * 4L / (1024 * 1024)} MB)")
        println(f"      Index Overhead vs Raw Log:             $memoryRatioPercent%.4f%% of raw file size")

        // [5] Random Coordinate Lookup Latency (100,000 samples)
        val totalLines = warmSparseIndex.totalLines.value
        val random = new Random(42L)
        val lookupSamples = 100_000
        val sampleIndices = new Array[Long](lookupSamples)
        var i = 0
        while (i < lookupSamples) {
          sampleIndices(i) = (random.nextDouble() * (totalLines - 1).toDouble).toLong
          i += 1
        }
        var w = 0
        while (w < 1000) {
          warmSparseIndex.byteOffsetForLine(LineIndex(sampleIndices(w)), warmSession.storageReader)
          w += 1
        }
        val lookupStartNano = System.nanoTime()
        var totalResolvedOffset = 0L
        var s = 0
        while (s < lookupSamples) {
          val offset: ByteOffset = warmSparseIndex.byteOffsetForLine(LineIndex(sampleIndices(s)), warmSession.storageReader)
          totalResolvedOffset += offset.value
          s += 1
        }
        val lookupElapsedNano = System.nanoTime() - lookupStartNano
        val avgLookupMicros = (lookupElapsedNano.toDouble / lookupSamples.toDouble) / 1000.0
        val lookupsPerSec = lookupSamples.toDouble / (lookupElapsedNano.toDouble / 1_000_000_000.0)

        println(f"  [5] Random Coordinate Lookup ($lookupSamples%,d queries):")
        println(f"      Average Seek Latency:                  $avgLookupMicros%.3f µs per line")
        println(f"      Coordinate Resolution Rate:            $lookupsPerSec%,.0f seeks/sec")

        def benchmarkSearch(queryTitle: String, query: String): Unit = {
          println(s"      --- $queryTitle: '$query' ---")
          val startNano = System.nanoTime()
          val resEither = warmSession.search(query)
          val elapsedNano = System.nanoTime() - startNano
          val elapsedMs = elapsedNano / 1_000_000L
          val sec = elapsedNano.toDouble / 1_000_000_000.0
          val throughputGbPerSec = (fileSizeBytes.toDouble / (1024.0 * 1024.0 * 1024.0)) / sec

          resEither match {
            case Right(res) =>
              println(f"      Matches Found:                         ${res.totalMatches}%,d lines")
              println(f"      Search Duration:                       $elapsedMs%,d ms (${sec}%.2f s)")
              println(f"      Search Scan Throughput:                $throughputGbPerSec%.2f GB/s")
            case Left(err) =>
              println(s"      Search failed: ${err.message}")
          }
        }

        // [6] Single-Literal High-Cardinality SIMD Search
        println("  [6] SIMD Substring Searches:")
        benchmarkSearch("Single-Literal High-Cardinality", singleQuery)

        // [7] Fused Dual-Literal Compound SIMD Searches
        benchmarkSearch("Fused Dual-Literal Compound (AND)", compoundAndQuery)
        benchmarkSearch("Fused Dual-Literal Compound (OR)", compoundOrQuery)

        // [8] JVM Heap Footprint
        System.gc()
        Thread.sleep(100)
        val heapUsedBytes = memoryBean.getHeapMemoryUsage.getUsed
        val heapMaxBytes = memoryBean.getHeapMemoryUsage.getMax
        println(f"  [8] JVM Heap Footprint:                    ${heapUsedBytes / (1024 * 1024)} MB used / ${heapMaxBytes / (1024 * 1024)} MB max")
        println("=" * 80 + "\n")

      } finally {
        warmSession.close()
      }
    }
  }

  test("benchmark: Hadoop HDFS Log (~5.42 GB)") {
    runDatasetBenchmark(
      "Hadoop HDFS Namenode Log (5.42 GB)",
      HdfsLogPath,
      singleQuery = "ERROR",
      compoundAndQuery = "ERROR AND Block",
      compoundOrQuery = "ERROR OR WARN"
    )
  }

  test("benchmark: Thunderbird Supercomputer Log (~31.79 GB)") {
    runDatasetBenchmark(
      "Thunderbird Supercomputer Log (31.79 GB)",
      ThunderbirdLogPath,
      singleQuery = "fatal",
      compoundAndQuery = "fatal AND kernel",
      compoundOrQuery = "fatal OR error"
    )
  }
}
