package umbrix.core.types

import scala.annotation.targetName

/**
 * Zero-boxing domain primitives for Umbrix.
 *
 * Implemented as Scala 3 opaque types to guarantee 100% compile-time type safety
 * with zero JVM object allocation on hot indexing, search, and coordinate translation paths.
 * At JVM runtime, these erase directly to primitive `long`.
 */

opaque type LineIndex = Long

/**
 * Companion object and zero-cost extension operations for [[LineIndex]].
 * Represents a 0-based logical or physical line index in a log file.
 */
object LineIndex {
  inline def apply(rawLineIndex: Long): LineIndex = rawLineIndex
  val Zero: LineIndex = 0L
  val MinValue: LineIndex = Long.MinValue
  val MaxValue: LineIndex = Long.MaxValue

  extension (lineIndex: LineIndex) {
    inline def value: Long = lineIndex
    inline def +(offset: Long): LineIndex = lineIndex + offset
    @targetName("minusOffset")
    inline def -(offset: Long): LineIndex = lineIndex - offset
    @targetName("minusLine")
    inline def -(other: LineIndex): Long = lineIndex - other.value
    inline def <(other: LineIndex): Boolean = lineIndex < other.value
    inline def <=(other: LineIndex): Boolean = lineIndex <= other.value
    inline def >(other: LineIndex): Boolean = lineIndex > other.value
    inline def >=(other: LineIndex): Boolean = lineIndex >= other.value
    inline def ==(other: LineIndex): Boolean = lineIndex == other.value
    inline def !=(other: LineIndex): Boolean = lineIndex != other.value
    inline def min(other: LineIndex): LineIndex = Math.min(lineIndex, other.value)
    inline def max(other: LineIndex): LineIndex = Math.max(lineIndex, other.value)
    inline def coerceIn(minLine: LineIndex, maxLine: LineIndex): LineIndex = {
      if (lineIndex < minLine.value) minLine
      else if (lineIndex > maxLine.value) maxLine
      else lineIndex
    }
  }

  given Ordering[LineIndex] = Ordering.Long.asInstanceOf[Ordering[LineIndex]]
}

opaque type ByteOffset = Long

/**
 * Companion object and zero-cost extension operations for [[ByteOffset]].
 * Represents a 0-based 64-bit absolute byte position within a file or storage stream.
 */
object ByteOffset {
  inline def apply(rawByteOffset: Long): ByteOffset = rawByteOffset
  val Zero: ByteOffset = 0L
  val MinValue: ByteOffset = Long.MinValue
  val MaxValue: ByteOffset = Long.MaxValue

  extension (byteOffset: ByteOffset) {
    inline def value: Long = byteOffset
    inline def +(offset: Long): ByteOffset = byteOffset + offset
    @targetName("minusOffset")
    inline def -(offset: Long): ByteOffset = byteOffset - offset
    @targetName("minusByteOffset")
    inline def -(other: ByteOffset): Long = byteOffset - other.value
    inline def <(other: ByteOffset): Boolean = byteOffset < other.value
    inline def <=(other: ByteOffset): Boolean = byteOffset <= other.value
    inline def >(other: ByteOffset): Boolean = byteOffset > other.value
    inline def >=(other: ByteOffset): Boolean = byteOffset >= other.value
    inline def ==(other: ByteOffset): Boolean = byteOffset == other.value
    inline def !=(other: ByteOffset): Boolean = byteOffset != other.value
    inline def min(other: ByteOffset): ByteOffset = Math.min(byteOffset, other.value)
    inline def max(other: ByteOffset): ByteOffset = Math.max(byteOffset, other.value)
  }

  given Ordering[ByteOffset] = Ordering.Long.asInstanceOf[Ordering[ByteOffset]]
}

opaque type EpochMicros = Long

/**
 * Companion object and zero-cost extension operations for [[EpochMicros]].
 * Represents microsecond-resolution Unix epoch timestamps.
 */
object EpochMicros {
  inline def apply(rawEpochMicros: Long): EpochMicros = rawEpochMicros
  val Zero: EpochMicros = 0L
  val MinValue: EpochMicros = Long.MinValue
  val MaxValue: EpochMicros = Long.MaxValue

  extension (epochMicros: EpochMicros) {
    inline def value: Long = epochMicros
    inline def +(offset: Long): EpochMicros = epochMicros + offset
    @targetName("minusOffset")
    inline def -(offset: Long): EpochMicros = epochMicros - offset
    @targetName("minusEpoch")
    inline def -(other: EpochMicros): Long = epochMicros - other.value
    inline def <(other: EpochMicros): Boolean = epochMicros < other.value
    inline def <=(other: EpochMicros): Boolean = epochMicros <= other.value
    inline def >(other: EpochMicros): Boolean = epochMicros > other.value
    inline def >=(other: EpochMicros): Boolean = epochMicros >= other.value
    inline def ==(other: EpochMicros): Boolean = epochMicros == other.value
    inline def !=(other: EpochMicros): Boolean = epochMicros != other.value
    inline def min(other: EpochMicros): EpochMicros = Math.min(epochMicros, other.value)
    inline def max(other: EpochMicros): EpochMicros = Math.max(epochMicros, other.value)
  }

  given Ordering[EpochMicros] = Ordering.Long.asInstanceOf[Ordering[EpochMicros]]
}
