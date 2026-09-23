package umbrix.vfs

import java.io.IOException
import java.lang.foreign.{Arena, MemorySegment}
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import umbrix.vfs.platform.PagingAdvisory

/**
 * Path A: Static log reader using Java 25 FFM continuous memory mapping.
 *
 * Provides instantaneous O(1) zero-copy pointer slicing into the OS page cache
 * with zero JVM heap ingestion. Closing the reader deterministically unmaps
 * all virtual memory pages and releases file locks immediately via `arena.close()`.
 */
final class MemoryMappedLogReader private (
    val path: Path,
    val sizeBytes: Long,
    private val mappedSegment: MemorySegment,
    private val sharedArena: Arena,
    private val fileChannel: FileChannel
) extends LogStorageReader {

  @volatile private var isReaderClosed: Boolean = false

  def readSlice(offset: Long, length: Long): MemorySegment = {
    ensureOpen()
    validateBounds(offset, length)
    if (length == 0L) {
      MemorySegment.NULL
    } else {
      mappedSegment.asSlice(offset, length)
    }
  }

  override def readInto(offset: Long, length: Long, destination: MemorySegment): Long = {
    ensureOpen()
    validateBounds(offset, length)
    if (length == 0L) {
      0L
    } else {
      require(
        destination.byteSize() >= length,
        s"Destination segment size (${destination.byteSize()}) is smaller than read length ($length)"
      )
      MemorySegment.copy(mappedSegment, offset, destination, 0L, length)
      length
    }
  }

  override def prefetch(offset: Long, length: Long): Unit = {
    if (!isReaderClosed && length > 0L && offset >= 0L && offset < sizeBytes) {
      val prefetchedLength = math.min(length, sizeBytes - offset)
      PagingAdvisory.advise(mappedSegment.asSlice(offset, prefetchedLength))
    }
  }

  override def evict(offset: Long, length: Long): Unit = {
    if (!isReaderClosed && length > 0L && offset >= 0L && offset < sizeBytes) {
      val evictedLength = math.min(length, sizeBytes - offset)
      PagingAdvisory.evict(mappedSegment.asSlice(offset, evictedLength))
    }
  }

  override def trimWorkingSet(): Unit = {
    PagingAdvisory.trimWorkingSet()
  }

  def close(): Unit = {
    if (!isReaderClosed) {
      isReaderClosed = true
      try {
        PagingAdvisory.trimWorkingSet()
        sharedArena.close()
      } finally {
        fileChannel.close()
      }
    }
  }

  private def ensureOpen(): Unit = {
    if (isReaderClosed) {
      throw new IllegalStateException(s"MemoryMappedLogReader for [$path] is already closed")
    }
  }

  private def validateBounds(offset: Long, length: Long): Unit = {
    if (offset < 0L) {
      throw new IndexOutOfBoundsException(s"Negative offset: $offset")
    }
    if (length < 0L) {
      throw new IllegalArgumentException(s"Negative length: $length")
    }
    if (offset + length > sizeBytes) {
      throw new IndexOutOfBoundsException(
        s"Requested range [$offset, ${offset + length}) exceeds file size $sizeBytes for [$path]"
      )
    }
  }
}

object MemoryMappedLogReader {

  def open(path: Path): MemoryMappedLogReader = {
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    val fileSize = channel.size()
    val sharedArena = Arena.ofShared()
    try {
      val memorySegment =
        if (fileSize == 0L) {
          MemorySegment.NULL
        } else {
          channel.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize, sharedArena)
        }

      new MemoryMappedLogReader(path, fileSize, memorySegment, sharedArena, channel)
    } catch {
      case fatalError: Throwable =>
        try {
          sharedArena.close()
        } finally {
          channel.close()
        }
        throw fatalError
    }
  }
}
