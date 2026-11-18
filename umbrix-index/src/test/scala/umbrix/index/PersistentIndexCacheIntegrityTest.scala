package umbrix.index

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import munit.FunSuite
import umbrix.vfs.LogStorageReader

class PersistentIndexCacheIntegrityTest extends FunSuite {

  /** Builds and saves an index spanning several superblocks, runs `testBody`, then cleans up. */
  private def withSavedCache(testBody: (Path, LogStorageReader, TwoLevelSparseIndex, Path) => Unit): Unit = {
    val logFile: Path = Files.createTempFile("umbrix-cache-integrity", ".log")
    val content = (1 to 40000).map(i => s"2026-03-30 10:15:22 [main] INFO integrity event #$i").mkString("\n")
    Files.write(logFile, content.getBytes(StandardCharsets.UTF_8))
    val reader: LogStorageReader = LogStorageReader.open(logFile, false)
    val cacheFile: Path = PersistentIndexCache.getCacheFilePath(PersistentIndexCache.computeFingerprint(logFile, reader))
    try {
      val builtIndex = TwoLevelSparseIndex.build(reader)
      PersistentIndexCache.save(logFile, reader, builtIndex)
      assert(Files.exists(cacheFile), "cache file should have been written")
      testBody(logFile, reader, builtIndex, cacheFile)
    } finally {
      reader.close()
      Files.deleteIfExists(cacheFile)
      Files.deleteIfExists(logFile)
    }
  }

  test("an intact cache round-trips") {
    withSavedCache { (logFile, reader, builtIndex, _) =>
      val loaded = PersistentIndexCache.tryLoad(logFile, reader).getOrElse(fail("expected cache hit"))
      assertEquals(loaded.totalLinesCount, builtIndex.totalLinesCount)
      assertEquals(loaded.superStrideOffsets.toLongArray.toList, builtIndex.superStrideOffsets.toLongArray.toList)
      assertEquals(loaded.intraStrideDeltas.toIntArray.toList, builtIndex.intraStrideDeltas.toIntArray.toList)
    }
  }

  test("a truncated cache file is rejected rather than zero-filled") {
    withSavedCache { (logFile, reader, _, cacheFile) =>
      val channel = FileChannel.open(cacheFile, StandardOpenOption.WRITE)
      try channel.truncate(Files.size(cacheFile) - 1000L) finally channel.close()
      assertEquals(PersistentIndexCache.tryLoad(logFile, reader), None)
    }
  }

  test("a flipped byte in the body fails the checksum") {
    withSavedCache { (logFile, reader, _, cacheFile) =>
      val channel = FileChannel.open(cacheFile, StandardOpenOption.READ, StandardOpenOption.WRITE)
      try {
        val corruptionPosition: Long = 36L + 100L
        val original = ByteBuffer.allocate(1)
        channel.read(original, corruptionPosition)
        val flipped = ByteBuffer.wrap(Array((original.get(0) ^ 0x5A).toByte))
        channel.write(flipped, corruptionPosition)
      } finally channel.close()
      assertEquals(PersistentIndexCache.tryLoad(logFile, reader), None)
    }
  }

  test("saving leaves no temporary files behind") {
    withSavedCache { (_, _, _, _) =>
      val leftoverTempFiles = Files.list(PersistentIndexCache.getCacheDirectory)
      try {
        assertEquals(leftoverTempFiles.filter(_.getFileName.toString.startsWith(".idx-")).count(), 0L)
      } finally leftoverTempFiles.close()
    }
  }
}
