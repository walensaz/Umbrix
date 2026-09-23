package umbrix.core.search

import munit.Tag
import org.roaringbitmap.RoaringBitmap
import org.roaringbitmap.longlong.Roaring64Bitmap
import org.roaringbitmap.longlong.Roaring64NavigableMap

/**
 * Performance benchmark comparing 32-bit RoaringBitmap vs 64-bit Roaring64NavigableMap
 * vs 64-bit Roaring64Bitmap for Umbrix search result bitset operations.
 *
 * Tests the critical hot-path operations used by Umbrix:
 * 1. Bulk insertion (simulating search result population)
 * 2. Contains (per-line match checking during viewport rendering)
 * 3. Rank (coordinate translation in FilteredLineView)
 * 4. Select (virtual-to-physical line mapping)
 * 5. Iteration (export/slicing loops)
 * 6. Cardinality (total match count)
 * 7. OR merge (combining parallel chunk results)
 *
 * Simulates a realistic workload: a 25GB log file with ~215 million lines,
 * where a search matches ~1% of lines (2.15 million matches).
 */
class RoaringBitmapBenchmarkTest extends munit.FunSuite {

  val Benchmark: Tag = new Tag("Benchmark")

  private val totalLines: Long = 215_000_000L
  private val matchPercentage: Double = 0.01
  private val matchCount: Int = (totalLines * matchPercentage).toInt
  private val warmupIterations: Int = 3
  private val measuredIterations: Int = 5

  // Generate deterministic pseudo-random match indices spread across the line space
  private def generateMatchIndices(count: Int, maxLine: Long): Array[Long] = {
    val step: Long = maxLine / count
    val indices: Array[Long] = new Array[Long](count)
    var i: Int = 0
    while (i < count) {
      indices(i) = (i.toLong * step) + (i.toLong % 7L) // slight jitter for realism
      i += 1
    }
    indices
  }

  private val matchIndices: Array[Long] = generateMatchIndices(matchCount, totalLines)

  private def benchmarkNanos(label: String, warmups: Int, iterations: Int)(body: => Unit): Long = {
    // Warmup
    var w: Int = 0
    while (w < warmups) {
      body
      w += 1
    }

    // Measured runs
    val timings: Array[Long] = new Array[Long](iterations)
    var i: Int = 0
    while (i < iterations) {
      val startNanos: Long = System.nanoTime()
      body
      timings(i) = System.nanoTime() - startNanos
      i += 1
    }

    val sorted: Array[Long] = timings.sorted
    val medianNanos: Long = sorted(iterations / 2)
    val meanNanos: Long = timings.sum / iterations
    val minNanos: Long = sorted.head
    val maxNanos: Long = sorted.last

    println(f"  $label%-45s median=${medianNanos / 1000}%,10d µs  mean=${meanNanos / 1000}%,10d µs  min=${minNanos / 1000}%,10d µs  max=${maxNanos / 1000}%,10d µs")
    medianNanos
  }

