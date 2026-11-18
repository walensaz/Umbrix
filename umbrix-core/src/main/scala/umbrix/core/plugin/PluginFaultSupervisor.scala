package umbrix.core.plugin

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import umbrix.core.diagnostics.{ForensicErrorReport, ForensicErrorReportGenerator, ForensicReportContext}

/**
 * Fault containment boundary and circuit breaker for third-party plugins.
 *
 * Catches unhandled plugin exceptions during rendering, format probing, framing, or action execution,
 * logs a structured ForensicDiagnosticReport, trips faulty plugins after repeated failures, and guarantees
 * the workstation process never crashes due to community plugin faults.
 */
class PluginFaultSupervisor(val maxConsecutiveFaults: Int = 3) {

  private val logger = LoggerFactory.getLogger(getClass)
  private val trippedPlugins = new ConcurrentHashMap[String, java.lang.Boolean]()
  private val consecutiveFaultCounts = new ConcurrentHashMap[String, AtomicInteger]()
  private val faultListeners = new CopyOnWriteArrayList[(String, Throwable, ForensicErrorReport) => Unit]()

  def isTripped(pluginId: String): Boolean = {
    trippedPlugins.getOrDefault(pluginId, java.lang.Boolean.FALSE).booleanValue()
  }

  def resetCircuitBreaker(pluginId: String): Unit = {
    trippedPlugins.remove(pluginId)
    consecutiveFaultCounts.remove(pluginId)
  }

  def addFaultListener(listener: (String, Throwable, ForensicErrorReport) => Unit): Unit = {
    faultListeners.add(listener)
  }

  def removeFaultListener(listener: (String, Throwable, ForensicErrorReport) => Unit): Unit = {
    faultListeners.remove(listener)
  }

  /**
   * Executes a plugin operation safely within the fault containment boundary.
   *
   * If the plugin is already tripped, immediately returns `fallbackValue`.
   * If the operation throws a NonFatal exception, generates a ForensicErrorReport,
   * records the fault, trips the plugin if the threshold is reached, and returns `fallbackValue`.
   */
  def executeGuarded[T](
    pluginId: String,
    operationName: String,
    fallbackValue: T
  )(action: => T): T = {
    if (isTripped(pluginId)) {
      fallbackValue
    } else {
      try {
        val result = action
        // Reset fault count on successful invocation
        consecutiveFaultCounts.remove(pluginId)
        result
      } catch {
        case NonFatal(t) =>
          handlePluginFault(pluginId, operationName, t)
          fallbackValue
      }
    }
  }

  private def handlePluginFault(pluginId: String, operationName: String, throwable: Throwable): Unit = {
    val count = consecutiveFaultCounts.computeIfAbsent(pluginId, _ => new AtomicInteger(0)).incrementAndGet()
    val report = ForensicErrorReportGenerator.generateReport(
      throwable = throwable,
      context = ForensicReportContext(activeFilePath = Some(s"plugin:$pluginId operation:$operationName"))
    )

    logger.error(
      s"Plugin '$pluginId' encountered exception in operation '$operationName' (fault $count/$maxConsecutiveFaults): ${throwable.getMessage}",
      throwable
    )

    if (count >= maxConsecutiveFaults) {
      trippedPlugins.put(pluginId, java.lang.Boolean.TRUE)
      logger.warn(
        s"Plugin '$pluginId' tripped the circuit breaker after $count consecutive faults. Further execution disabled until reset."
      )
    }

    val iter = faultListeners.iterator()
    while (iter.hasNext) {
      try {
        iter.next().apply(pluginId, throwable, report)
      } catch {
        case NonFatal(_) => // Ignore listener failure
      }
    }
  }
}
