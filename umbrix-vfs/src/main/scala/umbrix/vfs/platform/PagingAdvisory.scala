package umbrix.vfs.platform

import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import org.slf4j.LoggerFactory

/**
 * Cross-platform asynchronous OS page cache readahead advisory bridge.
 *
 * Pre-warms the OS page cache for sequential scans via Win32 `PrefetchVirtualMemory`
 * or POSIX `madvise(MADV_WILLNEED)`, with non-blocking fallback to `MemorySegment.load()`.
 */
object PagingAdvisory {

  private val logger = LoggerFactory.getLogger(getClass)
  private val nativeLinker: Linker = Linker.nativeLinker()

  private lazy val win32PrefetchHandle: Option[MethodHandle] = {
    try {
      if (Platform.isWindows) {
        val kernel32Library = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val prefetchSymbol = kernel32Library.find("PrefetchVirtualMemory")
        if (prefetchSymbol.isPresent) {
          Some(
            nativeLinker.downcallHandle(
              prefetchSymbol.get(),
              FunctionDescriptor.of(
                ValueLayout.JAVA_INT,  // BOOL
                ValueLayout.ADDRESS,   // HANDLE hProcess
                ValueLayout.JAVA_LONG, // ULONG_PTR NumberOfEntries
                ValueLayout.ADDRESS,   // PWIN32_MEMORY_RANGE_ENTRY
                ValueLayout.JAVA_INT   // ULONG Flags
              )
            )
          )
        } else {
          None
        }
      } else {
        None
      }
    } catch {
      case caughtError: Throwable =>
        logger.debug("Win32 PrefetchVirtualMemory not available", caughtError)
        None
    }
  }

  private lazy val win32CurrentProcessHandle: Option[MethodHandle] = {
    try {
      if (Platform.isWindows) {
        val kernel32Library = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val currentProcessSymbol = kernel32Library.find("GetCurrentProcess")
        if (currentProcessSymbol.isPresent) {
          Some(
            nativeLinker.downcallHandle(
              currentProcessSymbol.get(),
              FunctionDescriptor.of(ValueLayout.ADDRESS)
            )
          )
        } else {
          None
        }
      } else {
        None
      }
    } catch {
      case _: Throwable => None
    }
  }

  private lazy val posixMadviseHandle: Option[MethodHandle] = {
    try {
      if (Platform.isPosix) {
        val defaultSymbolLookup = nativeLinker.defaultLookup()
        val madviseSymbol = defaultSymbolLookup.find("posix_madvise").or(() => defaultSymbolLookup.find("madvise"))
        if (madviseSymbol.isPresent) {
          Some(
            nativeLinker.downcallHandle(
              madviseSymbol.get(),
              FunctionDescriptor.of(
                ValueLayout.JAVA_INT,  // int
                ValueLayout.ADDRESS,   // void *addr
                ValueLayout.JAVA_LONG, // size_t len
                ValueLayout.JAVA_INT   // int advice
              )
            )
          )
        } else {
          None
        }
      } else {
        None
      }
    } catch {
      case caughtError: Throwable =>
        logger.debug("POSIX posix_madvise not available", caughtError)
        None
    }
  }

  private lazy val win32EmptyWorkingSetHandle: Option[MethodHandle] = {
    try {
      if (Platform.isWindows) {
        val kernel32Library = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val emptyWsSymbol = kernel32Library.find("K32EmptyWorkingSet").or { () =>
          try {
            val psapiLibrary = SymbolLookup.libraryLookup("psapi", Arena.global())
            psapiLibrary.find("EmptyWorkingSet")
          } catch {
            case _: Throwable => java.util.Optional.empty()
          }
        }
        if (emptyWsSymbol.isPresent) {
          Some(
            nativeLinker.downcallHandle(
              emptyWsSymbol.get(),
              FunctionDescriptor.of(
                ValueLayout.JAVA_INT, // BOOL
                ValueLayout.ADDRESS   // HANDLE hProcess
              )
            )
          )
        } else {
          None
        }
      } else {
        None
      }
    } catch {
      case caughtError: Throwable =>
        logger.debug("Win32 K32EmptyWorkingSet/EmptyWorkingSet not available", caughtError)
        None
    }
  }

