package umbrix.vfs.platform

import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Dynamic native I/O factory selecting the optimal positional handle:
 * Win32 Overlapped on Windows, POSIX pread on Linux/macOS, with automatic NIO fallback.
 */
object NativeIo {

  private val logger = LoggerFactory.getLogger(getClass)

  def openPositional(path: Path): PositionalIoHandle = {
    if (Platform.isWindows && Win32PositionalHandle.isAvailable) {
      try {
        logger.debug("Opening [{}] via Win32PositionalHandle", path)
        Win32PositionalHandle.open(path)
      } catch {
        case nativeError: Throwable =>
          logger.warn(s"Failed to open [$path] via Win32 downcalls; falling back to NioPositionalHandle", nativeError)
          NioPositionalHandle.open(path)
      }
    } else if (Platform.isPosix && PosixPositionalHandle.isAvailable) {
      try {
        logger.debug("Opening [{}] via PosixPositionalHandle", path)
        PosixPositionalHandle.open(path)
      } catch {
        case nativeError: Throwable =>
          logger.warn(s"Failed to open [$path] via POSIX downcalls; falling back to NioPositionalHandle", nativeError)
          NioPositionalHandle.open(path)
      }
    } else {
      logger.debug("Opening [{}] via universal NioPositionalHandle", path)
      NioPositionalHandle.open(path)
    }
  }
}
