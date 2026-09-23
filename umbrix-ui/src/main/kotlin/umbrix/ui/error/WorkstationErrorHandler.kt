package umbrix.ui.error

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.slf4j.LoggerFactory
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.diagnostics.ForensicErrorReport
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import javax.swing.JOptionPane

/**
 * Global workstation error management handler providing uncaught exception catching,
 * forensic report persistence, user-facing error banners, and fatal JVM termination guards.
 */
object WorkstationErrorHandler {
    private val logger = LoggerFactory.getLogger(WorkstationErrorHandler::class.java)

    @Volatile
    var isShuttingDown: Boolean = false

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    var activeError: ForensicErrorReport? by mutableStateOf(null)

    fun install() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = originalHandler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleSafely(thread, throwable, originalHandler)
        }
    }

    fun uninstall() {
        isShuttingDown = true
        try {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        } catch (_: Throwable) {}
    }

    fun reportException(
        throwable: Throwable,
        context: ForensicReportContext = ForensicReportContext.empty()
    ): ForensicErrorReport {
        val report = ForensicErrorReportGenerator.generateReport(throwable, Thread.currentThread(), context)
        if (!report.isFatal() && !isShuttingDown) {
            try {
                activeError = report
            } catch (_: Throwable) {}
        }
        return report
    }

    private fun handleSafely(
        thread: Thread,
        throwable: Throwable,
        originalHandler: Thread.UncaughtExceptionHandler?
    ) {
        // If the application is already shutting down, suppress teardown noise
        if (isShuttingDown) {
            return
        }

        // Suppress standard coroutine cancellation or thread interruption during shutdown
        if (throwable is java.util.concurrent.CancellationException || throwable is InterruptedException) {
            return
        }

        try {
            handleUncaughtException(thread, throwable, originalHandler)
        } catch (handlerError: Throwable) {
            try {
                System.err.println("[WorkstationErrorHandler] Exception inside uncaught exception handler on thread ${thread.name}: ${handlerError.message}")
                handlerError.printStackTrace(System.err)
                System.err.println("[WorkstationErrorHandler] Original intercepted throwable was:")
                throwable.printStackTrace(System.err)
            } catch (_: Throwable) {}
        }
    }

    private fun handleUncaughtException(
        thread: Thread,
        throwable: Throwable,
        originalHandler: Thread.UncaughtExceptionHandler?
    ) {
        val fatal = ForensicErrorReportGenerator.isFatal(throwable)
        val report = ForensicErrorReportGenerator.generateReport(throwable, thread, ForensicReportContext.empty())

        if (fatal) {
            try {
                logger.error("CRITICAL FATAL JVM ERROR: Process state corrupted. Showing emergency dialog before exit.", throwable)
            } catch (_: Throwable) {}

            try {
                val reportPathStr = if (report.hasReportFile()) report.reportFilePathString() else "Memory only"
                val message = """
                    CRITICAL FATAL JVM ERROR: ${throwable.javaClass.name}
                    ${throwable.message ?: "No error details available."}

                    The workstation has encountered an unrecoverable JVM error (such as OutOfMemoryError) and must terminate to avoid silent data corruption.

                    Diagnostic report has been generated:
                    $reportPathStr
                """.trimIndent()

                if (!isShuttingDown && !java.awt.GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(
                        null,
                        message,
                        "Umbrix Workstation - Fatal JVM Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            } catch (_: Throwable) {
                // Ignore if GUI cannot render dialog under severe memory exhaustion or window disposal
            }

            if (originalHandler != null) {
                try {
                    originalHandler.uncaughtException(thread, throwable)
                } catch (_: Throwable) {}
            } else {
                Runtime.getRuntime().halt(1)
            }
        } else {
            try {
                logger.error("Intercepted unhandled workstation exception: ${throwable.message}", throwable)
            } catch (_: Throwable) {}

            if (!isShuttingDown) {
                try {
                    activeError = report
                } catch (_: Throwable) {
                    // Ignore snapshot update if Compose runtime is disposing
                }
            }
        }
    }
}