  private lazy val win32SetProcessWorkingSetSizeHandle: Option[MethodHandle] = {
    try {
      if (Platform.isWindows) {
        val kernel32Library = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val symbol = kernel32Library.find("SetProcessWorkingSetSize")
        if (symbol.isPresent) {
          Some(
            nativeLinker.downcallHandle(
              symbol.get(),
              FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG
              )
            )
          )
        } else {
          None
        }
      } else {
        None
      }
    } catch {
      case _: Throwable => None
    }
  }

  private val POSIX_MADV_WILLNEED: Int = 3
  private val POSIX_MADV_DONTNEED: Int = 4

  def advise(targetSegment: MemorySegment): Unit = {
    if (targetSegment.byteSize() > 0L) {
      val handledByWindows = {
        if (Platform.isWindows && win32PrefetchHandle.isDefined && win32CurrentProcessHandle.isDefined) {
          try {
            val localArena = Arena.ofConfined()
            try {
              val processHandle = win32CurrentProcessHandle.get.invoke().asInstanceOf[MemorySegment]
              val memoryRangeEntry = localArena.allocate(16, 8)
              memoryRangeEntry.set(ValueLayout.ADDRESS, 0, targetSegment)
              memoryRangeEntry.set(ValueLayout.JAVA_LONG, 8, targetSegment.byteSize())

              val prefetchStatus = win32PrefetchHandle.get.invoke(processHandle, 1L, memoryRangeEntry, 0).asInstanceOf[Int]
              prefetchStatus != 0
            } finally {
              localArena.close()
            }
          } catch {
            case caughtError: Throwable =>
              logger.debug("Win32 PrefetchVirtualMemory invocation failed, falling back to segment.load()", caughtError)
              false
          }
        } else {
          false
        }
      }

      val handledByPosix = {
        if (!handledByWindows && Platform.isPosix && posixMadviseHandle.isDefined) {
          try {
            val madviseStatus = posixMadviseHandle.get
              .invoke(targetSegment, targetSegment.byteSize(), POSIX_MADV_WILLNEED)
              .asInstanceOf[Int]
            madviseStatus == 0
          } catch {
            case caughtError: Throwable =>
              logger.debug("POSIX madvise invocation failed, falling back to segment.load()", caughtError)
              false
          }
        } else {
          false
        }
      }

      if (!handledByWindows && !handledByPosix) {
        try {
          targetSegment.load()
        } catch {
          case _: UnsupportedOperationException => ()
          case caughtError: Throwable =>
            logger.debug("segment.load() encountered exception", caughtError)
        }
      }
    }
  }

  /**
   * Advises the operating system kernel to evict or release physical memory backing this slice.
   * On POSIX, calls `posix_madvise(MADV_DONTNEED)` or `madvise(MADV_DONTNEED)`.
   */
  def evict(targetSegment: MemorySegment): Unit = {
    if (targetSegment.byteSize() > 0L) {
      if (Platform.isPosix && posixMadviseHandle.isDefined) {
        try {
          posixMadviseHandle.get.invoke(targetSegment, targetSegment.byteSize(), POSIX_MADV_DONTNEED)
        } catch {
          case caughtError: Throwable =>
            logger.debug("POSIX posix_madvise(POSIX_MADV_DONTNEED) invocation failed", caughtError)
        }
      }
    }
  }

  /**
   * Trims the process working set, reclaiming physical pages committed from memory-mapped files.
   * On Windows, invokes `K32EmptyWorkingSet` and `SetProcessWorkingSetSize(-1, -1)` to flush
   * clean mapped pages from the active working set into the standby list, immediately reducing Task Manager RSS.
   */
  def trimWorkingSet(): Unit = {
    if (Platform.isWindows && win32CurrentProcessHandle.isDefined) {
      try {
        val processHandle = win32CurrentProcessHandle.get.invoke().asInstanceOf[MemorySegment]
        if (win32EmptyWorkingSetHandle.isDefined) {
          win32EmptyWorkingSetHandle.get.invoke(processHandle)
        }
        if (win32SetProcessWorkingSetSizeHandle.isDefined) {
          win32SetProcessWorkingSetSizeHandle.get.invoke(processHandle, -1L, -1L)
        }
      } catch {
        case caughtError: Throwable =>
          logger.warn("Win32 working set trim invocation failed: {}", caughtError.getMessage)
      }
    }
  }
}
