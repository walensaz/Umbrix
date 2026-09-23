package umbrix.ui.launcher

import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.search.buildHighlightedLine
import umbrix.ui.search.extractSearchNeedles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkstationErrorHandlerTest {

  @Test
  fun testNonFatalExceptionRecordedAndSetsActiveError() {
    WorkstationErrorHandler.activeError = null

    val syntheticException = IllegalStateException("FocusRequester is not initialized")
    val report = WorkstationErrorHandler.reportException(syntheticException)

    assertFalse(report.isFatal())
    assertEquals("java.lang.IllegalStateException", report.exceptionClassName())
    assertEquals("FocusRequester is not initialized", report.errorMessage())
    assertNotNull(WorkstationErrorHandler.activeError)
    assertEquals(report.reportId(), WorkstationErrorHandler.activeError?.reportId())

    // Verify dismissal
    WorkstationErrorHandler.activeError = null
    assertNull(WorkstationErrorHandler.activeError)
  }

  @Test
  fun testFatalErrorDistinctionGuards() {
    val oom = OutOfMemoryError("Simulated heap exhaustion")
    assertTrue(ForensicErrorReportGenerator.isFatal(oom))

    val normalException = IllegalArgumentException("Bad input")
    assertFalse(ForensicErrorReportGenerator.isFatal(normalException))
  }

  @Test
  fun testExtractSearchNeedles() {
    val needles1 = extractSearchNeedles("timeout")
    assertEquals(listOf("timeout"), needles1)

    val needles2 = extractSearchNeedles("level:ERROR AND timeout")
    assertEquals(listOf("ERROR", "timeout"), needles2)

    val needles3 = extractSearchNeedles("\"database deadlock\"")
    assertEquals(listOf("database deadlock"), needles3)

    val empty = extractSearchNeedles("   ")
    assertTrue(empty.isEmpty())
  }

  @Test
  fun testBuildHighlightedLine() {
    val rawText = "2026-09-22 ERROR Database deadlock detected in table orders"
    val needles = listOf("deadlock", "orders")

    val annotated = buildHighlightedLine(rawText, needles, isMatch = true, isActiveMatch = false)
    assertEquals(rawText, annotated.text)
    // Span styles should have 2 highlight spans
    assertEquals(2, annotated.spanStyles.size)
    assertEquals(rawText.indexOf("deadlock"), annotated.spanStyles[0].start)
    assertEquals(rawText.indexOf("deadlock") + "deadlock".length, annotated.spanStyles[0].end)
    assertEquals(rawText.indexOf("orders"), annotated.spanStyles[1].start)
    assertEquals(rawText.indexOf("orders") + "orders".length, annotated.spanStyles[1].end)

    // Non match should return unstyled annotated string
    val nonMatch = buildHighlightedLine(rawText, needles, isMatch = false, isActiveMatch = false)
    assertTrue(nonMatch.spanStyles.isEmpty())
  }

  @Test
  fun testShuttingDownSuppressesActiveErrorMutation() {
    WorkstationErrorHandler.activeError = null
    WorkstationErrorHandler.isShuttingDown = true

    val exception = RuntimeException("Teardown event on disposed window")
    val report = WorkstationErrorHandler.reportException(exception)

    assertNotNull(report)
    assertNull(WorkstationErrorHandler.activeError, "When isShuttingDown is true, activeError should not be mutated")

    // Reset
    WorkstationErrorHandler.isShuttingDown = false
  }

  @Test
  fun testInstallAndUninstallRestoresPreviousHandler() {
    val dummyHandler = Thread.UncaughtExceptionHandler { _, _ -> }
    Thread.setDefaultUncaughtExceptionHandler(dummyHandler)

    WorkstationErrorHandler.install()
    val installedHandler = Thread.getDefaultUncaughtExceptionHandler()
    assertTrue(installedHandler != dummyHandler)

    WorkstationErrorHandler.uninstall()
    val restoredHandler = Thread.getDefaultUncaughtExceptionHandler()
    assertEquals(dummyHandler, restoredHandler)
    assertTrue(WorkstationErrorHandler.isShuttingDown)

    // Reset
    WorkstationErrorHandler.isShuttingDown = false
    Thread.setDefaultUncaughtExceptionHandler(null)
  }
}
