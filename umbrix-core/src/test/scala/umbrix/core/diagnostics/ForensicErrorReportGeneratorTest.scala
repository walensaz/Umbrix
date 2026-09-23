package umbrix.core.diagnostics

import munit.FunSuite
import java.io.File
import java.nio.file.Files

class ForensicErrorReportGeneratorTest extends FunSuite {

  test("isFatal correctly distinguishes fatal JVM corruption from catchable exceptions") {
    val oom = new OutOfMemoryError("Simulated heap exhaustion")
    val stackOverflow = new StackOverflowError("Simulated recursive call overflow")
    val linkage = new LinkageError("Simulated native symbol link failure")
    val runtimeEx = new IllegalStateException("FocusRequester is not initialized")
    val ioEx = new java.io.IOException("File not found")

    assert(ForensicErrorReportGenerator.isFatal(oom), "OutOfMemoryError must be identified as fatal")
    assert(ForensicErrorReportGenerator.isFatal(stackOverflow), "StackOverflowError must be identified as fatal")
    assert(ForensicErrorReportGenerator.isFatal(linkage), "LinkageError must be identified as fatal")
    assert(!ForensicErrorReportGenerator.isFatal(runtimeEx), "IllegalStateException must be identified as non-fatal")
    assert(!ForensicErrorReportGenerator.isFatal(ioEx), "IOException must be identified as non-fatal")
  }

  test("generateReport creates complete forensic diagnostics and persists to disk") {
    val syntheticException = new IllegalStateException("Test synthetic exception for diagnostic pipeline")
    val context = ForensicReportContext(
      activeFilePath = Some("C:/logs/production.log"),
      fileSizeBytes = Some(1024L * 1024L * 50L),
      totalLinesCount = Some(500_000L),
      currentLineIndex = Some(12_345L)
    )

    val report = ForensicErrorReportGenerator.generateReport(syntheticException, Thread.currentThread(), context)

    assertEquals(report.isFatal, false)
    assertEquals(report.exceptionClassName, "java.lang.IllegalStateException")
    assertEquals(report.errorMessage, "Test synthetic exception for diagnostic pipeline")
    assert(report.reportId.nonEmpty)
    assert(report.simdVectorByteWidth > 0, "SIMD vector width should be recorded")
    assert(report.stackTraceText.contains("Test synthetic exception for diagnostic pipeline"))

    // Verify summary formatting
    val summary = report.formattedSummaryText
    assert(summary.contains("UMBRIX INDUSTRIAL LOG WORKSTATION - FORENSIC DIAGNOSTIC REPORT"))
    assert(summary.contains("[NON-FATAL EXCEPTION - WORKSTATION RECOVERED]"))
    assert(summary.contains("Active File:         C:/logs/production.log"))

    // Verify JSON serialization
    val json = report.toJson
    assert(json.contains("\"exceptionClassName\": \"java.lang.IllegalStateException\""))
    assert(json.contains("\"isFatal\": false"))

    // Verify report was written to disk
    assert(report.reportFilePath.isDefined, "Report file path should be populated")
    val file = new File(report.reportFilePath.get)
    assert(file.exists(), s"Persisted report file must exist on disk: ${file.getAbsolutePath}")
    val content = Files.readString(file.toPath)
    assert(content.contains("Test synthetic exception for diagnostic pipeline"))

    // Cleanup test report file and companion json file
    file.delete()
    val jsonCompanion = new File(file.getAbsolutePath.replace(".txt", ".json"))
    if (jsonCompanion.exists()) {
      jsonCompanion.delete()
    }
  }

  test("generateReport handles null message safely") {
    val exWithoutMsg = new RuntimeException(null: String)
    val report = ForensicErrorReportGenerator.generateReport(exWithoutMsg)
    assert(report.errorMessage.contains("java.lang.RuntimeException"))
    assert(report.formattedSummaryText.nonEmpty)
  }
}
