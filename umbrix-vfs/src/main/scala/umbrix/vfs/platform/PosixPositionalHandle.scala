package umbrix.vfs.platform

import java.io.IOException
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * POSIX positional I/O handle for Linux and macOS via Panama libc downcalls.
 *
 * Uses positional `pread` to eliminate JVM hardware `SIGBUS` crashes when
 * active logs are truncated in-place (e.g. `logrotate copytruncate`).
 */
final class PosixPositionalHandle private (
    val path: Path,
    private val fileDescriptor: Int
) extends PositionalIoHandle {

  import PosixPositionalHandle.*

  private val logger = LoggerFactory.getLogger(getClass)
  @volatile private var isHandleClosed: Boolean = false

  def sizeBytes: Long = {
    ensureOpen()
    try {
      val queriedFileSize = lseekHandle.invoke(fileDescriptor, 0L, SEEK_END).asInstanceOf[Long]
      if (queriedFileSize < 0L) {
        throw new IOException(s"lseek(SEEK_END) failed for [$path] with return code $queriedFileSize")
      }
      queriedFileSize
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
      val requestedByteCount = math.min(length, Int.MaxValue.toLong)
      try {
        val targetDestinationSlice = destination.asSlice(0L, requestedByteCount)
        val bytesTransferred = preadHandle.invoke(fileDescriptor, targetDestinationSlice, requestedByteCount, fileOffset).asInstanceOf[Long]

        if (bytesTransferred < 0L) {
          throw new IOException(s"pread failed for [$path] at offset $fileOffset with return code $bytesTransferred")
        } else if (bytesTransferred == 0L) {
          -1
        } else {
          bytesTransferred.toInt
        }
      } catch {
        case ioException: IOException => throw ioException
        case fatalError: Throwable    => throw new IOException(s"Positional pread failed for [$path] at offset $fileOffset", fatalError)
      }
    }
  }

  def close(): Unit = {
    if (!isHandleClosed) {
      isHandleClosed = true
      try {
        val closeResult = closeHandle.invoke(fileDescriptor).asInstanceOf[Int]
        if (closeResult != 0) {
          logger.warn(s"POSIX close on [$path] returned code $closeResult")
        }
      } catch {
        case error: Throwable =>
          logger.error(s"Error closing POSIX file descriptor for [$path]", error)
      }
    }
  }

  private def ensureOpen(): Unit = {
    if (isHandleClosed) {
      throw new IllegalStateException(s"Handle for file [$path] is already closed")
    }
  }
}

object PosixPositionalHandle {

  private val O_RDONLY = 0
  private val SEEK_END = 2

  private val nativeLinker: Linker = Linker.nativeLinker()

  private lazy val libcLibrary: SymbolLookup = {
    val defaultLookup = nativeLinker.defaultLookup()
    if (Platform.isLinux) {
      try {
        SymbolLookup.libraryLookup("c", Arena.global()).or(defaultLookup)
      } catch {
        case _: Throwable => defaultLookup
      }
    } else if (Platform.isMacOS) {
      try {
        val systemDynamicLibrary = SymbolLookup.libraryLookup("libSystem.B.dylib", Arena.global())
        systemDynamicLibrary.or(defaultLookup)
      } catch {
        case _: Throwable =>
          try {
            SymbolLookup.libraryLookup("System", Arena.global()).or(defaultLookup)
          } catch {
            case _: Throwable => defaultLookup
          }
      }
    } else {
      defaultLookup
    }
  }

  lazy val isAvailable: Boolean = {
    try {
      Platform.isPosix &&
      libcLibrary.find("open").isPresent &&
      libcLibrary.find("pread").isPresent &&
      libcLibrary.find("lseek").isPresent &&
      libcLibrary.find("close").isPresent
    } catch {
      case _: Throwable => false
    }
  }

  private lazy val openHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      libcLibrary.findOrThrow("open"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, // int (fd)
        ValueLayout.ADDRESS,  // const char *pathname
        ValueLayout.JAVA_INT  // int flags
      )
    )
  }

  private lazy val preadHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      libcLibrary.findOrThrow("pread"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_LONG, // ssize_t
        ValueLayout.JAVA_INT,  // int fd
        ValueLayout.ADDRESS,   // void *buf
        ValueLayout.JAVA_LONG, // size_t count
        ValueLayout.JAVA_LONG  // off_t offset
      )
    )
  }

  private lazy val lseekHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      libcLibrary.findOrThrow("lseek"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_LONG, // off_t
        ValueLayout.JAVA_INT,  // int fd
        ValueLayout.JAVA_LONG, // off_t offset
        ValueLayout.JAVA_INT   // int whence
      )
    )
  }

  private lazy val closeHandle: MethodHandle = {
    nativeLinker.downcallHandle(
      libcLibrary.findOrThrow("close"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, // int
        ValueLayout.JAVA_INT  // int fd
      )
    )
  }

  def open(path: Path): PosixPositionalHandle = {
    val allocationArena = Arena.ofConfined()
    try {
      val utf8PathAddress = allocationArena.allocateFrom(path.toAbsolutePath.toString)
      val createdDescriptor = openHandle.invoke(utf8PathAddress, O_RDONLY).asInstanceOf[Int]
      if (createdDescriptor < 0) {
        throw new IOException(s"POSIX open failed for [$path] with fd return $createdDescriptor")
      }
      new PosixPositionalHandle(path, createdDescriptor)
    } finally {
      allocationArena.close()
    }
  }
}
