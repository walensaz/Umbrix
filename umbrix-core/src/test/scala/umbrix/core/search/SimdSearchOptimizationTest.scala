package umbrix.core.search

import munit.FunSuite
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.model.QueryAst
import umbrix.core.types.{EpochMicros, LineIndex}
import umbrix.index.{PhysicalLineIndexerImpl, TemporalCheckpointTable, TwoLevelSparseIndex}
import umbrix.vfs.LogStorageReader

class SimdSearchOptimizationTest extends FunSuite {

  test("ChunkLineResolver accurately deduplicates multiple matches on the same line and consecutive lines") {
    val tempFile = Files.createTempFile("umbrix-search-opt", ".log")
    try {
      val lines = (0 until 5000).map { i =>
        if (i % 3 == 0) {
          s"2026-03-30 12:00:00.000 [worker-$i] ERROR token token token repeated on line $i"
        } else if (i % 3 == 1) {
          s"2026-03-30 12:00:01.000 [worker-$i] WARN single token on adjacent line $i"
        } else {
          s"2026-03-30 12:00:02.000 [worker-$i] INFO normal message without keyword $i"
        }
      }
      Files.write(tempFile, lines.mkString("\n").getBytes(StandardCharsets.UTF_8))

      val reader = LogStorageReader.open(tempFile, isLive = false)
      val indexer = PhysicalLineIndexerImpl.build(reader)
      val engine = new SimdSearchEngine(reader, indexer)
      try {
        val resultEither = engine.search("\"token\"")
        assert(resultEither.isRight)
        val result = resultEither.toOption.get

        val expectedLines = (0 until 5000).filter(i => i % 3 == 0 || i % 3 == 1).map(_.toLong)
        assertEquals(result.totalMatches, expectedLines.size.toLong)
        expectedLines.foreach { lineIdx =>
          assert(result.matchingLines.contains(lineIdx), s"Missing expected matching line $lineIdx")
        }
      } finally {
        engine.close()
        indexer.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("Compound AND query with TimeRange prunes non-matching chunks and matches exact intersection") {
    val tempFile = Files.createTempFile("umbrix-search-and-pruning", ".log")
    try {
      val totalLines = 20000
      val lines = (0 until totalLines).map { i =>
        val sec = i / 100
        val min = (sec / 60) % 60
        val hr = 10 + (sec / 3600)
        val s = sec % 60
        val severity = if (i % 50 == 0) "ERROR" else "INFO"
        f"2026-03-30 $hr%02d:$min%02d:$s%02d.000 [main] $severity event payload #$i"
      }
      Files.write(tempFile, lines.mkString("\n").getBytes(StandardCharsets.UTF_8))

      val reader = LogStorageReader.open(tempFile, isLive = false)
      val indexer = PhysicalLineIndexerImpl.build(reader)
      val temporalTable = TemporalCheckpointTable.build(indexer)
      val engine = new SimdSearchEngine(reader, indexer, Some(temporalTable))
      try {
        val firstTs = temporalTable.firstTimestamp.get.value
        val lastTs = temporalTable.lastTimestamp.get.value
        val midTs = firstTs + (lastTs - firstTs) / 2L

        val timeRangeAst = QueryAst.TimeRange(Some(firstTs), Some(midTs))
        val literalAst = QueryAst.Literal("ERROR", caseSensitive = true)

        val timeOnlyRes = engine.execute(timeRangeAst)
        val literalOnlyRes = engine.execute(literalAst)
        val combinedRes = engine.execute(QueryAst.And(literalAst, timeRangeAst))

        val expectedIntersection = new org.roaringbitmap.longlong.Roaring64NavigableMap()
        expectedIntersection.or(timeOnlyRes.matchingLines)
        expectedIntersection.and(literalOnlyRes.matchingLines)

        assertEquals(combinedRes.totalMatches, expectedIntersection.getLongCardinality)
        assertEquals(combinedRes.matchingLines, expectedIntersection)
      } finally {
        engine.close()
        indexer.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("PhysicalLineIndexerImpl.getLineText fast-path and control-char sanitization preserve exact output") {
    val tempFile = Files.createTempFile("umbrix-line-text-sanitize", ".log")
    try {
      val content = "Clean ASCII line 0\nLine with\ttab and\rcarriage return\nLine with\u0000null and\u0007bell\n"
      Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8))

      val reader = LogStorageReader.open(tempFile, isLive = false)
      val indexer = PhysicalLineIndexerImpl.build(reader)
      try {
        assertEquals(indexer.getLineText(LineIndex(0L)), "Clean ASCII line 0")
        assertEquals(indexer.getLineText(LineIndex(1L)), "Line with    tab andcarriage return")
        assertEquals(indexer.getLineText(LineIndex(2L)), "Line with null and bell")
      } finally {
        indexer.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("Multi-chunk parallel search accurately resolves absolute offsets across chunk boundaries for literal, case-insensitive, regex, and compound queries") {
    val tempFile = Files.createTempFile("umbrix-multi-chunk-search", ".log")
    try {
      // Create ~320KB file (> 5 chunks at 64KB partitionChunkSizeBytes)
      val totalLines = 4000
      val lines = (0 until totalLines).map { i =>
        val sec = i / 50
        val min = (sec / 60) % 60
        val hr = 10 + (sec / 3600)
        val s = sec % 60
        val marker = if (i % 7 == 0) {
          s"CRITICAL_MULTI_CHUNK_TOKEN_#${i}_END and duplicate CRITICAL_MULTI_CHUNK_TOKEN"
        } else if (i % 13 == 0) {
          s"MixedCase_Target_Needle on line $i"
        } else {
          s"normal padding log message payload for line index $i with extra text to exceed 64KB chunks"
        }
        f"2026-03-30 $hr%02d:$min%02d:$s%02d.000 [worker-$i%04d] INFO $marker"
      }
      Files.write(tempFile, lines.mkString("\n").getBytes(StandardCharsets.UTF_8))

      val reader = LogStorageReader.open(tempFile, isLive = false)
      val indexer = PhysicalLineIndexerImpl.build(reader)
      val temporalTable = TemporalCheckpointTable.build(indexer)
      // Use 64KB partition chunks so 320KB spans 5+ chunks
      val engine = new SimdSearchEngine(
        storageReader = reader,
        lineIndexer = indexer,
        temporalTableOption = Some(temporalTable),
        partitionChunkSizeBytes = 64L * 1024L
      )
      try {
        assert(reader.sizeBytes > 4L * 64L * 1024L, s"Expected file > 256KB to span multiple 64KB chunks, got ${reader.sizeBytes}")

        // 1. Case-sensitive literal across multiple chunks
        val literalRes = engine.execute(QueryAst.Literal("CRITICAL_MULTI_CHUNK_TOKEN", caseSensitive = true))
        val expectedLiteralLines = (0 until totalLines).filter(_ % 7 == 0).map(_.toLong)
        assertEquals(literalRes.totalMatches, expectedLiteralLines.size.toLong)
        expectedLiteralLines.foreach { lineIdx =>
          assert(literalRes.matchingLines.contains(lineIdx), s"Missing multi-chunk literal match at line $lineIdx")
        }

        // 2. Case-insensitive literal across multiple chunks
        val ciRes = engine.execute(QueryAst.Literal("mixedcase_target_needle", caseSensitive = false))
        val expectedCiLines = (0 until totalLines).filter(i => i % 7 != 0 && i % 13 == 0).map(_.toLong)
        assertEquals(ciRes.totalMatches, expectedCiLines.size.toLong)
        expectedCiLines.foreach { lineIdx =>
          assert(ciRes.matchingLines.contains(lineIdx), s"Missing multi-chunk case-insensitive match at line $lineIdx")
        }

        // 3. Regex across multiple chunks
        val regexRes = engine.execute(QueryAst.Regex(com.google.re2j.Pattern.compile("CRITICAL_MULTI_CHUNK_TOKEN_#[0-9]+_END")))
        assertEquals(regexRes.totalMatches, expectedLiteralLines.size.toLong)
        assertEquals(regexRes.matchingLines, literalRes.matchingLines)

        // 4. Compound AND with TimeRange anchored in upper half of file (anchorByteOffset > 0 in later chunks)
        val firstTs = temporalTable.firstTimestamp.get.value
        val lastTs = temporalTable.lastTimestamp.get.value
        val midTs = firstTs + (lastTs - firstTs) / 2L
        val upperHalfTimeRange = QueryAst.TimeRange(Some(midTs), Some(lastTs))

        val timeOnlyRes = engine.execute(upperHalfTimeRange)
        val compoundRes = engine.execute(QueryAst.And(QueryAst.Literal("CRITICAL_MULTI_CHUNK_TOKEN", caseSensitive = true), upperHalfTimeRange))

        val expectedCompound = new org.roaringbitmap.longlong.Roaring64NavigableMap()
        expectedCompound.or(literalRes.matchingLines)
        expectedCompound.and(timeOnlyRes.matchingLines)

        assert(expectedCompound.getLongCardinality > 0L, "Expected non-empty compound matches in second half of file")
        assertEquals(compoundRes.totalMatches, expectedCompound.getLongCardinality)
        assertEquals(compoundRes.matchingLines, expectedCompound)
      } finally {
        engine.close()
        indexer.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