  test("Benchmark: RoaringBitmap (32-bit) vs Roaring64NavigableMap vs Roaring64Bitmap".ignore) {
    println()
    println("=" * 120)
    println(s"  ROARING BITMAP BENCHMARK — $matchCount matches across $totalLines total lines (${matchPercentage * 100}% hit rate)")
    println("=" * 120)

    // ===== 1. BULK INSERTION =====
    println()
    println("--- 1. Bulk Insertion ---")

    var rb32: RoaringBitmap = null
    var rb64nav: Roaring64NavigableMap = null
    var rb64bmp: Roaring64Bitmap = null

    val insert32 = benchmarkNanos("RoaringBitmap.add(int)", warmupIterations, measuredIterations) {
      rb32 = new RoaringBitmap()
      var i: Int = 0
      while (i < matchIndices.length) {
        rb32.add(matchIndices(i).toInt)
        i += 1
      }
    }

    val insert64nav = benchmarkNanos("Roaring64NavigableMap.addLong(long)", warmupIterations, measuredIterations) {
      rb64nav = new Roaring64NavigableMap()
      var i: Int = 0
      while (i < matchIndices.length) {
        rb64nav.addLong(matchIndices(i))
        i += 1
      }
    }

    val insert64bmp = benchmarkNanos("Roaring64Bitmap.addLong(long)", warmupIterations, measuredIterations) {
      rb64bmp = new Roaring64Bitmap()
      var i: Int = 0
      while (i < matchIndices.length) {
        rb64bmp.addLong(matchIndices(i))
        i += 1
      }
    }

    // Ensure populated for subsequent tests
    rb32 = new RoaringBitmap()
    matchIndices.foreach(idx => rb32.add(idx.toInt))
    rb64nav = new Roaring64NavigableMap()
    matchIndices.foreach(idx => rb64nav.addLong(idx))
    rb64bmp = new Roaring64Bitmap()
    matchIndices.foreach(idx => rb64bmp.addLong(idx))

    // ===== 2. CONTAINS (per-line hit check) =====
    println()
    println("--- 2. Contains (1M random lookups) ---")
    val lookupCount: Int = 1_000_000
    val lookupIndices: Array[Long] = {
      val arr = new Array[Long](lookupCount)
      var i = 0
      while (i < lookupCount) {
        arr(i) = (i.toLong * 215L) % totalLines
        i += 1
      }
      arr
    }

    benchmarkNanos("RoaringBitmap.contains(int)", warmupIterations, measuredIterations) {
      var hits: Int = 0
      var i: Int = 0
      while (i < lookupCount) {
        if (rb32.contains(lookupIndices(i).toInt)) hits += 1
        i += 1
      }
    }

    benchmarkNanos("Roaring64NavigableMap.contains(long)", warmupIterations, measuredIterations) {
      var hits: Int = 0
      var i: Int = 0
      while (i < lookupCount) {
        if (rb64nav.contains(lookupIndices(i))) hits += 1
        i += 1
      }
    }

    benchmarkNanos("Roaring64Bitmap.contains(long)", warmupIterations, measuredIterations) {
      var hits: Int = 0
      var i: Int = 0
      while (i < lookupCount) {
        if (rb64bmp.contains(lookupIndices(i))) hits += 1
        i += 1
      }
    }

    // ===== 3. RANK (coordinate translation) =====
    println()
    println("--- 3. Rank (100K queries) ---")
    val rankCount: Int = 100_000
    val rankIndices: Array[Long] = {
      val arr = new Array[Long](rankCount)
      var i = 0
      while (i < rankCount) {
        arr(i) = (i.toLong * 2150L) % totalLines
        i += 1
      }
      arr
    }

    benchmarkNanos("RoaringBitmap.rank(int)", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < rankCount) {
        sum += rb32.rank(rankIndices(i).toInt)
        i += 1
      }
    }

