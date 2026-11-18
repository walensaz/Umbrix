package umbrix.core.classifier

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.types.LineIndex
import umbrix.index.PhysicalLineIndexerImpl
import umbrix.vfs.LogStorageReader
import umbrix.vfs.MemoryMappedLogReader

class EventBoundaryClassifierTest extends FunSuite {

  test("EventBoundaryClassifier groups multi-line stack traces under parent event headers") {
    val logWithTraces =
      """2026-09-22 10:00:00 [main] ERROR java.lang.NullPointerException: Null session pointer
        |	at com.umbrix.session.SessionManager.open(SessionManager.scala:42)
        |	at com.umbrix.app.Main.run(Main.scala:18)
        |Caused by: java.lang.IllegalArgumentException: Missing config key
        |	at com.umbrix.config.ConfigReader.load(ConfigReader.scala:10)
        |2026-09-22 10:00:01 [main] INFO Application recovered
        |""".stripMargin

    val tempFile: File = File.createTempFile("umbrix-classifier-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, logWithTraces.getBytes(StandardCharsets.UTF_8))

    val storageReader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(storageReader)
      val classifier: EventBoundaryClassifier = EventBoundaryClassifier.build(indexer)

      // Total physical lines: 6
      assertEquals(indexer.totalLines.value, 6L)
      // Total logical events: 2 (line 0 and line 5)
      assertEquals(classifier.totalEvents, 2L)

      assert(classifier.isEventHeader(LineIndex(0L)))
      assert(!classifier.isEventHeader(LineIndex(1L)))
      assert(!classifier.isEventHeader(LineIndex(2L)))
      assert(!classifier.isEventHeader(LineIndex(3L)))
      assert(!classifier.isEventHeader(LineIndex(4L)))
      assert(classifier.isEventHeader(LineIndex(5L)))

      // Verify parent line resolution
      assertEquals(classifier.parentEventForLine(LineIndex(0L)).value, 0L)
      assertEquals(classifier.parentEventForLine(LineIndex(1L)).value, 0L)
      assertEquals(classifier.parentEventForLine(LineIndex(2L)).value, 0L)
      assertEquals(classifier.parentEventForLine(LineIndex(3L)).value, 0L)
      assertEquals(classifier.parentEventForLine(LineIndex(4L)).value, 0L)
      assertEquals(classifier.parentEventForLine(LineIndex(5L)).value, 5L)

      // Verify event line spans
      assertEquals(classifier.eventLineSpan(LineIndex(0L)), 5L)
      assertEquals(classifier.eventLineSpan(LineIndex(5L)), 1L)
    } finally {
      storageReader.close()
    }
  }
}
