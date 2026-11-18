package umbrix.core.benchmark

import munit.FunSuite
import java.io.{File, FileOutputStream}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import it.unimi.dsi.fastutil.longs.LongArrayList
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.classifier.EventBoundaryClassifier
import umbrix.core.index.{FusedIndexingResult, FusedStreamingIndexer}
import umbrix.core.search.{SearchResult, SimdSearchEngine}
import umbrix.core.session.LogSession
import umbrix.core.types.{ByteOffset, EpochMicros, LineIndex}
import umbrix.engine.{EngineOptions, LogEngine}
import umbrix.index.{PersistentIndexCache, PhysicalLineIndexerImpl, TemporalCheckpointTable, TwoLevelSparseIndex}
import umbrix.simd.VectorSubstringScanner
import umbrix.vfs.LogStorageReader

class FusedEngineBreakthroughsTest extends FunSuite {

  private def createSyntheticLogFile(lineCount: Int): Path = {
    val tempFile: Path = Files.createTempFile("umbrix-fused-test-", ".log")
    val fos = new FileOutputStream(tempFile.toFile)
    try {
      var i: Int = 0
      while (i < lineCount) {
        val lineStr: String = {
          if (i % 15 == 0) {
            f"2026-10-15 10:00:$i%02d.000 [main] ERROR com.example.Service - Fatal database connection failure $i\n"
          } else if (i % 15 == 1) {
            s"\tat com.example.Service.connect(Service.scala:${i * 10})\n"
          } else if (i % 15 == 2) {
            s"\tat com.example.Service.run(Service.scala:${i * 10 + 5})\n"
          } else if (i % 15 == 3) {
            s"Caused by: java.io.IOException: Network unreachable in step $i\n"
          } else if (i % 15 == 4) {
            s"\t... 12 common frames omitted\n"
          } else if (i % 5 == 0) {
            f"2026-10-15 10:00:$i%02d.500 [worker-1] WARN com.example.Worker - Retrying operation $i\n"
          } else {
            f"2026-10-15 10:00:$i%02d.750 [worker-2] INFO com.example.Worker - Heartbeat acknowledged $i\n"
          }
        }
        fos.write(lineStr.getBytes(StandardCharsets.UTF_8))
        i += 1
      }
    } finally {
      fos.close()
    }
    tempFile
  }

  test("1. FusedStreamingIndexer bit-for-bit parity with legacy TwoLevelSparseIndex + Temporal + EventBoundaryClassifier") {
    val logPath: Path = createSyntheticLogFile(18000) // Exceeds SuperStrideLines (16,384) and TemporalStrideLines (8,192)
    try {
      val storageReader: LogStorageReader = LogStorageReader.open(logPath, isLive = false)
      try {
        // Run legacy multi-pass builders
        val legacySparseIndex: TwoLevelSparseIndex = TwoLevelSparseIndex.buildStreaming(logPath)
        val legacyIndexer = new PhysicalLineIndexerImpl(storageReader, legacySparseIndex)
        val legacyTemporal: TemporalCheckpointTable = TemporalCheckpointTable.build(legacyIndexer)
        val legacyClassifier: EventBoundaryClassifier = EventBoundaryClassifier.buildStreaming(legacyIndexer, logPath)

        // Run single-pass fused indexer
        val fusedResult: FusedIndexingResult = FusedStreamingIndexer.buildStreaming(logPath, storageReader)

        // 1. Verify line count parity
        assertEquals(fusedResult.sparseIndex.totalLinesCount, legacySparseIndex.totalLinesCount)

        // 2. Verify SuperStride and IntraStride tables
        assertEquals(fusedResult.sparseIndex.superStrideOffsets.size(), legacySparseIndex.superStrideOffsets.size())
        assertEquals(fusedResult.sparseIndex.intraStrideDeltas.size(), legacySparseIndex.intraStrideDeltas.size())

        var superIdx: Int = 0
        while (superIdx < legacySparseIndex.superStrideOffsets.size()) {
          assertEquals(
            fusedResult.sparseIndex.superStrideOffsets.getLong(superIdx),
            legacySparseIndex.superStrideOffsets.getLong(superIdx),
            s"Super-stride mismatch at index $superIdx"
          )
          superIdx += 1
        }

        var intraIdx: Int = 0
        while (intraIdx < legacySparseIndex.intraStrideDeltas.size()) {
          assertEquals(
            fusedResult.sparseIndex.intraStrideDeltas.getInt(intraIdx),
            legacySparseIndex.intraStrideDeltas.getInt(intraIdx),
            s"Intra-stride mismatch at index $intraIdx"
          )
          intraIdx += 1
        }

        // 3. Verify sample coordinate seeks
        var lineSample: Long = 0L
        while (lineSample < legacySparseIndex.totalLinesCount) {
          val legacyOffset: ByteOffset = legacySparseIndex.byteOffsetForLine(LineIndex(lineSample), storageReader)
          val fusedOffset: ByteOffset = fusedResult.sparseIndex.byteOffsetForLine(LineIndex(lineSample), storageReader)
          assertEquals(fusedOffset.value, legacyOffset.value, s"Byte offset mismatch at line $lineSample")
          lineSample += 257L
        }

        // 4. Verify TemporalCheckpointTable parity
        assertEquals(fusedResult.temporalTable.checkpointCount, legacyTemporal.checkpointCount)
        var cpIdx: Int = 0
        while (cpIdx < legacyTemporal.checkpointCount) {
          assertEquals(
            fusedResult.temporalTable.lineAtCheckpoint(cpIdx).value,
            legacyTemporal.lineAtCheckpoint(cpIdx).value,
            s"Temporal checkpoint line mismatch at index $cpIdx"
          )
          assertEquals(
            fusedResult.temporalTable.timestampAtCheckpoint(cpIdx).value,
            legacyTemporal.timestampAtCheckpoint(cpIdx).value,
            s"Temporal checkpoint timestamp mismatch at index $cpIdx"
          )
          cpIdx += 1
        }

        // 5. Verify EventBoundaryClassifier continuation bitmap exact equality
        assertEquals(
          fusedResult.continuationBitmap.getLongCardinality,
          legacyClassifier.continuationLinesBitmap.getLongCardinality,
          "Continuation line counts differ"
        )
        assertEquals(
          fusedResult.continuationBitmap,
          legacyClassifier.continuationLinesBitmap,
          "Continuation Roaring64NavigableMap bitmaps are not identical"
        )
      } finally {
        storageReader.close()
      }
    } finally {
      Files.deleteIfExists(logPath)
    }
  }

