package umbrix.core.diagnostics

import org.slf4j.LoggerFactory
import umbrix.simd.VectorScanner
import java.io.{File, PrintWriter, StringWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Diagnostic session context capturing the workstation's active document coordinates
 * when an exception is intercepted.
 */
final case class ForensicReportContext(
    activeFilePath: Option[String] = None,
    fileSizeBytes: Option[Long] = None,
    totalLinesCount: Option[Long] = None,
    currentLineIndex: Option[Long] = None
)

object ForensicReportContext {
  def empty: ForensicReportContext = ForensicReportContext(None, None, None, None)

  def fromSession(
      activeFilePath: String,
      fileSizeBytes: Long,
      totalLinesCount: Long,
      currentLineIndex: Long
  ): ForensicReportContext = {
    ForensicReportContext(
      Option(activeFilePath),
      Some(fileSizeBytes),
      Some(totalLinesCount),
      Some(currentLineIndex)
    )
  }
}

/**
 * Immutable forensic error payload containing environment state, memory metrics,
 * causal stack traces, and document coordinates.
 */
final case class ForensicErrorReport(
    reportId: String,
    timestampIso: String,
    isFatal: Boolean,
    exceptionClassName: String,
    errorMessage: String,
    stackTraceText: String,
    threadName: String,
    threadId: Long,
    osName: String,
    osVersion: String,
    osArch: String,
    javaVersion: String,
    javaVendor: String,
    simdVectorByteWidth: Int,
    jvmUsedMemoryBytes: Long,
    jvmTotalMemoryBytes: Long,
    jvmMaxMemoryBytes: Long,
    sessionContext: ForensicReportContext,
    reportFilePath: Option[String]
) {

  def reportFilePathString: String = reportFilePath.getOrElse("Report file not saved to disk")
  def hasReportFile: Boolean = reportFilePath.isDefined

  /** Formats a structured text report suitable for copy-to-clipboard or console review. */
  def formattedSummaryText: String = {
    val fatalTag = if (isFatal) "[FATAL ERROR - PROCESS CORRUPTED]" else "[NON-FATAL EXCEPTION - WORKSTATION RECOVERED]"
    s"""================================================================================
       | UMBRIX INDUSTRIAL LOG WORKSTATION - FORENSIC DIAGNOSTIC REPORT
       | $fatalTag
       |================================================================================
       | Report ID:           $reportId
       | Timestamp:           $timestampIso
       | Exception Class:     $exceptionClassName
       | Message:             $errorMessage
       | Thread:              $threadName (ID: $threadId)
       |
       | ENVIRONMENT STATE:
       | OS:                  $osName ($osVersion, $osArch)
       | JVM:                 $javaVendor JDK $javaVersion
       | SIMD Vector Width:   $simdVectorByteWidth bytes
       | JVM Heap:            ${jvmUsedMemoryBytes / (1024 * 1024)}MB used / ${jvmTotalMemoryBytes / (1024 * 1024)}MB total (${jvmMaxMemoryBytes / (1024 * 1024)}MB max)
       |
       | SESSION CONTEXT:
       | Active File:         ${sessionContext.activeFilePath.getOrElse("None")}
       | File Size:           ${sessionContext.fileSizeBytes.map(size => s"$size bytes").getOrElse("N/A")}
       | Total Lines:         ${sessionContext.totalLinesCount.map(_.toString).getOrElse("N/A")}
       | Current Viewport:    ${sessionContext.currentLineIndex.map(_.toString).getOrElse("N/A")}
       |
       | DIAGNOSTIC STACK TRACE:
       |$stackTraceText
       |================================================================================
       | Report file saved at: ${reportFilePath.getOrElse("Memory only")}
       |================================================================================
       |""".stripMargin
  }

  /** Serializes the diagnostic report into structured JSON format. */
  def toJson: String = {
    def escapeJson(text: String): String = {
      text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    }

    s"""{
       |  "reportId": "${escapeJson(reportId)}",
       |  "timestampIso": "${escapeJson(timestampIso)}",
       |  "isFatal": $isFatal,
       |  "exceptionClassName": "${escapeJson(exceptionClassName)}",
       |  "errorMessage": "${escapeJson(errorMessage)}",
       |  "threadName": "${escapeJson(threadName)}",
       |  "threadId": $threadId,
       |  "osName": "${escapeJson(osName)}",
       |  "osVersion": "${escapeJson(osVersion)}",
       |  "osArch": "${escapeJson(osArch)}",
       |  "javaVersion": "${escapeJson(javaVersion)}",
       |  "javaVendor": "${escapeJson(javaVendor)}",
       |  "simdVectorByteWidth": $simdVectorByteWidth,
       |  "jvmUsedMemoryBytes": $jvmUsedMemoryBytes,
       |  "jvmTotalMemoryBytes": $jvmTotalMemoryBytes,
       |  "jvmMaxMemoryBytes": $jvmMaxMemoryBytes,
       |  "sessionContext": {
       |    "activeFilePath": ${sessionContext.activeFilePath.map(p => s"\"${escapeJson(p)}\"").getOrElse("null")},
       |    "fileSizeBytes": ${sessionContext.fileSizeBytes.map(_.toString).getOrElse("null")},
       |    "totalLinesCount": ${sessionContext.totalLinesCount.map(_.toString).getOrElse("null")},
       |    "currentLineIndex": ${sessionContext.currentLineIndex.map(_.toString).getOrElse("null")}
       |  },
       |  "reportFilePath": ${reportFilePath.map(p => s"\"${escapeJson(p)}\"").getOrElse("null")},
       |  "stackTraceText": "${escapeJson(stackTraceText)}"
       |}""".stripMargin
  }
}

/**
 * Central forensic diagnostic generator capturing unhandled exceptions, JVM environment,
 * SIMD vector hardware metrics, and persisting reports to disk and SLF4J logs.
 */
object ForensicErrorReportGenerator {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Distinguishes fatal JVM errors (e.g. OutOfMemoryError, StackOverflowError) that corrupt
   * process state from catchable/recoverable application exceptions.
   */
  @annotation.nowarn("cat=deprecation")
  def isFatal(throwable: Throwable): Boolean = {
    throwable match {
      case _: VirtualMachineError => true // OutOfMemoryError, StackOverflowError, InternalError
      case _: LinkageError        => true
      case _: ThreadDeath         => true
      case _                      => false
    }
  }

  /**
   * Generates a forensic diagnostic report, writes it to ~/.umbrix/reports/, and logs to SLF4J.
   *
   * Designed to be strictly non-throwing: even if report disk writing fails, the generated
   * in-memory report is preserved and returned safely.
   */
  def generateReport(
      throwable: Throwable,
      thread: Thread = Thread.currentThread(),
      context: ForensicReportContext = ForensicReportContext()
  ): ForensicErrorReport = {
    val reportId = UUID.randomUUID().toString.take(8)
    val now = Instant.now()
    val timestampIso = DateTimeFormatter.ISO_INSTANT.format(now)
    val fatal = isFatal(throwable)

    val stringWriter = new StringWriter()
    val printWriter = new PrintWriter(stringWriter)
    throwable.printStackTrace(printWriter)
    printWriter.flush()
    val stackTrace = stringWriter.toString

    val runtime = Runtime.getRuntime
    val totalMem = runtime.totalMemory()
    val freeMem = runtime.freeMemory()
    val maxMem = runtime.maxMemory()
    val usedMem = totalMem - freeMem

    val errorMsg = Option(throwable.getMessage).getOrElse(s"No message provided by ${throwable.getClass.getName}")

    val baseReport = ForensicErrorReport(
      reportId = reportId,
      timestampIso = timestampIso,
      isFatal = fatal,
      exceptionClassName = throwable.getClass.getName,
      errorMessage = errorMsg,
      stackTraceText = stackTrace,
      threadName = thread.getName,
      threadId = thread.threadId(),
      osName = System.getProperty("os.name", "Unknown OS"),
      osVersion = System.getProperty("os.version", "Unknown Version"),
      osArch = System.getProperty("os.arch", "Unknown Arch"),
      javaVersion = System.getProperty("java.version", "Unknown Java"),
      javaVendor = System.getProperty("java.vendor", "Unknown Vendor"),
      simdVectorByteWidth = try { VectorScanner.vectorByteWidth } catch { case _: Throwable => 0 },
      jvmUsedMemoryBytes = usedMem,
      jvmTotalMemoryBytes = totalMem,
      jvmMaxMemoryBytes = maxMem,
      sessionContext = context,
      reportFilePath = None
    )

    // Persist report to ~/.umbrix/reports/
    val persistedReport = tryPersistReport(baseReport)

    // Log incident asynchronously to SLF4J
    if (fatal) {
      logger.error(
        s"CRITICAL FATAL JVM ERROR [Report: ${persistedReport.reportId}]: ${throwable.getClass.getName}: $errorMsg",
        throwable
      )
    } else {
      logger.error(
        s"Non-fatal application exception caught [Report: ${persistedReport.reportId}]: ${throwable.getClass.getName}: $errorMsg",
        throwable
      )
    }

    persistedReport
  }

  private def tryPersistReport(report: ForensicErrorReport): ForensicErrorReport = {
    try {
      val userHome = System.getProperty("user.home", ".")
      val reportsDir = Paths.get(userHome, ".umbrix", "reports")
      if (!Files.exists(reportsDir)) {
        Files.createDirectories(reportsDir)
      }

      val safeTimestamp = report.timestampIso.replace(':', '-').replace('.', '-')
      val textFileName = s"error_report_${safeTimestamp}_${report.reportId}.txt"
      val jsonFileName = s"error_report_${safeTimestamp}_${report.reportId}.json"

      val textPath = reportsDir.resolve(textFileName)
      val jsonPath = reportsDir.resolve(jsonFileName)

      Files.writeString(textPath, report.formattedSummaryText, StandardCharsets.UTF_8)
      Files.writeString(jsonPath, report.toJson, StandardCharsets.UTF_8)

      report.copy(reportFilePath = Some(textPath.toAbsolutePath.toString))
    } catch {
      case diskException: Throwable =>
        logger.warn(s"Failed to persist forensic error report to disk: ${diskException.getMessage}")
        report
    }
  }
}
