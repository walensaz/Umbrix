package umbrix.simd

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.function.LongConsumer

/**
 * Universal sequential scalar scanner for off-heap memory segments.
 *
 * Traverses bytes sequentially using Java FFM `ValueLayout.JAVA_BYTE`.
 * Provides the golden reference implementation for correctness verification in unit tests
 * and serves as a reliable fallback whenever SIMD vector hardware is unavailable.
 */
object ScalarScanner extends ByteScanner {

  def countByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte
  ): Long = {
    val endOffset: Long = startOffset + length
    var currentByteOffset: Long = startOffset
    var totalMatchesAccumulator: Long = 0L
    while (currentByteOffset < endOffset) {
      if (segment.get(ValueLayout.JAVA_BYTE, currentByteOffset) == targetByte) {
        totalMatchesAccumulator += 1L
      }
      currentByteOffset += 1L
    }
    totalMatchesAccumulator
  }

  def scanByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte,
    matchConsumer: LongConsumer
  ): Long = {
    val endOffset: Long = startOffset + length
    var currentByteOffset: Long = startOffset
    var totalMatchesAccumulator: Long = 0L
    while (currentByteOffset < endOffset) {
      if (segment.get(ValueLayout.JAVA_BYTE, currentByteOffset) == targetByte) {
        matchConsumer.accept(currentByteOffset)
        totalMatchesAccumulator += 1L
      }
      currentByteOffset += 1L
    }
    totalMatchesAccumulator
  }
}