  test("2. PersistentIndexCache Tier-2 sidecar (.t2) serialization round-trip and CRC32 corruption rejection") {
    val logPath: Path = createSyntheticLogFile(9000)
    try {
      val storageReader: LogStorageReader = LogStorageReader.open(logPath, isLive = false)
      try {
        val fusedResult: FusedIndexingResult = FusedStreamingIndexer.buildStreaming(logPath, storageReader)
        val expectedLines: Long = fusedResult.sparseIndex.totalLinesCount

        // 1. Save Tier-2
        PersistentIndexCache.saveTier2(logPath, storageReader, fusedResult.temporalTable, fusedResult.continuationBitmap)

        // 2. Load Tier-2
        val loadedOpt = PersistentIndexCache.tryLoadTier2(logPath, storageReader, expectedLines)
        assert(loadedOpt.isDefined, "Expected Tier-2 cache hit")
        val loaded = loadedOpt.get

        assertEquals(loaded.temporalTable.checkpointCount, fusedResult.temporalTable.checkpointCount)
        assertEquals(loaded.continuationLinesBitmap.getLongCardinality, fusedResult.continuationBitmap.getLongCardinality)
        assertEquals(loaded.continuationLinesBitmap, fusedResult.continuationBitmap)

        // 3. Corrupt sidecar file and verify CRC32 rejection
        val fingerprint: String = PersistentIndexCache.computeFingerprint(logPath, storageReader)
        val t2File: Path = PersistentIndexCache.getTier2CacheFilePath(fingerprint)
        assert(Files.exists(t2File))

        val bytes = Files.readAllBytes(t2File)
        // Flip one byte in the middle of the body
        val corruptIndex = bytes.length / 2
        bytes(corruptIndex) = (bytes(corruptIndex) ^ 0xFF).toByte
        Files.write(t2File, bytes)

        val corruptedLoad = PersistentIndexCache.tryLoadTier2(logPath, storageReader, expectedLines)
        assertEquals(corruptedLoad, None, "Corrupted .t2 cache file should be rejected via CRC32 checksum mismatch")
      } finally {
        storageReader.close()
      }
    } finally {
      Files.deleteIfExists(logPath)
    }
  }

