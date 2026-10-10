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
