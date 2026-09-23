package umbrix.core.session

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.types.LineIndex

class LogSessionTest extends FunSuite {

  test("LogSession opens, indexes, searches, filters, and exports sub-files end-to-end") {
    val logContent: String =
      "2026-09-22 12:00:00.000 [INFO] First message\n" +
      "2026-09-22 12:00:01.000 [WARN] High memory pressure detected\n" +
      "2026-09-22 12:00:02.000 [ERROR] Out of memory failure\n" +
      "\tat com.umbrix.Worker.run(Worker.scala:42)\n"

    val tempFile: File = File.createTempFile("umbrix-session-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, logContent.getBytes(StandardCharsets.UTF_8))

    val session: LogSession = LogSession.open(tempFile.toPath)
    try {
      assertEquals(session.totalLines.value, 4L)
      assertEquals(session.fileName, tempFile.getName)
      assert(session.indexingDurationMs >= 0L)
      assert(session.simdVectorWidth > 0)
      assertEquals(session.simdVectorWidth, umbrix.simd.VectorScanner.vectorByteWidth)

      assertEquals(session.getLineText(LineIndex(0L)), "2026-09-22 12:00:00.000 [INFO] First message")
      assertEquals(session.getLineText(LineIndex(1L)), "2026-09-22 12:00:01.000 [WARN] High memory pressure detected")
      assertEquals(session.getLineText(LineIndex(2L)), "2026-09-22 12:00:02.000 [ERROR] Out of memory failure")

      // Progressive Tier 2 background indexing verification
      assert(session.indexingProgressPercent >= 0.0 && session.indexingProgressPercent <= 100.0)
      session.eventClassifierFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
      session.temporalIndexFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
      assertEquals(session.indexingProgressPercent, 100.0)
      assert(session.isIndexingComplete)
      assert(session.isClassifierComplete)
      assert(session.isTemporalIndexComplete)

      // Event classification
      assert(session.isEventHeader(LineIndex(0L)))
      assert(session.isEventHeader(LineIndex(1L)))
      assert(session.isEventHeader(LineIndex(2L)))
      assert(!session.isEventHeader(LineIndex(3L))) // Stack frame continuation line
      assertEquals(session.parentEventForLine(LineIndex(3L)).value, 2L)

      // Search
      val searchResult = session.search("level:ERROR").getOrElse(fail("Search failed"))
      assertEquals(searchResult.totalMatches, 1L)
      assert(searchResult.matchingLines.contains(2))

      // Filtered view
      val filteredView = session.createFilteredView(searchResult.matchingLines)
      assertEquals(filteredView.totalFilteredLines.value, 1L)
      assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(0L)).value, 2L)
      assertEquals(filteredView.virtualIndexForPhysicalLine(LineIndex(2L)).value, 0L)
      assertEquals(filteredView.virtualIndexForPhysicalLine(LineIndex(0L)).value, 0L)

      // Sub-file export
      val subFile: File = File.createTempFile("umbrix-subfile-", ".log")
      subFile.deleteOnExit()
      val sliceResult = session.exportFilteredLines(searchResult.matchingLines, subFile.toPath)
      assertEquals(sliceResult.linesExported, 1L)

      val subSession: LogSession = LogSession.open(subFile.toPath)
      try {
        assertEquals(subSession.totalLines.value, 1L)
        assert(subSession.getLineText(LineIndex(0L)).contains("[ERROR] Out of memory failure"))
      } finally {
        subSession.close()
      }
    } finally {
      session.close()
    }
  }
}
