package umbrix.app

import org.slf4j.LoggerFactory
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.simd.VectorScanner
import umbrix.ui.launcher.DesktopAppLauncher

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
