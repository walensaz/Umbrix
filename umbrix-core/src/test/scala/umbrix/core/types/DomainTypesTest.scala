package umbrix.core.types

import munit.FunSuite

class DomainTypesTest extends FunSuite {

  test("LineIndex opaque type operations preserve 64-bit precision") {
    val idx1 = LineIndex(1000L)
    val idx2 = idx1 + 250L
    val idx3 = idx1 - 100L

    assertEquals(idx2.value, 1250L)
    assertEquals(idx3.value, 900L)
    assertEquals(idx2 - idx1, 250L)

    assert(idx1 < idx2)
    assert(idx2 > idx1)
    assert(idx1 <= idx1)
    assert(idx1 >= idx1)
    assert(idx1 == LineIndex(1000L))
    assert(idx1 != idx2)

    assertEquals(idx1.min(idx2), idx1)
    assertEquals(idx1.max(idx2), idx2)
    assertEquals(LineIndex(500L).coerceIn(LineIndex(100L), LineIndex(400L)), LineIndex(400L))
    assertEquals(LineIndex(50L).coerceIn(LineIndex(100L), LineIndex(400L)), LineIndex(100L))
    assertEquals(LineIndex(250L).coerceIn(LineIndex(100L), LineIndex(400L)), LineIndex(250L))

    // Ordering test
    val unsorted = List(LineIndex(30L), LineIndex(10L), LineIndex(20L))
    val sorted = unsorted.sorted
    assertEquals(sorted, List(LineIndex(10L), LineIndex(20L), LineIndex(30L)))
  }

  test("ByteOffset opaque type operates with zero boxing overhead") {
    val offset = ByteOffset(25_000_000_000L) // 25 GB
    val nextOffset = offset + 64L
    val prevOffset = offset - 64L

    assertEquals(nextOffset.value, 25_000_000_064L)
    assertEquals(prevOffset.value, 24_999_999_936L)
    assertEquals(nextOffset - offset, 64L)

    assert(offset < nextOffset)
    assert(nextOffset > offset)
    assert(offset <= offset)
    assert(offset >= offset)
    assert(offset == ByteOffset(25_000_000_000L))

    assertEquals(offset.min(nextOffset), offset)
    assertEquals(offset.max(nextOffset), nextOffset)

    val unsorted = List(ByteOffset(300L), ByteOffset(100L), ByteOffset(200L))
    assertEquals(unsorted.sorted, List(ByteOffset(100L), ByteOffset(200L), ByteOffset(300L)))
  }

  test("EpochMicros opaque type handles microsecond temporal coordinates") {
    val t1 = EpochMicros(1_700_000_000_000_000L)
    val t2 = t1 + 5_000_000L // +5s
    val t3 = t1 - 1_000_000L // -1s

    assertEquals(t2.value, 1_700_000_005_000_000L)
    assertEquals(t3.value, 1_699_999_999_000_000L)
    assertEquals(t2 - t1, 5_000_000L)

    assert(t1 < t2)
    assert(t2 > t1)
    assert(t1 <= t1)
    assert(t1 >= t1)
    assert(t1 == EpochMicros(1_700_000_000_000_000L))

    assertEquals(t1.min(t2), t1)
    assertEquals(t1.max(t2), t2)

    val unsorted = List(EpochMicros(3000L), EpochMicros(1000L), EpochMicros(2000L))
    assertEquals(unsorted.sorted, List(EpochMicros(1000L), EpochMicros(2000L), EpochMicros(3000L)))
  }
}
