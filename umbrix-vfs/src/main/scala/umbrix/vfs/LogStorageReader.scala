package umbrix.vfs

import java.lang.foreign.MemorySegment
import java.nio.file.Path

/**
 * Port interface for low-level, zero-copy off-heap storage access.
 *
 * Decouples indexing and search engines from concrete OS storage mechanics.
 * Invariant: Operates strictly on off-heap `MemorySegment` bytes without heap allocations.
 */
trait LogStorageReader extends AutoCloseable {

  def path: Path

  def sizeBytes: Long

  def readSlice(offset: Long, length: Long): MemorySegment

  def readSlice(offset: Long, length: Long, arena: java.lang.foreign.Arena): MemorySegment = {
    readSlice(offset, length)
  }

  def readInto(offset: Long, length: Long, destination: MemorySegment): Long = {
    val memorySlice = readSlice(offset, length)
    val bytesToCopy = math.min(memorySlice.byteSize(), length)
    MemorySegment.copy(memorySlice, 0L, destination, 0L, bytesToCopy)
    bytesToCopy
  }

  def prefetch(offset: Long, length: Long): Unit = ()

  def evict(offset: Long, length: Long): Unit = ()

  def trimWorkingSet(): Unit = ()

  def close(): Unit
}

object LogStorageReader {

  def open(path: Path, isLive: Boolean = false): LogStorageReader = {
    if (isLive) {
      PositionalLogReader.open(path)
    } else {
      MemoryMappedLogReader.open(path)
    }
  }
}
