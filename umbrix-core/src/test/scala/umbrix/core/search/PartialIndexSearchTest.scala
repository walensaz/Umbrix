package umbrix.core.search

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executor
import munit.FunSuite
import umbrix.index.{PhysicalLineIndexerImpl, TwoLevelSparseIndex}
import umbrix.vfs.MemoryMappedLogReader

class PartialIndexSearchTest extends FunSuite {

  test("search is clamped to the indexed region while indexing, then covers the tail once complete") {
    val totalLineCount: Int = 100000
    val content: String = (0 until totalLineCount).map { lineNumber =>
      val marker: String = if (lineNumber == 0 || lineNumber == totalLineCount - 1) "NEEDLE" else "filler"
      f"line-$lineNumber%06d $marker padding-padding-padding"
    }.mkString("", "\n", "\n")
    val logFile = Files.createTempFile("umbrix-partial-", ".log")
    logFile.toFile.deleteOnExit()
    Files.write(logFile, content.getBytes(StandardCharsets.UTF_8))

    // Executor that defers the background phase so the index stays partial (1MB chunk 0 only).
    var deferredBackgroundTask: Runnable = null
    val deferringExecutor: Executor = (task: Runnable) => { deferredBackgroundTask = task }
    var initialSnapshot: TwoLevelSparseIndex = null
    val completionFuture = TwoLevelSparseIndex.buildStreamingProgressive(
      logFile,
      onInitialChunkReady = snapshot => { initialSnapshot = snapshot },
      onChunkUpdate = (_, _, _, _) => (),
      bufferCapacity = 1024L * 1024L,
      executor = new java.util.concurrent.AbstractExecutorService {
        def execute(task: Runnable): Unit = deferringExecutor.execute(task)
        def shutdown(): Unit = ()
        def shutdownNow(): java.util.List[Runnable] = java.util.Collections.emptyList()
        def isShutdown: Boolean = false
        def isTerminated: Boolean = false
        def awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
      }
    )

    val storageReader = MemoryMappedLogReader.open(logFile)
    val indexer = new PhysicalLineIndexerImpl(storageReader, initialSnapshot)
    val searchEngine = new SimdSearchEngine(storageReader, indexer, None, 2)
    try {
      assert(indexer.indexedByteLimit < storageReader.sizeBytes, "index should be partial")

      val partialResult = searchEngine.search("NEEDLE").toOption.get
      assertEquals(partialResult.totalMatches, 1L)
      assert(partialResult.matchingLines.contains(0L))

      deferredBackgroundTask.run()
      indexer.updateSparseIndex(completionFuture.get())

      val completeResult = searchEngine.search("NEEDLE").toOption.get
      assertEquals(completeResult.totalMatches, 2L)
      assert(completeResult.matchingLines.contains((totalLineCount - 1).toLong))
    } finally {
      searchEngine.close()
      storageReader.close()
    }
  }
}
