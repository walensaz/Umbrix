package umbrix.vfs

import java.lang.foreign.{Arena, MemorySegment}
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import umbrix.vfs.platform.{NativeIo, PagingAdvisory, PositionalIoHandle}

/**
 * Path B: Active / live rotating log reader using non-locking positional I/O.
 *
 * Reads directly into off-heap direct buffers without creating kernel section
 * locks on Windows (FILE_SHARE_DELETE) or risking SIGBUS crashes during truncation on Unix.
 *
 * Slice lifetime contract:
 * a slice returned by `readSlice(offset, length)` is backed by a scratch buffer private to the
 * calling thread and stays valid until that same thread calls `readSlice` again (or the reader
 * closes). Threads therefore can never overwrite each other's data. Each thread's scratch buffer
 * grows geometrically to the largest read it performed and is released when it is outgrown, when
 * its thread dies, or on `close()`, so native memory is bounded by (live threads x largest read)
 * instead of growing with the number of reads.
 * Callers needing independent lifetimes can use `readSlice(offset, length, arena)` or `readInto`.
 */
final class PositionalLogReader private (
    val path: Path,
    private val ioHandle: PositionalIoHandle,
    private val sharedArena: Arena,
    val ringBufferCapacity: Long
) extends LogStorageReader {

  /** One thread's scratch buffer; its own shared arena lets us free it when outgrown. */
  private final class ThreadScratch(val ownerThread: Thread, val arena: Arena, val segment: MemorySegment)

  @volatile private var isReaderClosed: Boolean = false
  private val scratchByThread: ConcurrentHashMap[Thread, ThreadScratch] = new ConcurrentHashMap[Thread, ThreadScratch]()

  def sizeBytes: Long = {
    ensureOpen()
    ioHandle.sizeBytes
  }

  /** Total native bytes currently held by per-thread scratch buffers (diagnostics and tests). */
  def scratchBytesAllocated: Long = {
    scratchByThread.values().stream().mapToLong(scratch => scratch.segment.byteSize()).sum()
  }

  def readSlice(offset: Long, length: Long): MemorySegment = {
    ensureOpen()
    validateBounds(offset, length)
    if (length == 0L) {
      MemorySegment.NULL
    } else {
      val scratchSegment: MemorySegment = scratchSegmentFor(length)
      val destinationSlice: MemorySegment = scratchSegment.asSlice(0L, length)
      val bytesActuallyRead: Int = ioHandle.read(destinationSlice, offset, length)
      if (bytesActuallyRead <= 0) {
        MemorySegment.NULL
      } else if (bytesActuallyRead.toLong < length) {
        destinationSlice.asSlice(0L, bytesActuallyRead.toLong)
      } else {
        destinationSlice
      }
    }
  }

  override def readSlice(offset: Long, length: Long, arena: Arena): MemorySegment = {
    ensureOpen()
    validateBounds(offset, length)
    if (length == 0L) {
      MemorySegment.NULL
    } else {
      val allocatedBuffer: MemorySegment = arena.allocate(length, 1)
      val bytesActuallyRead: Int = ioHandle.read(allocatedBuffer, offset, length)
      if (bytesActuallyRead <= 0) {
        MemorySegment.NULL
      } else if (bytesActuallyRead.toLong < length) {
        allocatedBuffer.asSlice(0L, bytesActuallyRead.toLong)
      } else {
        allocatedBuffer
      }
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
      val bytesTransferred: Int = ioHandle.read(destination, offset, length)
      if (bytesTransferred < 0) {
        0L
      } else {
        bytesTransferred.toLong
      }
    }
  }

  override def prefetch(offset: Long, length: Long): Unit = ()

  override def trimWorkingSet(): Unit = {
    PagingAdvisory.trimWorkingSet()
  }

  def close(): Unit = {
    if (!isReaderClosed) {
      isReaderClosed = true
      try {
        PagingAdvisory.trimWorkingSet()
        ioHandle.close()
      } finally {
        try {
          scratchByThread.values().forEach(scratch => scratch.arena.close())
          scratchByThread.clear()
        } finally {
          sharedArena.close()
        }
      }
    }
  }

  /** Returns the calling thread's scratch segment, replacing it with a larger one when needed. */
  private def scratchSegmentFor(requiredLength: Long): MemorySegment = {
    val callingThread: Thread = Thread.currentThread()
    val existingScratch: ThreadScratch = scratchByThread.get(callingThread)
    if (existingScratch != null && existingScratch.segment.byteSize() >= requiredLength) {
      existingScratch.segment
    } else {
      releaseScratchOfDeadThreads()
      val newCapacity: Long = nextPowerOfTwo(requiredLength.max(ringBufferCapacity))
      val newArena: Arena = Arena.ofShared()
      val newSegment: MemorySegment = try {
        newArena.allocate(newCapacity, 64)
      } catch {
        case allocationFailure: Throwable =>
          newArena.close()
          throw allocationFailure
      }
      scratchByThread.put(callingThread, new ThreadScratch(callingThread, newArena, newSegment))
      if (existingScratch != null) {
        existingScratch.arena.close()
      }
      newSegment
    }
  }

  private def releaseScratchOfDeadThreads(): Unit = {
    scratchByThread.values().removeIf { scratch =>
      val isDead: Boolean = !scratch.ownerThread.isAlive
      if (isDead) {
        scratch.arena.close()
      }
      isDead
    }
  }

  private def nextPowerOfTwo(value: Long): Long = {
    if (value <= 1L) 1L else java.lang.Long.highestOneBit(value - 1L) << 1
  }

  private def ensureOpen(): Unit = {
    if (isReaderClosed) {
      throw new IllegalStateException(s"PositionalLogReader for [$path] is already closed")
    }
  }

  private def validateBounds(offset: Long, length: Long): Unit = {
    if (offset < 0L) {
      throw new IndexOutOfBoundsException(s"Negative offset: $offset")
    }
    if (length < 0L) {
      throw new IllegalArgumentException(s"Negative length: $length")
    }
  }
}

object PositionalLogReader {

  /** Initial per-thread scratch capacity; larger reads grow it geometrically. */
  val DefaultRingBufferCapacity: Long = 1L * 1024L * 1024L // 1MB

  def open(path: Path, ringBufferCapacity: Long = DefaultRingBufferCapacity): PositionalLogReader = {
    val handle: PositionalIoHandle = NativeIo.openPositional(path)
    val arena: Arena = Arena.ofShared()
    try {
      new PositionalLogReader(path, handle, arena, ringBufferCapacity)
    } catch {
      case fatalError: Throwable =>
        try {
          handle.close()
        } finally {
          arena.close()
        }
        throw fatalError
    }
  }
}
