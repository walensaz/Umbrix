package umbrix.simd

import java.lang.foreign.MemorySegment
import java.util.function.LongConsumer

/**
 * Fundamental zero-heap scanning contract for counting and enumerating byte occurrences.
 *
 * Guarantees zero JVM heap memory allocation: all operations scan directly over off-heap
 * `MemorySegment` buffers without converting raw bytes into `java.lang.String` or boxed collections.
 * Callbacks use primitive `java.util.function.LongConsumer` to pass 64-bit file offsets.
 */
trait ByteScanner {

  /**
   * Counts total occurrences of `targetByte` within the specified memory slice.
   *
   * @param segment off-heap memory segment holding raw data
   * @param startOffset starting byte offset within the segment (inclusive)
   * @param length number of bytes to scan
   * @param targetByte byte value to search for
   * @return total count of matching bytes
   */
  def countByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte
  ): Long

  /**
   * Scans for occurrences of `targetByte` within the specified memory slice, emitting
   * the absolute 64-bit byte offset of each match to the supplied consumer.
   *
   * @param segment off-heap memory segment holding raw data
   * @param startOffset starting byte offset within the segment (inclusive)
   * @param length number of bytes to scan
   * @param targetByte byte value to search for
   * @param matchConsumer callback receiving primitive absolute 64-bit byte offsets
   * @return total count of matching bytes
   */
  def scanByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte,
    matchConsumer: LongConsumer
  ): Long

  /**
   * Convenience helper for counting newline bytes (`0x0A`).
   */
  def countNewlines(segment: MemorySegment, startOffset: Long, length: Long): Long = {
    countByte(segment, startOffset, length, 0x0A.toByte)
  }

  /**
   * Convenience helper for enumerating newline byte (`0x0A`) offsets.
   */
  def scanNewlines(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    matchConsumer: LongConsumer
  ): Long = {
    scanByte(segment, startOffset, length, 0x0A.toByte, matchConsumer)
  }
}
