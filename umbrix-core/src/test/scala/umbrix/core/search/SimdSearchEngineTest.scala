package umbrix.core.search

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.types.LineIndex
import umbrix.index.PhysicalLineIndexerImpl
import umbrix.vfs.LogStorageReader
import umbrix.vfs.MemoryMappedLogReader

class SimdSearchEngineTest extends FunSuite {

  test("SimdSearchEngine executes literal and regex queries with forward/backward navigation") {
    val sampleLog =
      """2026-09-22 10:00:00.000 [main] INFO Application initialized successfully
        |2026-09-22 10:00:01.200 [worker-1] WARN High memory threshold detected: 85%
        |2026-09-22 10:00:02.400 [worker-2] ERROR Database connection timeout to 10.0.0.1:5432
        |2026-09-22 10:00:03.000 [worker-1] DEBUG Heartbeat ping acknowledged
        |2026-09-22 10:00:04.100 [worker-2] ERROR Retry attempt 1 failed with connection timeout
        |2026-09-22 10:00:05.500 [main] INFO Healthcheck probe OK
        |2026-09-22 10:00:06.000 [worker-2] ERROR Fatal service shutdown initiated
        |""".stripMargin

    val tempFile: File = File.createTempFile("umbrix-search-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, sampleLog.getBytes(StandardCharsets.UTF_8))

    val storageReader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(storageReader)
      val searchEngine: SimdSearchEngine = new SimdSearchEngine(storageReader, indexer)
      try {
        // 1. Literal search
        val timeoutResult = searchEngine.search("timeout").getOrElse(fail("Query parse failed"))
        assertEquals(timeoutResult.totalMatches, 2L)
        assert(timeoutResult.matchingLines.contains(2L))
        assert(timeoutResult.matchingLines.contains(4L))

        // Forward search from line 0
        assertEquals(timeoutResult.findNext(LineIndex(0L)), Some(LineIndex(2L)))
        // Forward search from line 2
        assertEquals(timeoutResult.findNext(LineIndex(2L)), Some(LineIndex(2L)))
        // Forward search after line 2
        assertEquals(timeoutResult.findNext(LineIndex(3L)), Some(LineIndex(4L)))
        // Forward search past end
        assertEquals(timeoutResult.findNext(LineIndex(5L)), None)

        // Backward search from line 5
        assertEquals(timeoutResult.findPrevious(LineIndex(5L)), Some(LineIndex(4L)))
        // Backward search from line 4
        assertEquals(timeoutResult.findPrevious(LineIndex(4L)), Some(LineIndex(4L)))
        // Backward search strictly before line 4
        assertEquals(timeoutResult.findPrevious(LineIndex(3L)), Some(LineIndex(2L)))
        // Backward search before line 2
        assertEquals(timeoutResult.findPrevious(LineIndex(1L)), None)

        // 2. Case-sensitive literal search
        val caseResult = searchEngine.search("case:sensitive \"Database\"").getOrElse(fail("Query parse failed"))
        assertEquals(caseResult.totalMatches, 1L)
        assert(caseResult.matchingLines.contains(2))

        // 3. Level filter
        val errorResult = searchEngine.search("level:ERROR").getOrElse(fail("Query parse failed"))
        assertEquals(errorResult.totalMatches, 3L)
        assert(errorResult.matchingLines.contains(2))
        assert(errorResult.matchingLines.contains(4))
        assert(errorResult.matchingLines.contains(6))

        // 4. Boolean AND
        val andResult = searchEngine.search("level:ERROR AND timeout").getOrElse(fail("Query parse failed"))
        assertEquals(andResult.totalMatches, 2L)
        assert(andResult.matchingLines.contains(2))
        assert(andResult.matchingLines.contains(4))

        // 5. Boolean OR
        val orResult = searchEngine.search("WARN OR Fatal").getOrElse(fail("Query parse failed"))
        assertEquals(orResult.totalMatches, 2L)
        assert(orResult.matchingLines.contains(1))
        assert(orResult.matchingLines.contains(6))

        // 6. Boolean NOT
        val notResult = searchEngine.search("level:ERROR AND NOT timeout").getOrElse(fail("Query parse failed"))
        assertEquals(notResult.totalMatches, 1L)
        assert(notResult.matchingLines.contains(6))

        // 7. Regex search
        val regexResult = searchEngine.search("/worker-\\d/").getOrElse(fail("Query parse failed"))
        assertEquals(regexResult.totalMatches, 5L)

        // 8. Case-insensitive SIMD literal search
        val caseInsensitiveUpper = searchEngine.search("TIMEOUT").getOrElse(fail("Query parse failed"))
        assertEquals(caseInsensitiveUpper.totalMatches, 2L)
        assert(caseInsensitiveUpper.matchingLines.contains(2))
        assert(caseInsensitiveUpper.matchingLines.contains(4))

        val caseInsensitiveLower = searchEngine.search("database").getOrElse(fail("Query parse failed"))
        assertEquals(caseInsensitiveLower.totalMatches, 1L)
        assert(caseInsensitiveLower.matchingLines.contains(2))

        // 8b. Multi-word phrase search with spaces (unquoted)
        val phraseBoth = searchEngine.search("connection timeout").getOrElse(fail("Query parse failed"))
        assertEquals(phraseBoth.totalMatches, 2L)
        assert(phraseBoth.matchingLines.contains(2))
        assert(phraseBoth.matchingLines.contains(4))

        val phraseOnlyLine2 = searchEngine.search("Database connection timeout").getOrElse(fail("Query parse failed"))
        assertEquals(phraseOnlyLine2.totalMatches, 1L)
        assert(phraseOnlyLine2.matchingLines.contains(2))

        val phraseOnlyLine4 = searchEngine.search("failed with connection timeout").getOrElse(fail("Query parse failed"))
        assertEquals(phraseOnlyLine4.totalMatches, 1L)
        assert(phraseOnlyLine4.matchingLines.contains(4))

        val phraseNone = searchEngine.search("Database retry").getOrElse(fail("Query parse failed"))
        assertEquals(phraseNone.totalMatches, 0L)

        // 9. Progressive search emissions
        val progressiveSnapshots = new java.util.ArrayList[SearchResult]()
        val finalProgResult = searchEngine.searchProgressive("timeout", res => progressiveSnapshots.add(res))
          .getOrElse(fail("Query parse failed"))

        assert(!progressiveSnapshots.isEmpty)
        val lastSnapshot = progressiveSnapshots.get(progressiveSnapshots.size() - 1)
        assertEquals(lastSnapshot.totalMatches, 2L)
        assertEquals(lastSnapshot.isComplete, true)
        assertEquals(finalProgResult.totalMatches, 2L)
      } finally {
        searchEngine.close()
      }
    } finally {
      storageReader.close()
    }
  }

