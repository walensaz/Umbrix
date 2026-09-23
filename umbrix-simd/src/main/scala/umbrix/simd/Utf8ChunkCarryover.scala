package umbrix.simd

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import scala.annotation.tailrec

/**
 * UTF-8 chunk boundary alignment and carryover state machine.
 *
 * Log files partitioned across multi-core workers or streaming extent buffers may split
 * multi-byte UTF-8 codepoints (2, 3, or 4-byte sequences like Greek, CJK, or Emojis) across
 * chunk boundaries.
 *
 * This state machine ensures:
 * 1. Workers slice at valid codepoint starts by skipping trailing continuation bytes (`10xxxxxx`).
 * 2. Streaming pipelines retain incomplete trailing sequences (1–3 bytes) to prepend to subsequent chunks.
 * 3. Formal verification that byte `0x0A` (`\n`) is mathematically invariant and can never collide with UTF-8 sequences.
 */
object Utf8ChunkCarryover {

  /** Checks if the given byte is a UTF-8 continuation byte (`10xxxxxx`, 0x80 to 0xBF). */
  inline def isContinuationByte(byteValue: Byte): Boolean = {
    (byteValue & 0xC0) == 0x80
  }

  /** Checks if the given byte is a leading byte of a multi-byte sequence (`11xxxxxx`, >= 0xC0). */
  inline def isLeadingByte(byteValue: Byte): Boolean = {
    (byteValue & 0xC0) == 0xC0
  }

  /** Checks if the given byte is single-byte ASCII (`0xxxxxxx`, 0x00 to 0x7F). */
  inline def isAsciiByte(byteValue: Byte): Boolean = {
    (byteValue & 0x80) == 0x00
  }

  /**
   * Computes expected total byte length of a UTF-8 sequence from its leading byte.
   *
   * @param leadingByte byte starting the sequence
   * @return 1 for ASCII, 2 for `110xxxxx`, 3 for `1110xxxx`, 4 for `11110xxx`, or 1 for invalid headers
   */
  def expectedSequenceLength(leadingByte: Byte): Int = {
    if ((leadingByte & 0x80) == 0x00) {
      1
    } else if ((leadingByte & 0xE0) == 0xC0) {
      2
    } else if ((leadingByte & 0xF0) == 0xE0) {
      3
    } else if ((leadingByte & 0xF8) == 0xF0) {
      4
    } else {
      1
    }
  }

  /**
   * Advances `candidateOffset` forward past any continuation bytes (`10xxxxxx`) to the
   * next valid codepoint boundary. At most 3 bytes are inspected.
   *
   * @param segment off-heap memory segment
   * @param candidateOffset offset to align
   * @param maxOffset boundary upper bound (exclusive)
   * @return offset aligned to the start of a valid codepoint, or `maxOffset` if reached
   */
  @tailrec
  def alignToCodepointBoundary(
    segment: MemorySegment,
    candidateOffset: Long,
    maxOffset: Long
  ): Long = {
    if (candidateOffset >= maxOffset) {
      maxOffset
    } else {
      val currentByte: Byte = segment.get(ValueLayout.JAVA_BYTE, candidateOffset)
      if (isContinuationByte(currentByte)) {
        alignToCodepointBoundary(segment, candidateOffset + 1L, maxOffset)
      } else {
        candidateOffset
      }
    }
  }

  /**
   * Advances `candidateOffset` forward to the start of the line immediately following
   * the next newline (`0x0A`) byte. Enables partitioning files into line-aligned chunks.
   *
   * @param segment off-heap memory segment
   * @param candidateOffset offset to start searching from
   * @param maxOffset boundary upper bound (exclusive)
   * @return offset of the byte following `0x0A`, or `maxOffset` if no newline is found
   */
  @tailrec
  def alignToLineBoundary(
    segment: MemorySegment,
    candidateOffset: Long,
    maxOffset: Long
  ): Long = {
    if (candidateOffset >= maxOffset) {
      maxOffset
    } else {
      val currentByte: Byte = segment.get(ValueLayout.JAVA_BYTE, candidateOffset)
      if (currentByte == 0x0A.toByte) {
        val nextLineStartOffset: Long = candidateOffset + 1L
        if (nextLineStartOffset < maxOffset) nextLineStartOffset else maxOffset
      } else {
        alignToLineBoundary(segment, candidateOffset + 1L, maxOffset)
      }
    }
  }

  /**
   * Inspects the trailing bytes of a slice to detect incomplete multi-byte sequences.
   *
   * @param segment off-heap memory segment holding slice
   * @param sliceStartOffset start offset of slice
   * @param sliceLength length of slice in bytes
   * @return number of incomplete trailing bytes (0 to 3) requiring carryover
   */
  def calculateTrailingIncompleteBytes(
    segment: MemorySegment,
    sliceStartOffset: Long,
    sliceLength: Long
  ): Int = {
    if (sliceLength <= 0L) {
      0
    } else {
      val sliceEndExclusiveOffset: Long = sliceStartOffset + sliceLength
      val lastByteOffset: Long = sliceEndExclusiveOffset - 1L
      val lastByteValue: Byte = segment.get(ValueLayout.JAVA_BYTE, lastByteOffset)

      if (isAsciiByte(lastByteValue)) {
        0
      } else {
        // Inspect up to 3 preceding bytes to find the leading byte of this sequence
        findLeadingByteDistance(segment, lastByteOffset, sliceStartOffset, 1) match {
          case None =>
            0 // Orphan continuation bytes or invalid sequence: treat as complete
          case Some((leadingByteValue, sequenceDistanceToEnd)) =>
            val expectedLength: Int = expectedSequenceLength(leadingByteValue)
            if (sequenceDistanceToEnd < expectedLength) {
              sequenceDistanceToEnd
            } else {
              0
            }
        }
      }
    }
  }

  /**
   * Helper scanning backward up to 3 bytes from `currentOffset` to identify the sequence header.
   */
  @tailrec
  private def findLeadingByteDistance(
    segment: MemorySegment,
    currentOffset: Long,
    minOffset: Long,
    distanceFromEnd: Int
  ): Option[(Byte, Int)] = {
    if (distanceFromEnd > 4 || currentOffset < minOffset) {
      None
    } else {
      val byteValue: Byte = segment.get(ValueLayout.JAVA_BYTE, currentOffset)
      if (isLeadingByte(byteValue)) {
        Some((byteValue, distanceFromEnd))
      } else if (isContinuationByte(byteValue)) {
        findLeadingByteDistance(segment, currentOffset - 1L, minOffset, distanceFromEnd + 1)
      } else {
        None
      }
    }
  }
}