    benchmarkNanos("Roaring64NavigableMap.rankLong(long)", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < rankCount) {
        sum += rb64nav.rankLong(rankIndices(i))
        i += 1
      }
    }

    benchmarkNanos("Roaring64Bitmap.rankLong(long)", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < rankCount) {
        sum += rb64bmp.rankLong(rankIndices(i))
        i += 1
      }
    }

    // ===== 4. SELECT (virtual-to-physical mapping) =====
    println()
    println("--- 4. Select (100K queries) ---")

    val cardinality32: Int = rb32.getCardinality
    val cardinality64: Long = rb64nav.getLongCardinality

    benchmarkNanos("RoaringBitmap.select(int)", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < rankCount) {
        val rank: Int = (i * (cardinality32.toLong / rankCount)).toInt
        if (rank >= 0 && rank < cardinality32) {
          sum += rb32.select(rank)
        }
        i += 1
      }
    }

    benchmarkNanos("Roaring64NavigableMap.select(long)", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < rankCount) {
        val rank: Long = (i.toLong * (cardinality64 / rankCount))
        if (rank >= 0L && rank < cardinality64) {
          sum += rb64nav.select(rank)
        }
        i += 1
      }
    }

    benchmarkNanos("Roaring64Bitmap.select(long)", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < rankCount) {
        val rank: Long = (i.toLong * (cardinality64 / rankCount))
        if (rank >= 0L && rank < cardinality64) {
          sum += rb64bmp.select(rank)
        }
        i += 1
      }
    }

    // ===== 5. FULL ITERATION =====
    println()
    println("--- 5. Full Iteration (all matches) ---")

    benchmarkNanos("RoaringBitmap IntIterator", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      val iter = rb32.getIntIterator
      while (iter.hasNext) {
        sum += iter.next()
      }
    }

    benchmarkNanos("Roaring64NavigableMap LongIterator", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      val iter = rb64nav.getLongIterator
      while (iter.hasNext) {
        sum += iter.next()
      }
    }

    benchmarkNanos("Roaring64Bitmap LongIterator", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      val iter = rb64bmp.getLongIterator
      while (iter.hasNext) {
        sum += iter.next()
      }
    }

    // ===== 6. CARDINALITY =====
    println()
    println("--- 6. Cardinality ---")

    benchmarkNanos("RoaringBitmap.getCardinality", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < 100_000) {
        sum += rb32.getCardinality
        i += 1
      }
    }

    benchmarkNanos("Roaring64NavigableMap.getLongCardinality", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < 100_000) {
        sum += rb64nav.getLongCardinality
        i += 1
      }
    }

    benchmarkNanos("Roaring64Bitmap.getLongCardinality", warmupIterations, measuredIterations) {
      var sum: Long = 0L
      var i: Int = 0
      while (i < 100_000) {
        sum += rb64bmp.getLongCardinality
        i += 1
      }
    }

    // ===== 7. OR MERGE (combining parallel chunk results) =====
    println()
    println("--- 7. OR Merge (4 chunks into 1) ---")

    // Build 4 chunks
    val chunkSize: Int = matchCount / 4
    val chunks32: Array[RoaringBitmap] = Array.fill(4)(new RoaringBitmap())
    val chunks64nav: Array[Roaring64NavigableMap] = Array.fill(4)(new Roaring64NavigableMap())
    val chunks64bmp: Array[Roaring64Bitmap] = Array.fill(4)(new Roaring64Bitmap())

    var ci: Int = 0
    while (ci < matchIndices.length) {
      val chunkId: Int = ci / chunkSize
      val boundedChunkId: Int = if (chunkId >= 4) 3 else chunkId
      chunks32(boundedChunkId).add(matchIndices(ci).toInt)
      chunks64nav(boundedChunkId).addLong(matchIndices(ci))
      chunks64bmp(boundedChunkId).addLong(matchIndices(ci))
      ci += 1
    }

    benchmarkNanos("RoaringBitmap.or (4 chunks)", warmupIterations, measuredIterations) {
      val combined = new RoaringBitmap()
      combined.or(chunks32(0))
      combined.or(chunks32(1))
      combined.or(chunks32(2))
      combined.or(chunks32(3))
    }

    benchmarkNanos("Roaring64NavigableMap.or (4 chunks)", warmupIterations, measuredIterations) {
      val combined = new Roaring64NavigableMap()
      combined.or(chunks64nav(0))
      combined.or(chunks64nav(1))
      combined.or(chunks64nav(2))
      combined.or(chunks64nav(3))
    }

    benchmarkNanos("Roaring64Bitmap.or (4 chunks)", warmupIterations, measuredIterations) {
      val combined = new Roaring64Bitmap()
      combined.or(chunks64bmp(0))
      combined.or(chunks64bmp(1))
      combined.or(chunks64bmp(2))
      combined.or(chunks64bmp(3))
    }

    // ===== 8. MEMORY FOOTPRINT =====
    println()
    println("--- 8. Serialized Size (bytes) ---")
    println(f"  RoaringBitmap:          ${rb32.serializedSizeInBytes()}%,12d bytes")
    println(f"  Roaring64NavigableMap:   ${rb64nav.serializedSizeInBytes()}%,12d bytes")
    println(f"  Roaring64Bitmap:         ${rb64bmp.serializedSizeInBytes()}%,12d bytes")

    println()
    println("=" * 120)
    println("  BENCHMARK COMPLETE")
    println("=" * 120)

    // Sanity assertion: NavigableMap should have same cardinality as 32-bit
    assertEquals(rb64nav.getLongCardinality, rb32.getCardinality.toLong)
  }
}
