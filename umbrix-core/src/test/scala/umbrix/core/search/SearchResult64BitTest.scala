package umbrix.core.search

import munit.FunSuite
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.core.view.FilteredLineView
import umbrix.index.PhysicalLineIndexer

class SearchResult64BitTest extends FunSuite {

  test("SearchResult correctly handles 64-bit line indices exceeding 2.14 billion lines") {
    val bitmap = new Roaring64NavigableMap()
    val lineA: LineIndex = LineIndex(2_500_000_000L)  // Exceeds Int.MaxValue (2,147,483,647)
    val lineB: LineIndex = LineIndex(5_000_000_000L)
    val lineC: LineIndex = LineIndex(10_000_000_000L)

    bitmap.addLong(lineA.value)
    bitmap.addLong(lineB.value)
    bitmap.addLong(lineC.value)

    val result = SearchResult(bitmap, 3L, 1000L, isComplete = true)

    assertEquals(result.totalMatches, 3L)
    assert(result.nonEmpty)
    assert(!result.isEmpty)

    // Exact contains checks
    assert(result.matchingLines.contains(lineA.value))
    assert(result.matchingLines.contains(lineB.value))
    assert(result.matchingLines.contains(lineC.value))
    assert(!result.matchingLines.contains(lineA.value - 1L))
    assert(!result.matchingLines.contains(lineB.value + 1L))

    // Forward search (findNext and findNextLine)
    assertEquals(result.findNext(LineIndex(0L)), Some(lineA))
    assertEquals(result.findNextLine(0L), lineA.value)
    assertEquals(result.findNext(lineA), Some(lineA))
    assertEquals(result.findNextLine(lineA.value), lineA.value)
    assertEquals(result.findNext(lineA + 1L), Some(lineB))
    assertEquals(result.findNextLine(lineA.value + 1L), lineB.value)
    assertEquals(result.findNext(lineB), Some(lineB))
    assertEquals(result.findNextLine(lineB.value), lineB.value)
    assertEquals(result.findNext(lineB + 1L), Some(lineC))
    assertEquals(result.findNextLine(lineB.value + 1L), lineC.value)
    assertEquals(result.findNext(lineC), Some(lineC))
    assertEquals(result.findNextLine(lineC.value), lineC.value)
    assertEquals(result.findNext(lineC + 1L), None)
    assertEquals(result.findNextLine(lineC.value + 1L), -1L)

    // Backward search (findPrevious and findPreviousLine)
    assertEquals(result.findPrevious(lineC + 100L), Some(lineC))
    assertEquals(result.findPreviousLine(lineC.value + 100L), lineC.value)
    assertEquals(result.findPrevious(lineC), Some(lineC))
    assertEquals(result.findPreviousLine(lineC.value), lineC.value)
    assertEquals(result.findPrevious(lineC - 1L), Some(lineB))
    assertEquals(result.findPreviousLine(lineC.value - 1L), lineB.value)
    assertEquals(result.findPrevious(lineB), Some(lineB))
    assertEquals(result.findPreviousLine(lineB.value), lineB.value)
    assertEquals(result.findPrevious(lineB - 1L), Some(lineA))
    assertEquals(result.findPreviousLine(lineB.value - 1L), lineA.value)
    assertEquals(result.findPrevious(lineA), Some(lineA))
    assertEquals(result.findPreviousLine(lineA.value), lineA.value)
    assertEquals(result.findPrevious(lineA - 1L), None)
    assertEquals(result.findPreviousLine(lineA.value - 1L), -1L)
  }

