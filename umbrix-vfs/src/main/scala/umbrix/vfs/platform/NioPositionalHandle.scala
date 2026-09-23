package umbrix.vfs.platform

import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}

/**
 * Universal positional I/O handle using Java NIO `FileChannel`.
 *
 * Projects destination `MemorySegment` into a direct `ByteBuffer` to perform
 * zero-heap kernel reads (`FileChannel.read(dst, position)`). Serves as the
 * bedrock fallback ensuring 100% stability across all OS and JVM versions.
 */
final class NioPositionalHandle private (
    val path: Path,
    private val channel: FileChannel
) extends PositionalIoHandle {

  @volatile private var isHandleClosed: Boolean = false

  def sizeBytes: Long = {
    ensureOpen()
    channel.size()
  }

  def read(destination: MemorySegment, fileOffset: Long, length: Long): Int = {
    ensureOpen()
    require(length >= 0L, s"Read length cannot be negative: $length")
    require(fileOffset >= 0L, s"File offset cannot be negative: $fileOffset")
    require(destination.byteSize() >= length, s"Destination segment too small: ${destination.byteSize()} < $length")

    if (length == 0L) {
      0
    } else {
      val directByteBuffer = destination.asSlice(0L, length).asByteBuffer()
      channel.read(directByteBuffer, fileOffset)
    }
  }

  def close(): Unit = {
    if (!isHandleClosed) {
      isHandleClosed = true
      channel.close()
    }
  }

  private def ensureOpen(): Unit = {
    if (isHandleClosed) {
      throw new IllegalStateException(s"Handle for file [$path] is already closed")
    }
  }
}

object NioPositionalHandle {
  def open(path: Path): NioPositionalHandle = {
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    new NioPositionalHandle(path, channel)
  }
}
