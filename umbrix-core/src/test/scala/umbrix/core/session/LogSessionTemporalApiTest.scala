package umbrix.core.session

import it.unimi.dsi.fastutil.longs.LongArrayList
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import munit.FunSuite
import umbrix.core.classifier.EventBoundaryClassifier
import umbrix.core.search.SimdSearchEngine
import umbrix.core.slicing.LogSlicer
import umbrix.core.temporal.LatencyGapResult
import umbrix.core.types.{EpochMicros, LineIndex}
import umbrix.index.{PhysicalLineIndexerImpl, TemporalCheckpointTable, TwoLevelSparseIndex}
import umbrix.vfs.MemoryMappedLogReader

class LogSessionTemporalApiTest extends FunSuite {

  test("LogSession temporal queries return accurate Kotlin-compatible primitives") {
    val tempFile: File = File.createTempFile("umbrix_session_temporal_test", ".log")
    tempFile.deleteOnExit()

    val logContent: String =
      "2026-09-23 10:00:00 [INFO] Line 1 initialized\n" +
      "2026-09-23 10:00:05 [WARN] Line 2 warning after delay\n" +
      "2026-09-23 10:00:10 [ERROR] Line 3 fatal outage\n"

    Files.write(tempFile.toPath, logContent.getBytes(StandardCharsets.UTF_8))

    val storageReader = MemoryMappedLogReader.open(tempFile.toPath)
    val sparseIndex = TwoLevelSparseIndex.build(storageReader)
    val indexer = new PhysicalLineIndexerImpl(storageReader, sparseIndex)
    val searchEngine = new SimdSearchEngine(storageReader, indexer, None)
    val slicer = new LogSlicer(storageReader, indexer)

    val lines = new LongArrayList()
    val micros = new LongArrayList()
    lines.add(0L)
    micros.add(1000000L) // 1.0s

    lines.add(1L)
    micros.add(6000000L) // 6.0s -> delta 5.0s (gap)

    lines.add(2L)
    micros.add(11000000L) // 11.0s -> delta 5.0s (gap)

    val temporalTable = new TemporalCheckpointTable(lines, micros)
    val temporalFuture = CompletableFuture.completedFuture(temporalTable)

    val gapLines = new LongArrayList()
    val gapDurations = new LongArrayList()
    val gapMap = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap()
    gapLines.add(0L)
    gapDurations.add(5000000L)
    gapMap.put(0L, 5000000L)
    gapLines.add(1L)
    gapDurations.add(5000000L)
    gapMap.put(1L, 5000000L)
    val gapResult = new LatencyGapResult(gapLines, gapDurations, gapMap)
    val gapFuture = CompletableFuture.completedFuture(gapResult)

    val session = new LogSession(
      tempFile.toPath,
      storageReader,
      indexer,
      temporalFuture,
      searchEngine,
      slicer,
      CompletableFuture.completedFuture(EventBoundaryClassifier.unclassified(indexer)),
      10L,
      () => 1.0,
      () => 1.0,
      CompletableFuture.completedFuture(sparseIndex),
      () => 1.0,
      () => storageReader.sizeBytes,
      CompletableFuture.completedFuture(null),
      () => 1.0,
      umbrix.core.profile.JavaJvmProfile,
      gapFuture
    )

    try {
      assertEquals(session.isTemporalDataAvailable, true)
      assertEquals(session.firstTimestamp, Some(1000000L))
      assertEquals(session.lastTimestamp, Some(11000000L))

      // seekNearestLine
      assertEquals(session.seekNearestLine(EpochMicros(1000000L)), LineIndex(0L))
      assertEquals(session.seekNearestLine(EpochMicros(6000000L)), LineIndex(1L))
      assertEquals(session.seekNearestLine(EpochMicros(11000000L)), LineIndex(2L))

      // seekLineRangeBounds returning Array[Long]
      val range = session.seekLineRangeBounds(1000000L, 6000000L)
      assertEquals(range(0), 0L)
      assertEquals(range(1), 1L)
      assertEquals(session.lineCountInTimeRange(1000000L, 6000000L), 2L)

      // Latency gaps
      assertEquals(session.hasLatencyGapAt(0L), true)
      assertEquals(session.latencyGapDurationAt(0L), 5000000L)
      assertEquals(session.hasLatencyGapAt(1L), true)
      assertEquals(session.latencyGapDurationAt(1L), 5000000L)
      assertEquals(session.hasLatencyGapAt(2L), false)
      assertEquals(session.latencyGapDurationAt(2L), 0L)
    } finally {
      session.close()
    }
  }
}
