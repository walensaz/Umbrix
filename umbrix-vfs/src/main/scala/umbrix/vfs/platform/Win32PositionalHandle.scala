package umbrix.vfs.platform

import java.io.IOException
import java.lang.foreign.*
import java.lang.invoke.{MethodHandle, VarHandle}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Windows NT non-locking positional I/O handle via Panama downcalls.
 *
 * Opens files with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`
 * and issues 64-bit `OVERLAPPED` positional `ReadFile` downcalls, completely
 * eliminating `ERROR_SHARING_VIOLATION` (32) during active log rotation.
 */
final class Win32PositionalHandle private (
    val path: Path,
    private val fileHandle: MemorySegment
) extends PositionalIoHandle {

  import Win32PositionalHandle.*

  private val logger = LoggerFactory.getLogger(getClass)
  @volatile private var isHandleClosed: Boolean = false

  def sizeBytes: Long = {
    ensureOpen()
    try {
      val localArena = Arena.ofConfined()
      try {
        val capturedState = localArena.allocate(captureStateLayout)
        val fileSizePointer = localArena.allocate(ValueLayout.JAVA_LONG)
        val queryResult = getFileSizeExHandle.invoke(capturedState, fileHandle, fileSizePointer).asInstanceOf[Int]
        if (queryResult == 0) {
          val errorCode = extractLastError(capturedState)
          throw new IOException(s"GetFileSizeEx failed for [$path] with Win32 error $errorCode")
        }
        fileSizePointer.get(ValueLayout.JAVA_LONG, 0)
      } finally {
        localArena.close()
      }
    } catch {
      case ioException: IOException => throw ioException
      case fatalError: Throwable    => throw new IOException(s"Failed to query size for [$path]", fatalError)
    }
  }

  def read(destination: MemorySegment, fileOffset: Long, length: Long): Int = {
    ensureOpen()
    require(length >= 0L, s"Read length cannot be negative: $length")
    require(fileOffset >= 0L, s"File offset cannot be negative: $fileOffset")
    require(destination.byteSize() >= length, s"Destination segment too small: ${destination.byteSize()} < $length")

    if (length == 0L) {
      0
    } else {
      val requestedByteCount = math.min(length, Int.MaxValue.toLong).toInt
      val localArena = Arena.ofConfined()
      var overlappedEventHandle: MemorySegment = MemorySegment.NULL
      try {
        val createEventState = localArena.allocate(captureStateLayout)
        overlappedEventHandle = createEventWHandle
          .invoke(createEventState, MemorySegment.NULL, 1, 0, MemorySegment.NULL)
          .asInstanceOf[MemorySegment]

        if (overlappedEventHandle.address() == 0L) {
          val eventErrorCode = extractLastError(createEventState)
          throw new IOException(s"CreateEventW failed with Win32 error $eventErrorCode")
        }

        val overlappedStruct = localArena.allocate(32, 8)
        overlappedStruct.set(ValueLayout.JAVA_INT, 16, (fileOffset & 0xffffffffL).toInt)
        overlappedStruct.set(ValueLayout.JAVA_INT, 20, (fileOffset >>> 32).toInt)
        overlappedStruct.set(ValueLayout.ADDRESS, 24, overlappedEventHandle)

        val transferredBytesPointer = localArena.allocate(ValueLayout.JAVA_INT)
        val targetDestinationSlice = destination.asSlice(0L, requestedByteCount.toLong)

        val readCallState = localArena.allocate(captureStateLayout)
        val initialReadStatus = readFileHandle
          .invoke(readCallState, fileHandle, targetDestinationSlice, requestedByteCount, transferredBytesPointer, overlappedStruct)
          .asInstanceOf[Int]

        val transferResult = {
          if (initialReadStatus == 0) {
            val readErrorCode = extractLastError(readCallState)
            if (readErrorCode == ERROR_HANDLE_EOF) {
              -1
            } else if (readErrorCode == ERROR_IO_PENDING) {
              val overlappedCallState = localArena.allocate(captureStateLayout)
              val overlappedStatus = getOverlappedResultHandle
                .invoke(overlappedCallState, fileHandle, overlappedStruct, transferredBytesPointer, 1)
                .asInstanceOf[Int]
              if (overlappedStatus == 0) {
                val overlappedErrorCode = extractLastError(overlappedCallState)
                if (overlappedErrorCode == ERROR_HANDLE_EOF) {
                  -1
                } else {
                  throw new IOException(s"GetOverlappedResult failed for [$path] with Win32 error $overlappedErrorCode")
                }
              } else {
                transferredBytesPointer.get(ValueLayout.JAVA_INT, 0)
              }
            } else {
              throw new IOException(s"ReadFile failed for [$path] with Win32 error $readErrorCode")
            }
          } else {
            transferredBytesPointer.get(ValueLayout.JAVA_INT, 0)
          }
        }

        if (transferResult == 0 && requestedByteCount > 0) -1 else transferResult
      } catch {
        case ioException: IOException => throw ioException
        case fatalError: Throwable    => throw new IOException(s"Positional read failed for [$path] at offset $fileOffset", fatalError)
      } finally {
        if (overlappedEventHandle.address() != 0L) {
          val closeEventState = localArena.allocate(captureStateLayout)
          val _ = closeHandleHandle.invoke(closeEventState, overlappedEventHandle)
        }
        localArena.close()
      }
    }
  }

  def close(): Unit = {
    if (!isHandleClosed) {
      isHandleClosed = true
      val localArena = Arena.ofConfined()
      try {
        val closeCallState = localArena.allocate(captureStateLayout)
        val closeResult = closeHandleHandle.invoke(closeCallState, fileHandle).asInstanceOf[Int]
        if (closeResult == 0) {
          val closeErrorCode = extractLastError(closeCallState)
          logger.warn(s"CloseHandle on [$path] returned Win32 error $closeErrorCode")
        }
      } catch {
        case error: Throwable =>
          logger.error(s"Error closing Win32 file handle for [$path]", error)
      } finally {
        localArena.close()
      }
    }
  }

  private def ensureOpen(): Unit = {
    if (isHandleClosed) {
      throw new IllegalStateException(s"Handle for file [$path] is already closed")
    }
  }
}

object Win32PositionalHandle {

  private val GENERIC_READ = 0x80000000
  private val FILE_SHARE_READ = 0x00000001
  private val FILE_SHARE_WRITE = 0x00000002
  private val FILE_SHARE_DELETE = 0x00000004
  private val OPEN_EXISTING = 3
  private val FILE_FLAG_OVERLAPPED = 0x40000000
  private val FILE_ATTRIBUTE_NORMAL = 0x00000080

  private val ERROR_HANDLE_EOF = 38
  private val ERROR_IO_PENDING = 997

  private val nativeLinker: Linker = Linker.nativeLinker()
  private lazy val kernel32Library: SymbolLookup = SymbolLookup.libraryLookup("kernel32", Arena.global())

  private val captureStateLayout: StructLayout = Linker.Option.captureStateLayout()
  private val getLastErrorVarHandle: VarHandle =
    captureStateLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"))

  private def extractLastError(capturedState: MemorySegment): Int = {
    getLastErrorVarHandle.get(capturedState, 0L).asInstanceOf[Int]
  }

  lazy val isAvailable: Boolean = {
    try {
      Platform.isWindows &&
      kernel32Library.find("CreateFileW").isPresent &&
      kernel32Library.find("ReadFile").isPresent &&
      kernel32Library.find("GetOverlappedResult").isPresent &&
      kernel32Library.find("GetFileSizeEx").isPresent &&
      kernel32Library.find("CloseHandle").isPresent
    } catch {
      case _: Throwable => false
    }
  }

  private lazy val createFileWHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      kernel32Library.findOrThrow("CreateFileW"),
      FunctionDescriptor.of(
        ValueLayout.ADDRESS,  // HANDLE
        ValueLayout.ADDRESS,  // lpFileName
        ValueLayout.JAVA_INT, // dwDesiredAccess
        ValueLayout.JAVA_INT, // dwShareMode
        ValueLayout.ADDRESS,  // lpSecurityAttributes
        ValueLayout.JAVA_INT, // dwCreationDisposition
        ValueLayout.JAVA_INT, // dwFlagsAndAttributes
        ValueLayout.ADDRESS   // hTemplateFile
      ),
      Linker.Option.captureCallState("GetLastError")
    )
  }

  private lazy val readFileHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      kernel32Library.findOrThrow("ReadFile"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, // BOOL
        ValueLayout.ADDRESS,  // HANDLE
        ValueLayout.ADDRESS,  // lpBuffer
        ValueLayout.JAVA_INT, // nNumberOfBytesToRead
        ValueLayout.ADDRESS,  // lpNumberOfBytesRead
        ValueLayout.ADDRESS   // lpOverlapped
      ),
      Linker.Option.captureCallState("GetLastError")
    )
  }

  private lazy val getOverlappedResultHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      kernel32Library.findOrThrow("GetOverlappedResult"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, // BOOL
        ValueLayout.ADDRESS,  // HANDLE
        ValueLayout.ADDRESS,  // lpOverlapped
        ValueLayout.ADDRESS,  // lpNumberOfBytesTransferred
        ValueLayout.JAVA_INT  // bWait
      ),
      Linker.Option.captureCallState("GetLastError")
    )
  }

  private lazy val getFileSizeExHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      kernel32Library.findOrThrow("GetFileSizeEx"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, // BOOL
        ValueLayout.ADDRESS,  // HANDLE
        ValueLayout.ADDRESS   // lpFileSize
      ),
      Linker.Option.captureCallState("GetLastError")
    )
  }

  private lazy val createEventWHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      kernel32Library.findOrThrow("CreateEventW"),
      FunctionDescriptor.of(
        ValueLayout.ADDRESS,  // HANDLE
        ValueLayout.ADDRESS,  // lpEventAttributes
        ValueLayout.JAVA_INT, // bManualReset
        ValueLayout.JAVA_INT, // bInitialState
        ValueLayout.ADDRESS   // lpName
      ),
      Linker.Option.captureCallState("GetLastError")
    )
  }

  private lazy val closeHandleHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      kernel32Library.findOrThrow("CloseHandle"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, // BOOL
        ValueLayout.ADDRESS   // hObject
      ),
      Linker.Option.captureCallState("GetLastError")
    )
  }

  def open(path: Path): Win32PositionalHandle = {
    val allocationArena = Arena.ofConfined()
    try {
      val widePathAddress = allocationArena.allocateFrom(path.toAbsolutePath.toString, StandardCharsets.UTF_16LE)
      val sharingMode = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE
      val creationFlags = FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED

      val capturedState = allocationArena.allocate(captureStateLayout)
      val handleSegment = createFileWHandle
        .invoke(
          capturedState,
          widePathAddress,
          GENERIC_READ,
          sharingMode,
          MemorySegment.NULL,
          OPEN_EXISTING,
          creationFlags,
          MemorySegment.NULL
        )
        .asInstanceOf[MemorySegment]

      if (handleSegment.address() == -1L || handleSegment.address() == 0L) {
        val creationErrorCode = extractLastError(capturedState)
        throw new IOException(s"CreateFileW failed for [$path] with Win32 error $creationErrorCode")
      }

      new Win32PositionalHandle(path, handleSegment)
    } finally {
      allocationArena.close()
    }
  }
}
