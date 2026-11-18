package umbrix.core.search

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * High-performance off-heap CharSequence adapter.
 *
 * Wraps a native off-heap MemorySegment slice without allocating any JVM heap Strings.
 * Enables zero-heap RE2J linear regular expression matching and scanning directly against
 * the OS page cache.
 */
class OffHeapCharSequence(
  val segment: MemorySegment,
  val baseOffset: Long,
  val sequenceLength: Int
) extends CharSequence {

  def length(): Int = sequenceLength

  def charAt(index: Int): Char = {
    if (index < 0 || index >= sequenceLength) {
      throw new IndexOutOfBoundsException(s"Index $index out of bounds for length $sequenceLength")
    } else {
      (segment.get(ValueLayout.JAVA_BYTE, baseOffset + index.toLong) & 0xFF).toChar
    }
  }

  def subSequence(start: Int, end: Int): CharSequence = {
    if (start < 0 || end < start || end > sequenceLength) {
      throw new IndexOutOfBoundsException(s"subSequence($start, $end) out of bounds for length $sequenceLength")
    } else {
      new OffHeapCharSequence(segment, baseOffset + start.toLong, end - start)
    }
  }

  override def toString: String = {
    // Only instantiated if explicitly called for debugging; not invoked during hot-path searches
    val bytes: Array[Byte] = new Array[Byte](sequenceLength)
    MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, baseOffset, bytes, 0, sequenceLength)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
  }
}
