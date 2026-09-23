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

    logger.info("Initializing Umbrix Workstation...")
    logger.info(s"Runtime Java Version: ${System.getProperty("java.version")}")
    val simdWidth = try { VectorScanner.vectorByteWidth } catch { case _: Throwable => 0 }
    logger.info(s"Detected SIMD Vector Register Width: $simdWidth bytes")

    if (args.contains("--headless")) {
      logger.info("Running in headless mode. GUI will not be launched.")
    } else {
      logger.info("Launching Desktop UI with Compose & Jewel dark theme...")
      try {
        DesktopAppLauncher.launch(args)
      } finally {
        logger.info("Umbrix Workstation terminated cleanly.")
        System.exit(0)
      }
    }
  }
}
