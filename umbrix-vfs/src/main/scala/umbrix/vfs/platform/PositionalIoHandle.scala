package umbrix.vfs.platform

import java.lang.foreign.MemorySegment

/**
 * Low-level positional I/O handle abstraction for native and NIO adapters.
 *
 * Invariant: Reads are positional and thread-safe without mutating shared file pointers.
 */
trait PositionalIoHandle extends AutoCloseable {

  def sizeBytes: Long

  def read(destination: MemorySegment, fileOffset: Long, length: Long): Int

  def close(): Unit
}
