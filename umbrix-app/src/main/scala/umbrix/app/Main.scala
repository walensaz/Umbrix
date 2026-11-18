package umbrix.app

import org.slf4j.LoggerFactory
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.simd.VectorScanner
import umbrix.ui.launcher.DesktopAppLauncher

/**
 * Entry point and composition root for the Umbrix Workstation platform.
 *
 * === Architectural Bootstrap Lifecycle ===
 * The application initialization follows a strict sequenced lifecycle:
 *
 * 1. '''Bootstrap Exception Interception & Telemetry:'''
 *    Installs an initial [[java.lang.Thread.UncaughtExceptionHandler]] across all JVM threads before
 *    any UI or subsystem initialization. Fatal runtime anomalies (e.g. [[java.lang.OutOfMemoryError]],
 *    [[java.lang.VirtualMachineError]], or native FFM segmentation errors) trigger immediate structured
 *    incident analysis via [[umbrix.core.diagnostics.ForensicErrorReportGenerator]] followed by safe fail-stop
 *    process termination (`Runtime.getRuntime.halt(1)`), preventing corrupted data writes or zombie off-heap allocations.
 *    Non-fatal exceptions are captured and routed through async SLF4J logging.
 *
 * 2. '''Runtime Diagnostics & Dynamic Logging Configuration:'''
 *    Inspects command-line arguments and environment variables (`--debug`, `-v`, `--verbose`, `UMBRIX_DEBUG`)
 *    to elevate root Logback loggers dynamically to `DEBUG` without requiring JVM restarts or XML reconfiguration.
 *
 * 3. '''Hardware & Platform Capability Probing:'''
 *    Logs JVM runtime environment information and interrogates the host CPU register width via
 *    [[umbrix.simd.VectorScanner.vectorByteWidth]], identifying AVX-512 (64B), AVX2 (32B), ARM NEON (16B),
 *    or scalar fallback availability for hardware-accelerated log delimiter scanning and line indexing.
 *
 * 4. '''Clean Shutdown Coordination:'''
 *    Registers a JVM shutdown hook to cleanly flush and terminate active SLF4J / Logback logger contexts,
 *    ensuring buffered diagnostic telemetry is written to disk before OS process exit.
 *
 * 5. '''Execution Mode Routing:'''
 *    - '''Headless CLI Mode (`--headless`):''' Runs in non-interactive batch/forensic mode. Storage readers,
 *      SIMD indexes, or embedded services operate without instantiating Skia / Compose Desktop GUI dependencies.
 *    - '''Compose Desktop GUI Mode (Default):''' Boots the full reactive Skia / Jetpack Compose Desktop interface
 *      via [[umbrix.ui.launcher.DesktopAppLauncher.launch]], initializing the 64-bit viewport, multi-tab docking framework,
 *      and Jewel dark styling.
 */
object Main {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Install initial bootstrap uncaught exception handler for pre-UI initialization
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) => {
      try {
        val report = ForensicErrorReportGenerator.generateReport(throwable, thread)
        if (ForensicErrorReportGenerator.isFatal(throwable)) {
          logger.error(s"CRITICAL FATAL JVM ERROR in thread ${thread.getName}: ${throwable.getMessage}", throwable)
          Runtime.getRuntime.halt(1)
        } else {
          logger.error(s"Uncaught application exception in thread ${thread.getName}: ${throwable.getMessage}", throwable)
        }
      } catch {
        case handlerError: Throwable =>
          try {
            System.err.println(s"[Main] Uncaught error inside bootstrap handler for thread ${thread.getName}: ${handlerError.getMessage}")
            handlerError.printStackTrace(System.err)
            System.err.println("[Main] Original intercepted throwable:")
            throwable.printStackTrace(System.err)
          } catch {
            case _: Throwable => ()
          }
      }
    })

    val isDebugMode = args.contains("--debug") || args.contains("-v") || args.contains("--verbose") || System.getenv("UMBRIX_DEBUG") != null
    if (isDebugMode) {
      try {
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) match {
          case rootLogback: ch.qos.logback.classic.Logger =>
            rootLogback.setLevel(ch.qos.logback.classic.Level.DEBUG)
            logger.info("Debug logging enabled via command line flag / environment variable.")
          case _ => ()
        }
      } catch {
        case debugConfigError: Throwable =>
          logger.warn(s"Unable to dynamically elevate root log level: ${debugConfigError.getMessage}")
      }
    }

    logger.info("Initializing Umbrix Workstation...")
    logger.info(s"Runtime Java Version: ${System.getProperty("java.version")}")
    val simdWidth = try { VectorScanner.vectorByteWidth } catch { case _: Throwable => 0 }
    logger.info(s"Detected SIMD Vector Register Width: $simdWidth bytes")

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      flushLoggers()
    }))

    if (args.contains("--headless")) {
      logger.info("Running in headless mode. GUI will not be launched.")
      flushLoggers()
    } else {
      logger.info("Launching Desktop UI with Compose & Jewel dark theme...")
      try {
        DesktopAppLauncher.launch(args)
      } finally {
        logger.info("Umbrix Workstation terminated cleanly.")
        flushLoggers()
        System.exit(0)
      }
    }
  }

  private def flushLoggers(): Unit = {
    try {
      LoggerFactory.getILoggerFactory match {
        case loggerContext: ch.qos.logback.classic.LoggerContext => loggerContext.stop()
        case _ => ()
      }
    } catch {
      case _: Throwable => ()
    }
  }
}
