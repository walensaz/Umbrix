package umbrix.index

import java.lang.foreign.MemorySegment
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.vfs.LogStorageReader

/**
 * Constants and port specification for the Two-Level Hierarchical Sparse Index.
 *
 * Memory footprint: ~13MB RAM for a 25GB log file (215 million lines).
 */
object SparseIndex {
  /** Lines per Level 1 super-stride: 16,384 (2^14). */
  final val SuperStrideLines: Int = 16384
  final val SuperStrideShift: Int = 14

  /** Lines per Level 2 intra-stride: 64 (2^6). */
  final val IntraStrideLines: Int = 64
  final val IntraStrideShift: Int = 6

  /** Number of intra-stride entries per superblock: 16384 / 64 = 256. */
  final val IntraEntriesPerSuperblock: Int = 256
  final val IntraMask: Int = 0x3FFF

  /** Maximum lines remaining to scan after intra-stride seek: 63 lines. */
  final val FineScanMask: Int = 0x3F

  /** Bit shift converting superblock index to base index inside `intraStrideDeltas` (256 entries/superblock). */
  final val SuperblockToIntraShift: Int = 8

  /** Mask extracting the 32-bit unsigned value stored in `intraStrideDeltas`. */
  final val UnsignedIntMask: Long = 0xFFFFFFFFL

  /** Sentinel byte offset returned when a forward scan or search finds no match. */
  final val NoMatchOffset: Long = -1L

  /** Sentinel physical/virtual line index representing an invalid or missing line. */
  final val InvalidLine: Long = -1L

  /** Computes the Level-1 superblock index (`line >>> 14`) for a raw 0-based physical line number. */
  inline def superblockIndex(rawLine: Long): Int = (rawLine >>> SuperStrideShift).toInt

  /** Computes the Level-2 slot `[0, 255]` within a superblock for a raw 0-based physical line number. */
  inline def intraSlotIndex(rawLine: Long): Int = ((rawLine & IntraMask) >>> IntraStrideShift).toInt

  /** Computes the remaining fine-scan line count `[0, 63]` past the intra-stride checkpoint. */
  inline def fineScanOffset(rawLine: Long): Int = (rawLine & FineScanMask).toInt

  /** Resolves the flat array index into `intraStrideDeltas` for `(superIndex, intraIndex)`. */
  inline def intraTableOffset(superIndex: Int, intraIndex: Int): Int = (superIndex << SuperblockToIntraShift) + intraIndex

  /** Zero-extends a signed 32-bit intra-stride delta `Int` to an unsigned `[0, 4GB-1]` `Long` byte delta. */
  inline def unsignedIntraDelta(rawDelta: Int): Long = rawDelta & UnsignedIntMask

  /** Clamps a 64-bit intra-superblock byte delta into the storable unsigned 32-bit `Int` range. */
  inline def clampIntraDelta(rawDelta: Long): Int = rawDelta.min(UnsignedIntMask).toInt

  /** Reconstructs the 0-based checkpoint line index from resolved `(superIndex, intraIndex)` table coordinates. */
  inline def checkpointLineIndex(superIndex: Int, intraIndex: Int): Long = {
    (superIndex.toLong << SuperStrideShift) + (intraIndex.toLong << IntraStrideShift)
  }

  /**
   * Compile-time inlined floor binary search returning the greatest index in `[lowBound, highBound]`
   * whose monotonic `valueAt(index) <= target` (or `lowBound` if all entries exceed `target`).
   *
   * Uses Scala 3 `inline` parameter substitution so callers incur zero `Function1` closure or boxing overhead.
   */
  inline def binarySearchFloor(lowBound: Int, highBound: Int, target: Long)(inline valueAt: Int => Long): Int = {
    var low: Int = lowBound
    var high: Int = highBound
    var resolvedIndex: Int = lowBound
    while (low <= high) {
      val mid: Int = (low + high) >>> 1
      if (valueAt(mid) <= target) {
        resolvedIndex = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }
    resolvedIndex
  }
}

/**
 * Port interface for the physical line index.
 */
trait PhysicalLineIndexer extends AutoCloseable {
  def storageReader: LogStorageReader
  def totalLines: LineIndex
  /** Bytes from file start whose lines are resolvable; below file size while progressive indexing runs. */
  def indexedByteLimit: Long = storageReader.sizeBytes
  def byteOffsetForLine(lineIndex: LineIndex): ByteOffset
  def lineIndexForByteOffset(byteOffset: ByteOffset): LineIndex
  def getLineSlice(lineIndex: LineIndex): MemorySegment
  def getLineText(lineIndex: LineIndex, maxCharacters: Int = 4096): String
}