  test("FilteredLineView translates virtual coordinates beyond 2.14 billion lines") {
    val bitmap = new Roaring64NavigableMap()
    val line1: LineIndex = LineIndex(3_000_000_000L)
    val line2: LineIndex = LineIndex(6_000_000_000L)
    val line3: LineIndex = LineIndex(9_000_000_000L)

    bitmap.addLong(line1.value)
    bitmap.addLong(line2.value)
    bitmap.addLong(line3.value)

    // Mock minimal PhysicalLineIndexer for coordinate math testing
    val mockIndexer = new PhysicalLineIndexer {
      def totalLines: LineIndex = LineIndex(10_000_000_000L)
      def byteOffsetForLine(lineIndex: LineIndex): ByteOffset = ByteOffset(lineIndex.value * 100L)
      def lineIndexForByteOffset(byteOffset: ByteOffset): LineIndex = LineIndex(byteOffset.value / 100L)
      def lineLength(lineIndex: LineIndex): Long = 100L
      def getLineSlice(lineIndex: LineIndex): java.lang.foreign.MemorySegment = java.lang.foreign.MemorySegment.NULL
      def getLineText(lineIndex: LineIndex, maxCharacters: Int): String = s"line-${lineIndex.value}"
      def storageReader: umbrix.vfs.LogStorageReader = null
      def close(): Unit = ()
    }

    val filteredView = new FilteredLineView(bitmap, mockIndexer)

    assertEquals(filteredView.totalFilteredLines, LineIndex(3L))
    assert(!filteredView.isEmpty)

    // Virtual to physical mapping
    assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(0L)), line1)
    assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(1L)), line2)
    assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(2L)), line3)

    // Out of bounds virtual index
    assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(-1L)), LineIndex(0L))
    assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(3L)), LineIndex(0L))

    // Physical to virtual mapping
    assertEquals(filteredView.virtualIndexForPhysicalLine(line1), LineIndex(0L))
    assertEquals(filteredView.virtualIndexForPhysicalLine(line2), LineIndex(1L))
    assertEquals(filteredView.virtualIndexForPhysicalLine(line3), LineIndex(2L))

    // Physical lines between matches
    assertEquals(filteredView.virtualIndexForPhysicalLine(line1 + 100L), LineIndex(1L))
    assertEquals(filteredView.virtualIndexForPhysicalLine(line2 + 100L), LineIndex(2L))

    // Contains check
    assert(filteredView.containsPhysicalLine(line1))
    assert(filteredView.containsPhysicalLine(line2))
    assert(filteredView.containsPhysicalLine(line3))
    assert(!filteredView.containsPhysicalLine(line1 + 1L))
  }

  test("microbenchmark: findNextLine eliminates boxing and outperforms Option[Long] findNext") {
    val bitmap = new Roaring64NavigableMap()
    for (i <- 0L until 100_000L by 10L) {
      bitmap.addLong(i)
    }
    val result = SearchResult(bitmap, 10_000L, 1000L, isComplete = true)

    val iterations = 500_000
    // Balanced JIT Warmup
    var warmupSum = 0L
    for (i <- 0 until 100_000) {
      val idx = (i % 10_000).toLong
      warmupSum += result.findNextLine(idx)
      val opt = result.findNext(LineIndex(idx))
      if (opt.isDefined) {
        warmupSum += opt.get.value
      }
    }

    // 1. Primitive findNextLine (Zero Boxing)
    val t0 = System.nanoTime()
    var primitiveSum = 0L
    for (i <- 0 until iterations) {
      val next = result.findNextLine((i % 100_000).toLong)
      if (next >= 0L) {
        primitiveSum += next
      }
    }
    val primitiveNanos = System.nanoTime() - t0

    // 2. Boxed Option[LineIndex] findNext
    val t1 = System.nanoTime()
    var boxedSum = 0L
    for (i <- 0 until iterations) {
      val opt = result.findNext(LineIndex((i % 100_000).toLong))
      if (opt.isDefined) {
        boxedSum += opt.get.value
      }
    }
    val boxedNanos = System.nanoTime() - t1

    assertEquals(primitiveSum, boxedSum)
    println(f"=== Hit Navigation Benchmark ($iterations%,d calls) ===")
    println(f"Primitive findNextLine: ${primitiveNanos / 1e6}%.2f ms (${iterations * 1e9 / primitiveNanos}%,.0f ops/sec)")
    println(f"Boxed Option findNext : ${boxedNanos / 1e6}%.2f ms (${iterations * 1e9 / boxedNanos}%,.0f ops/sec)")
    val speedup = boxedNanos.toDouble / primitiveNanos.toDouble
    println(f"Speedup from zero-boxing: ${speedup}%.2fx")
    assert(primitiveSum > 0L, "Must find matches")
  }
}
