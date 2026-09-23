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
object LineIndex {
  inline def apply(v: Long): LineIndex = v
  val Zero: LineIndex = 0L
  val MinValue: LineIndex = Long.MinValue
  val MaxValue: LineIndex = Long.MaxValue

  extension (l: LineIndex) {
    inline def value: Long = l
    inline def +(offset: Long): LineIndex = l + offset
    @targetName("minusOffset")
    inline def -(offset: Long): LineIndex = l - offset
    @targetName("minusLine")
    inline def -(other: LineIndex): Long = l - other.value
    inline def <(other: LineIndex): Boolean = l < other.value
    inline def <=(other: LineIndex): Boolean = l <= other.value
    inline def >(other: LineIndex): Boolean = l > other.value
    inline def >=(other: LineIndex): Boolean = l >= other.value
    inline def ==(other: LineIndex): Boolean = l == other.value
    inline def !=(other: LineIndex): Boolean = l != other.value
    inline def min(other: LineIndex): LineIndex = Math.min(l, other.value)
    inline def max(other: LineIndex): LineIndex = Math.max(l, other.value)
    inline def coerceIn(minLine: LineIndex, maxLine: LineIndex): LineIndex = {
      if (l < minLine.value) minLine
      else if (l > maxLine.value) maxLine
      else l
    }
  }

  given Ordering[LineIndex] = Ordering.Long.asInstanceOf[Ordering[LineIndex]]
}

opaque type ByteOffset = Long
object ByteOffset {
  inline def apply(v: Long): ByteOffset = v
  val Zero: ByteOffset = 0L
  val MinValue: ByteOffset = Long.MinValue
  val MaxValue: ByteOffset = Long.MaxValue

  extension (b: ByteOffset) {
    inline def value: Long = b
    inline def +(offset: Long): ByteOffset = b + offset
    @targetName("minusOffset")
    inline def -(offset: Long): ByteOffset = b - offset
    @targetName("minusByteOffset")
    inline def -(other: ByteOffset): Long = b - other.value
    inline def <(other: ByteOffset): Boolean = b < other.value
    inline def <=(other: ByteOffset): Boolean = b <= other.value
    inline def >(other: ByteOffset): Boolean = b > other.value
    inline def >=(other: ByteOffset): Boolean = b >= other.value
    inline def ==(other: ByteOffset): Boolean = b == other.value
    inline def !=(other: ByteOffset): Boolean = b != other.value
    inline def min(other: ByteOffset): ByteOffset = Math.min(b, other.value)
    inline def max(other: ByteOffset): ByteOffset = Math.max(b, other.value)
  }

  given Ordering[ByteOffset] = Ordering.Long.asInstanceOf[Ordering[ByteOffset]]
}

opaque type EpochMicros = Long
object EpochMicros {
  inline def apply(v: Long): EpochMicros = v
  val Zero: EpochMicros = 0L
  val MinValue: EpochMicros = Long.MinValue
  val MaxValue: EpochMicros = Long.MaxValue

  extension (e: EpochMicros) {
    inline def value: Long = e
    inline def +(offset: Long): EpochMicros = e + offset
    @targetName("minusOffset")
    inline def -(offset: Long): EpochMicros = e - offset
    @targetName("minusEpoch")
    inline def -(other: EpochMicros): Long = e - other.value
    inline def <(other: EpochMicros): Boolean = e < other.value
    inline def <=(other: EpochMicros): Boolean = e <= other.value
    inline def >(other: EpochMicros): Boolean = e > other.value
    inline def >=(other: EpochMicros): Boolean = e >= other.value
    inline def ==(other: EpochMicros): Boolean = e == other.value
    inline def !=(other: EpochMicros): Boolean = e != other.value
    inline def min(other: EpochMicros): EpochMicros = Math.min(e, other.value)
    inline def max(other: EpochMicros): EpochMicros = Math.max(e, other.value)
  }

  given Ordering[EpochMicros] = Ordering.Long.asInstanceOf[Ordering[EpochMicros]]
}