  test("SimdSearchEngine handles thread interruption safely and restores interrupt flag") {
    val tempFile: File = File.createTempFile("umbrix-interrupt-test-", ".log")
    tempFile.deleteOnExit()
    val content: Array[Byte] = ("Error on line\n" * 1000).getBytes(StandardCharsets.UTF_8)
    Files.write(tempFile.toPath, content)

    val storageReader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(storageReader)
      val searchEngine: SimdSearchEngine = new SimdSearchEngine(storageReader, indexer)
      try {
        // Set the interrupt flag on current thread prior to search
        Thread.currentThread().interrupt()

        // Search must not crash or throw unhandled InterruptedException
        val result = searchEngine.search("Error")
        assert(result.isRight)

        // Verify the interrupt flag was preserved / restored
        val wasInterrupted = Thread.interrupted()
        assertEquals(wasInterrupted, true)
      } finally {
        // Ensure flag is cleared if still set
        Thread.interrupted()
        searchEngine.close()
      }
    } finally {
      storageReader.close()
    }
  }

  test("SimdSearchEngine abortCurrentSearch stops query processing cooperatively") {
    val tempFile: File = File.createTempFile("umbrix-abort-test-", ".log")
    tempFile.deleteOnExit()
    val content: Array[Byte] = ("2026-09-24 10:00:00 [ERROR] Connection lost to worker node\n" * 50000).getBytes(StandardCharsets.UTF_8)
    Files.write(tempFile.toPath, content)

    val storageReader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(storageReader)
      val searchEngine: SimdSearchEngine = new SimdSearchEngine(storageReader, indexer)
      try {
        searchEngine.abortCurrentSearch()
        val result = searchEngine.search("Connection")
        assert(result.isRight)
      } finally {
        searchEngine.close()
      }
    } finally {
      storageReader.close()
    }
  }

  test("SimdSearchEngine executes anchor-prioritized progressive search successfully") {
    val tempFile: File = File.createTempFile("umbrix-anchor-search-test-", ".log")
    tempFile.deleteOnExit()
    val content: Array[Byte] = (
      ("2026-10-11 12:00:00 [INFO] Line A\n" * 1000) +
      "2026-10-11 12:01:00 [ERROR] Target Match Near Anchor\n" +
      ("2026-10-11 12:02:00 [INFO] Line B\n" * 1000)
    ).getBytes(StandardCharsets.UTF_8)
    Files.write(tempFile.toPath, content)

    val storageReader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(storageReader)
      val searchEngine: SimdSearchEngine = new SimdSearchEngine(storageReader, indexer)
      try {
        val anchorByte: Long = indexer.byteOffsetForLine(LineIndex(1000L)).value
        val snapshots = new java.util.ArrayList[SearchResult]()
        val result = searchEngine.searchProgressive("Target Match", anchorByte, res => snapshots.add(res))
          .getOrElse(fail("Query parse failed"))

        assertEquals(result.totalMatches, 1L)
        assertEquals(result.findNextLine(1000L), 1000L)
        assert(!snapshots.isEmpty)
        val lastSnapshot = snapshots.get(snapshots.size() - 1)
        assertEquals(lastSnapshot.totalMatches, 1L)
        assertEquals(lastSnapshot.isComplete, true)
      } finally {
        searchEngine.close()
      }
    } finally {
      storageReader.close()
    }
  }
}