  test("3. VectorSubstringScanner scanDualLiteral and SimdSearchEngine fused AND/OR parity") {
    val text: String = "alpha beta gamma delta ERROR failed Block ERROR warn ERROR Failed Block"
    val bytes: Array[Byte] = text.getBytes(StandardCharsets.UTF_8)
    val arena = Arena.ofConfined()
    try {
      val segment: MemorySegment = arena.allocate(bytes.length.toLong)
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, segment, 0L, bytes.length.toLong)

      val listA = new LongArrayList()
      val listB = new LongArrayList()
      VectorSubstringScanner.scanDualLiteral(
        segment,
        0L,
        bytes.length.toLong,
        "ERROR".getBytes(StandardCharsets.UTF_8),
        "Block".getBytes(StandardCharsets.UTF_8),
        (o: Long) => listA.add(o),
        (o: Long) => listB.add(o)
      )

      val singleA = new LongArrayList()
      VectorSubstringScanner.scanLiteral(segment, 0L, bytes.length.toLong, "ERROR".getBytes(StandardCharsets.UTF_8), (o: Long) => singleA.add(o))
      val singleB = new LongArrayList()
      VectorSubstringScanner.scanLiteral(segment, 0L, bytes.length.toLong, "Block".getBytes(StandardCharsets.UTF_8), (o: Long) => singleB.add(o))

      assertEquals(listA.toLongArray().toSeq, singleA.toLongArray().toSeq)
      assertEquals(listB.toLongArray().toSeq, singleB.toLongArray().toSeq)

      // Test case-insensitive dual literal
      val listLowerA = new LongArrayList()
      val listLowerB = new LongArrayList()
      VectorSubstringScanner.scanDualLiteralCaseInsensitive(
        segment,
        0L,
        bytes.length.toLong,
        "error".getBytes(StandardCharsets.UTF_8),
        "failed".getBytes(StandardCharsets.UTF_8),
        (o: Long) => listLowerA.add(o),
        (o: Long) => listLowerB.add(o)
      )

      val singleLowerA = new LongArrayList()
      VectorSubstringScanner.scanLiteralCaseInsensitive(segment, 0L, bytes.length.toLong, "error".getBytes(StandardCharsets.UTF_8), (o: Long) => singleLowerA.add(o))
      val singleLowerB = new LongArrayList()
      VectorSubstringScanner.scanLiteralCaseInsensitive(segment, 0L, bytes.length.toLong, "failed".getBytes(StandardCharsets.UTF_8), (o: Long) => singleLowerB.add(o))

      assertEquals(listLowerA.toLongArray().toSeq, singleLowerA.toLongArray().toSeq)
      assertEquals(listLowerB.toLongArray().toSeq, singleLowerB.toLongArray().toSeq)
    } finally {
      arena.close()
    }

    // Now test SimdSearchEngine fused AND and OR on file session
    val logPath: Path = createSyntheticLogFile(5000)
    try {
      val session = LogSession.open(logPath, isLiveStream = false)
      try {
        // Evaluate single literals
        val errorRes = session.search("ERROR").toOption.get
        val warnRes = session.search("WARN").toOption.get

        // Fused OR: "ERROR OR WARN"
        val fusedOrRes = session.search("ERROR OR WARN").toOption.get
        val expectedOr = new Roaring64NavigableMap()
        expectedOr.or(errorRes.matchingLines)
        expectedOr.or(warnRes.matchingLines)
        assertEquals(fusedOrRes.matchingLines, expectedOr, "Fused OR must match bitwise union of single literals")

        // Fused AND: "ERROR AND Service"
        val serviceRes = session.search("Service").toOption.get
        val fusedAndRes = session.search("ERROR AND Service").toOption.get
        val expectedAnd = new Roaring64NavigableMap()
        expectedAnd.or(errorRes.matchingLines)
        expectedAnd.and(serviceRes.matchingLines)
        assertEquals(fusedAndRes.matchingLines, expectedAnd, "Fused AND must match bitwise intersection of single literals")
      } finally {
        session.close()
      }
    } finally {
      Files.deleteIfExists(logPath)
    }
  }

  test("4. LogEngine standalone facade lifecycle") {
    val logPath: Path = createSyntheticLogFile(1000)
    try {
      val engine: LogEngine = LogEngine.open(logPath, EngineOptions.Default)
      try {
        assertEquals(engine.totalLines, 1000L)
        assert(engine.totalBytes > 0L)

        val firstLineText: String = engine.getLineText(0L)
        assert(firstLineText.contains("2026-10-15 10:00:00.000"))

        val firstLineSlice: MemorySegment = engine.getLineSlice(0L)
        assert(firstLineSlice.byteSize() > 0L)

        val searchResultEither = engine.search("ERROR")
        assert(searchResultEither.isRight)
        val searchResult = searchResultEither.toOption.get
        assert(searchResult.totalMatches > 0L)

        // Sub-file export test
        val exportFile: Path = Files.createTempFile("umbrix-export-", ".log")
        try {
          val exportedBytes: Long = engine.exportSlice(0L, 10L, exportFile)
          assert(exportedBytes > 0L)
          assert(Files.size(exportFile) == exportedBytes)
        } finally {
          Files.deleteIfExists(exportFile)
        }
      } finally {
        engine.close()
      }
    } finally {
      Files.deleteIfExists(logPath)
    }
  }
}
