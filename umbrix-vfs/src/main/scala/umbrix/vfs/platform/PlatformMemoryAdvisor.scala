package umbrix.vfs.platform

import java.lang.management.ManagementFactory
import org.slf4j.LoggerFactory

/**
 * System hardware and memory profiling advisor.
 *
 * Detects physical host memory and platform CPU topology to compute adaptive,
 * memory-safe off-heap streaming buffer budgets and worker thread concurrency.
 *
 * Invariant: Prevents Umbrix from exceeding ~30-40% host system resource consumption
 * on lower-memory machines (e.g. 8GB or 16GB laptops), avoiding OS pagefile thrashing.
 */
object PlatformMemoryAdvisor {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Default fallback memory size (16 GB) if platform MXBean cannot be queried. */
  private final val DefaultHostMemoryBytes: Long = 16L * 1024L * 1024L * 1024L

  /** Minimum safe streaming buffer size (16 MB) for constrained systems (< 8 GB RAM). */
  final val MinimumStreamingBufferBytes: Long = 16L * 1024L * 1024L

  /** Standard streaming buffer size (32 MB) for typical workstations (8 GB - 24 GB RAM). */
  final val StandardStreamingBufferBytes: Long = 32L * 1024L * 1024L

  /** High-performance streaming buffer size (64 MB) for large workstations (>= 32 GB RAM). */
  final val MaximumStreamingBufferBytes: Long = 64L * 1024L * 1024L

  /**
   * Resolves the total physical system RAM in bytes via the operating system MXBean.
   */
  def totalPhysicalMemoryBytes: Long = {
    try {
      val osBean = ManagementFactory.getOperatingSystemMXBean
      osBean match {
        case sunOsBean: com.sun.management.OperatingSystemMXBean =>
          sunOsBean.getTotalMemorySize
        case _ =>
          DefaultHostMemoryBytes
      }
    } catch {
      case mxBeanError: Throwable =>
        logger.debug("Failed to query physical memory via OperatingSystemMXBean, using default budget", mxBeanError)
        DefaultHostMemoryBytes
    }
  }

  /**
   * Resolves the available free physical system RAM in bytes.
   */
  def freePhysicalMemoryBytes: Long = {
    try {
      val osBean = ManagementFactory.getOperatingSystemMXBean
      osBean match {
        case sunOsBean: com.sun.management.OperatingSystemMXBean =>
          sunOsBean.getFreeMemorySize
        case _ =>
          DefaultHostMemoryBytes / 2L
      }
    } catch {
      case _: Throwable =>
        DefaultHostMemoryBytes / 2L
    }
  }

  /**
   * Computes an optimal, bounded streaming buffer capacity in bytes based on physical memory.
   */
  def recommendedStreamingBufferBytes: Long = {
    val totalMemory: Long = totalPhysicalMemoryBytes
    if (totalMemory < 8L * 1024L * 1024L * 1024L) {
      MinimumStreamingBufferBytes // 16 MB on low-memory machines
    } else if (totalMemory < 32L * 1024L * 1024L * 1024L) {
      StandardStreamingBufferBytes // 32 MB on standard machines
    } else {
      MaximumStreamingBufferBytes // 64 MB on high-end machines
    }
  }

  /**
   * Computes recommended worker thread count for background tasks, reserving at least
   * 1 platform core for the OS kernel and Compose Desktop UI rendering thread.
   */
  def recommendedBackgroundWorkerCount: Int = {
    val availableCores: Int = Runtime.getRuntime.availableProcessors()
    if (availableCores > 1) {
      availableCores - 1
    } else {
      1
    }
  }
}
