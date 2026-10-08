package umbrix.index

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import munit.FunSuite
import umbrix.vfs.LogStorageReader

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

class PersistentIndexCacheTest extends FunSuite {

  test("PersistentIndexCache should serialize, deserialize, and accurately match indices") {
    val tempFile: Path = Files.createTempFile("umbrix-cache-test", ".log")
    try {
      val sampleContent = (1 to 500).map(i => s"2026-03-30 10:15:22.00$i [main] INFO Worker thread batch event payload #$i").mkString("\n")
      Files.write(tempFile, sampleContent.getBytes(StandardCharsets.UTF_8))

      val reader: LogStorageReader = LogStorageReader.open(tempFile, false)
      try {
        val builtIndex: TwoLevelSparseIndex = TwoLevelSparseIndex.build(reader)
        assertEquals(builtIndex.totalLinesCount, 500L)

        // Save to cache
        PersistentIndexCache.save(tempFile, reader, builtIndex)

        // Attempt load from cache
        val loadedOpt: Option[TwoLevelSparseIndex] = PersistentIndexCache.tryLoad(tempFile, reader)
        assert(loadedOpt.isDefined, "Cached index should be successfully loaded")

        val loadedIndex = loadedOpt.get
        assertEquals(loadedIndex.totalLinesCount, builtIndex.totalLinesCount)
        assertEquals(loadedIndex.superStrideOffsets.size(), builtIndex.superStrideOffsets.size())
        assertEquals(loadedIndex.intraStrideDeltas.size(), builtIndex.intraStrideDeltas.size())

        // Verify offset lookups match exactly
        var line: Long = 0L
        while (line < 500L) {
          val origOffset = builtIndex.byteOffsetForLine(umbrix.core.types.LineIndex(line), reader)
          val loadedOffset = loadedIndex.byteOffsetForLine(umbrix.core.types.LineIndex(line), reader)
          assertEquals(loadedOffset, origOffset)
          line += 17L
        }
      } finally {
        reader.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("PersistentIndexCache should clean stale cache files beyond threshold") {
    val cacheDir = PersistentIndexCache.getCacheDirectory
    val dummyStale = cacheDir.resolve("dummy-stale-test-cache.idx")
    Files.write(dummyStale, "dummy".getBytes(StandardCharsets.UTF_8))
    // Set timestamp to 8 days ago
    val eightDaysAgo = System.currentTimeMillis() - (8L * 24L * 3600L * 1000L)
    Files.setLastModifiedTime(dummyStale, java.nio.file.attribute.FileTime.fromMillis(eightDaysAgo))

    val cleaned = PersistentIndexCache.cleanStaleCaches(7L * 24L * 3600L * 1000L)
    assert(cleaned >= 1, "Should have cleaned at least 1 stale cache file")
    assert(!Files.exists(dummyStale), "Stale cache file should have been deleted")
  }
}
