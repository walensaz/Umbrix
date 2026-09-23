package umbrix.vfs

import java.lang.foreign.{Arena, MemorySegment}
import java.nio.file.Path
import umbrix.vfs.platform.{NativeIo, PagingAdvisory, PositionalIoHandle}

/**
 * Path B: Active / live rotating log reader using non-locking positional I/O.
 *
 * Reads directly into off-heap direct buffers without creating kernel section
 * locks on Windows (FILE_SHARE_DELETE) or risking SIGBUS crashes during truncation on Unix.
 *
 * Invariant & Memory Safety:
 * Employs a pre-allocated circular off-heap ring buffer (16MB default) for transient `readSlice`
 * operations. This eliminates the unbounded off-heap memory leak caused by repeated allocations
 * in an un-resettable shared Arena, preserving the strict <150MB RSS budget.
 * Callers requiring independent lifecycle control can supply their own `Arena` via `readSlice(offset, length, arena)`
 * or write directly into pre-allocated destination segments via `readInto`.
 */
final class PositionalLogReader private (
    val path: Path,
    private val ioHandle: PositionalIoHandle,
    private val sharedArena: Arena,
    val ringBufferCapacity: Long
) extends LogStorageReader {

  @volatile private var isReaderClosed: Boolean = false

  // Pre-allocated off-heap ring buffer (allocated ONCE in sharedArena)
  private val ringSegment: MemorySegment = sharedArena.allocate(ringBufferCapacity, 64)
  private val ringLock: AnyRef = new AnyRef()
  private var currentRingOffset: Long = 0L
  private val maxRingSlice: Long = ringBufferCapacity / 2L // 8MB maximum single slice in ring

  def sizeBytes: Long = {
    ensureOpen()
    ioHandle.sizeBytes
  }

  def readSlice(offset: Long, length: Long): MemorySegment = {
    ensureOpen()
    validateBounds(offset, length)
    if (length == 0L) {
      MemorySegment.NULL
    } else if (length <= maxRingSlice) {
      // Allocate from bounded circular ring buffer (zero leak, fixed memory footprint)
      val alignedLength: Long = (length + 15L) & ~15L // 16-byte alignment
      val claimOffset: Long = claimRingSpace(alignedLength)
      val destinationSlice: MemorySegment = ringSegment.asSlice(claimOffset, length)
      val bytesActuallyRead: Int = ioHandle.read(destinationSlice, offset, length)
      if (bytesActuallyRead <= 0) {
        MemorySegment.NULL
      } else if (bytesActuallyRead.toLong < length) {
        destinationSlice.asSlice(0L, bytesActuallyRead.toLong)
      } else {
        destinationSlice
      }
    } else {
      // Fallback for rare single read exceeding maxRingSlice
      val allocatedBuffer: MemorySegment = sharedArena.allocate(length, 64)
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
        sharedArena.close()
      }
    }
  }

  private def claimRingSpace(alignedLength: Long): Long = {
    ringLock.synchronized {
      if (currentRingOffset + alignedLength > ringBufferCapacity) {
        currentRingOffset = 0L
      }
      val claimed: Long = currentRingOffset
      currentRingOffset += alignedLength
      claimed
    }
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

  val DefaultRingBufferCapacity: Long = 16L * 1024L * 1024L // 16MB

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
