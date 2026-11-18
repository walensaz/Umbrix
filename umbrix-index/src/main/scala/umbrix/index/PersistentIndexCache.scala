package umbrix.index

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import org.slf4j.LoggerFactory
import umbrix.vfs.LogStorageReader

import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.types.LineIndex

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.zip.CRC32
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * Deserialized Tier-2 metadata containing temporal checkpoints and logical event boundary continuation lines.
 */
final case class CachedTier2Metadata(
  temporalTable: TemporalCheckpointTable,
  continuationLinesBitmap: Roaring64NavigableMap
)

/**
 * Persistent on-disk sidecar cache for TwoLevelSparseIndex instances.
 *
 * Enables sub-millisecond re-indexing of previously processed multi-gigabyte log files
 * by serializing SuperStride and IntraStride coordinate tables to ~/.umbrix/cache/indexes/<fileFingerprint>.idx.
 *
 * Invariants:
 * - Deterministic binary structure with magic signature and versioning.
 * - Keyed by strict composite fingerprint (file size + last modified + header CRC32 checksum).
 * - Automatic 7-day LRU/age-based cache eviction during workstation startup.
 * - 0 bytes of log data ingested into JVM Garbage Collector heap during serialization/deserialization.
 */
object PersistentIndexCache {

  private val logger = LoggerFactory.getLogger(PersistentIndexCache.getClass)

  /** Binary magic signature 'UMBRIXI1' in hex. */
  private val MagicSignature: Long = 0x554D425249584931L
  private val CacheFormatVersion: Int = 2 // v2 adds a CRC32 trailer over both tables
  /** Binary magic signature 'UMBRIXT2' in hex. */
  private val Tier2MagicSignature: Long = 0x554D425249585432L
  private val Tier2CacheFormatVersion: Int = 2
  private val Tier2HeaderBytes: Long = 41L
  /** Bytes of the CRC32 body checksum appended after the tables. */
  private val TrailerBytes: Long = 8L
  private val MinimumCacheFileBytes: Long = 36L + TrailerBytes
  private val HeaderChecksumSampleSize: Long = 65536L // Sample first 64KB for instant header validation
  private val DefaultStaleThresholdMillis: Long = 7L * 24L * 3600L * 1000L // 7 days

  /**
   * Resolves the root cache directory ~/.umbrix/cache/indexes/
   */
  def getCacheDirectory: Path = {
    val userHome: String = Option(System.getProperty("user.home")).getOrElse(".")
    val cacheDir: Path = Paths.get(userHome, ".umbrix", "cache", "indexes")
    if (!Files.exists(cacheDir)) {
      try {
        Files.createDirectories(cacheDir)
      } catch {
        case NonFatal(e) =>
          logger.warn("Failed to create index cache directory {}: {}", cacheDir, e.getMessage)
      }
    }
    cacheDir
  }

  /**
   * Computes a collision-resistant composite fingerprint for a file.
   * Combines file size, modification timestamp, and hardware-accelerated CRC32 checksum of the first 64KB.
   */
  def computeFingerprint(path: Path, storageReader: LogStorageReader): String = {
    val fileSize: Long = if (storageReader != null) storageReader.sizeBytes else Files.size(path)
    val lastMod: Long = try {
      Files.getLastModifiedTime(path).toMillis
    } catch {
      case NonFatal(_) => 0L
    }

    val sampleSize: Long = math.min(HeaderChecksumSampleSize, fileSize)
    val crc = new CRC32()
    var crcComputedFromReader: Boolean = false
    if (storageReader != null && sampleSize > 0L) {
      try {
        val slice: MemorySegment = storageReader.readSlice(0L, sampleSize)
        crc.update(slice.asByteBuffer())
        crcComputedFromReader = true
      } catch {
        case NonFatal(_) =>
          crc.reset()
      }
    }
    if (!crcComputedFromReader && Files.exists(path) && sampleSize > 0L) {
      val channel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
      try {
        val buf: ByteBuffer = ByteBuffer.allocate(sampleSize.toInt)
        channel.read(buf)
        buf.flip()
        crc.update(buf)
      } finally {
        channel.close()
      }
    }

    val headerCrc: Long = crc.getValue
    f"$fileSize%x-$lastMod%x-$headerCrc%x"
  }

  /**
   * Resolves the target cache file path for a given fingerprint.
   */
  def getCacheFilePath(fingerprint: String): Path = {
    getCacheDirectory.resolve(s"$fingerprint.idx")
  }

  /**
   * Resolves the target Tier-2 cache file path for a given fingerprint.
   */
  def getTier2CacheFilePath(fingerprint: String): Path = {
    getCacheDirectory.resolve(s"$fingerprint.t2")
  }

  /**
   * Attempts to deserialize a previously cached TwoLevelSparseIndex for the specified log file.
   * Updates the cache file's last modified timestamp on hit to refresh the 7-day eviction timer.
   *
   * @param path local file path
   * @param storageReader storage reader for fingerprint validation
   * @return Some(TwoLevelSparseIndex) if cache exists, is valid, and matches file fingerprint; None otherwise.
   */
  def tryLoad(path: Path, storageReader: LogStorageReader): Option[TwoLevelSparseIndex] = {
    try {
      if (path == null || !Files.exists(path)) {
        None
      } else {
        val fingerprint: String = computeFingerprint(path, storageReader)
        val cacheFile: Path = getCacheFilePath(fingerprint)

        if (!Files.exists(cacheFile) || Files.size(cacheFile) < MinimumCacheFileBytes) {
          None
        } else {
          val channel: FileChannel = FileChannel.open(cacheFile, StandardOpenOption.READ)
          try {
            readValidatedIndex(channel, Files.size(cacheFile), storageReader.sizeBytes, path, cacheFile)
          } finally {
            channel.close()
          }
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Failed to load index cache for {}: {}", path, e.getMessage)
        None
    }
  }

  /**
   * Strictly validates and decodes a cache file. Any size, count, short-read, checksum, or
   * monotonicity inconsistency yields `None` so a damaged cache can never produce silently wrong line offsets.
   */
  private def readValidatedIndex(
    channel: FileChannel,
    cacheFileBytes: Long,
    expectedLogFileBytes: Long,
    logPath: Path,
    cacheFile: Path
  ): Option[TwoLevelSparseIndex] = {
    val headerBuf: ByteBuffer = ByteBuffer.allocate(36)
    if (!readFully(channel, headerBuf)) {
      None
    } else {
      headerBuf.flip()
      val magic: Long = headerBuf.getLong
      val version: Int = headerBuf.getInt
      val cachedFileSize: Long = headerBuf.getLong
      val totalLinesCount: Long = headerBuf.getLong
      val superCount: Int = headerBuf.getInt
      val intraCount: Int = headerBuf.getInt

      val countsPlausible: Boolean =
        totalLinesCount > 0L &&
          superCount >= 1 && superCount <= 10000000 &&
          intraCount > (superCount - 1) * SparseIndex.IntraEntriesPerSuperblock &&
          intraCount <= superCount * SparseIndex.IntraEntriesPerSuperblock

      if (magic != MagicSignature || version != CacheFormatVersion || cachedFileSize != expectedLogFileBytes) {
        logger.debug("Index cache mismatch or invalid magic for {}", logPath)
        None
      } else if (!countsPlausible) {
        logger.warn("Corrupt index cache entry counts in {}", cacheFile)
        None
      } else if (cacheFileBytes != 36L + superCount.toLong * 8L + intraCount.toLong * 4L + TrailerBytes) {
        logger.warn("Truncated or oversized index cache file {}", cacheFile)
        None
      } else {
        val superBuf: ByteBuffer = ByteBuffer.allocate(superCount * 8)
        val intraBuf: ByteBuffer = ByteBuffer.allocate(intraCount * 4)
        val trailerBuf: ByteBuffer = ByteBuffer.allocate(TrailerBytes.toInt)
        if (!readFully(channel, superBuf) || !readFully(channel, intraBuf) || !readFully(channel, trailerBuf)) {
          logger.warn("Short read while loading index cache {}", cacheFile)
          None
        } else {
          superBuf.flip()
          intraBuf.flip()
          trailerBuf.flip()
          val bodyChecksum = new CRC32()
          bodyChecksum.update(superBuf.duplicate())
          bodyChecksum.update(intraBuf.duplicate())
          if (bodyChecksum.getValue != trailerBuf.getLong) {
            logger.warn("Index cache checksum mismatch in {}", cacheFile)
            None
          } else {
            val superArray = new Array[Long](superCount)
            superBuf.asLongBuffer().get(superArray)
            val intraArray = new Array[Int](intraCount)
            intraBuf.asIntBuffer().get(intraArray)

            if (!validateTableMonotonicity(superArray, intraArray, expectedLogFileBytes)) {
              logger.warn("Index cache failed monotonic offset verification in {}", cacheFile)
              None
            } else {
              // Refresh cache file access time for LRU tracking
              try {
                Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()))
              } catch {
                case NonFatal(_) => ()
              }

              logger.info(
                "Instantly loaded cached index for {} ({} lines, {} superblocks, {} intra entries) from {}",
                logPath.getFileName,
                totalLinesCount,
                superCount,
                intraCount,
                cacheFile.getFileName
              )
              Some(new TwoLevelSparseIndex(LongArrayList.wrap(superArray), IntArrayList.wrap(intraArray), totalLinesCount))
            }
          }
        }
      }
    }
  }

  /**
   * Verifies that deserialized superblock offsets are strictly non-decreasing within `[0, fileSize]`.
   */
  private def validateTableMonotonicity(
    superArray: Array[Long],
    intraArray: Array[Int],
    fileSize: Long
  ): Boolean = {
    if (superArray.isEmpty || superArray(0) != 0L || intraArray.isEmpty || intraArray(0) != 0) {
      false
    } else {
      var prevSuper: Long = 0L
      var sIdx: Int = 1
      var valid: Boolean = true
      while (sIdx < superArray.length && valid) {
        val currSuper: Long = superArray(sIdx)
        if (currSuper <= prevSuper || currSuper > fileSize) {
          valid = false
        }
        prevSuper = currSuper
        sIdx += 1
      }
      valid
    }
  }

  @tailrec
  private def readFully(channel: FileChannel, buffer: ByteBuffer): Boolean = {
    if (!buffer.hasRemaining) {
      true
    } else if (channel.read(buffer) < 0) {
      false
    } else {
      readFully(channel, buffer)
    }
  }

  /**
   * Persists a completed TwoLevelSparseIndex to disk.
   *
   * @param path local file path
   * @param storageReader storage reader for fingerprint generation
   * @param index populated TwoLevelSparseIndex
   */
  def save(path: Path, storageReader: LogStorageReader, index: TwoLevelSparseIndex): Unit = {
    try {
      if (path != null && Files.exists(path) && index != null && index.totalLinesCount > 0L) {
        val fingerprint: String = computeFingerprint(path, storageReader)
        val cacheDir: Path = getCacheDirectory
        val targetFile: Path = cacheDir.resolve(s"$fingerprint.idx")
        // Unique temp name so concurrent Umbrix instances never write the same file.
        val tempFile: Path = Files.createTempFile(cacheDir, ".idx-", ".tmp")

        val superCount: Int = index.superStrideOffsets.size()
        val intraCount: Int = index.intraStrideDeltas.size()
        val totalBytes: Long = 36L + (superCount.toLong * 8L) + (intraCount.toLong * 4L) + TrailerBytes

        val superBuf: ByteBuffer = ByteBuffer.allocate(superCount * 8)
        superBuf.asLongBuffer().put(index.superStrideOffsets.elements(), 0, superCount)
        val intraBuf: ByteBuffer = ByteBuffer.allocate(intraCount * 4)
        intraBuf.asIntBuffer().put(index.intraStrideDeltas.elements(), 0, intraCount)

        val bodyChecksum = new CRC32()
        bodyChecksum.update(superBuf.duplicate())
        bodyChecksum.update(intraBuf.duplicate())

        val headerBuf: ByteBuffer = ByteBuffer.allocate(36)
        headerBuf.putLong(MagicSignature)
        headerBuf.putInt(CacheFormatVersion)
        headerBuf.putLong(storageReader.sizeBytes)
        headerBuf.putLong(index.totalLinesCount)
        headerBuf.putInt(superCount)
        headerBuf.putInt(intraCount)
        headerBuf.flip()

        val trailerBuf: ByteBuffer = ByteBuffer.allocate(TrailerBytes.toInt)
        trailerBuf.putLong(bodyChecksum.getValue)
        trailerBuf.flip()

        try {
          val channel: FileChannel = FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
          try {
            Seq(headerBuf, superBuf, intraBuf, trailerBuf).foreach { buffer =>
              while (buffer.hasRemaining) {
                channel.write(buffer)
              }
            }
          } finally {
            channel.close()
          }

          try {
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          } catch {
            case _: java.nio.file.AtomicMoveNotSupportedException =>
              Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
          }
        } catch {
          case writeFailure: Throwable =>
            Files.deleteIfExists(tempFile)
            throw writeFailure
        }

        logger.info(
          "Persisted index cache for {} ({} lines, {} KB) to {}",
          path.getFileName,
          index.totalLinesCount,
          totalBytes / 1024L,
          targetFile.getFileName
        )
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Failed to persist index cache for {}: {}", path, e.getMessage)
    }
  }

  /**
   * Persists Tier-2 metadata (TemporalCheckpointTable and EventBoundaryClassifier continuation bitmap)
   * to a companion sidecar file ~/.umbrix/cache/indexes/<fileFingerprint>.t2.
   *
   * @param path local file path
   * @param storageReader storage reader for fingerprint generation
   * @param temporalTable populated TemporalCheckpointTable
   * @param continuationBitmap compressed Roaring64NavigableMap holding continuation line indices
   */
  def saveTier2(
    path: Path,
    storageReader: LogStorageReader,
    temporalTable: TemporalCheckpointTable,
    continuationBitmap: Roaring64NavigableMap
  ): Unit = {
    try {
      if (path != null && Files.exists(path) && temporalTable != null && continuationBitmap != null) {
        val fingerprint: String = computeFingerprint(path, storageReader)
        val cacheDir: Path = getCacheDirectory
        val targetFile: Path = getTier2CacheFilePath(fingerprint)
        val tempFile: Path = Files.createTempFile(cacheDir, ".t2-", ".tmp")

        val checkpointCount: Int = temporalTable.checkpointCount
        val linesList: LongArrayList = temporalTable.lineIndices
        val microsList: LongArrayList = temporalTable.epochMicros

        val linesBuf: ByteBuffer = ByteBuffer.allocate(checkpointCount * 8)
        linesBuf.asLongBuffer().put(linesList.elements(), 0, checkpointCount)

        val microsBuf: ByteBuffer = ByteBuffer.allocate(checkpointCount * 8)
        microsBuf.asLongBuffer().put(microsList.elements(), 0, checkpointCount)

        val bitmapByteArrayStream = new java.io.ByteArrayOutputStream()
        val dataOut = new java.io.DataOutputStream(bitmapByteArrayStream)
        continuationBitmap.serialize(dataOut)
        dataOut.flush()
        val bitmapBytes: Array[Byte] = bitmapByteArrayStream.toByteArray
        val bitmapByteSize: Long = bitmapBytes.length.toLong

        val bodyChecksum = new CRC32()
        bodyChecksum.update(linesBuf.duplicate())
        bodyChecksum.update(microsBuf.duplicate())
        bodyChecksum.update(bitmapBytes)

        val headerBuf: ByteBuffer = ByteBuffer.allocate(Tier2HeaderBytes.toInt)
        headerBuf.putLong(Tier2MagicSignature)
        headerBuf.putInt(Tier2CacheFormatVersion)
        headerBuf.putLong(storageReader.sizeBytes)
        headerBuf.putLong(temporalTable.totalLines.value)
        headerBuf.putInt(checkpointCount)
        headerBuf.putLong(bitmapByteSize)
        headerBuf.put(if (temporalTable.hasDateComponent) 1.toByte else 0.toByte)
        headerBuf.flip()

        val trailerBuf: ByteBuffer = ByteBuffer.allocate(TrailerBytes.toInt)
        trailerBuf.putLong(bodyChecksum.getValue)
        trailerBuf.flip()

        val bitmapBuf: ByteBuffer = ByteBuffer.wrap(bitmapBytes)

        try {
          val channel: FileChannel = FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
          try {
            Seq(headerBuf, linesBuf, microsBuf, bitmapBuf, trailerBuf).foreach { buffer =>
              while (buffer.hasRemaining) {
                channel.write(buffer)
              }
            }
          } finally {
            channel.close()
          }

          try {
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          } catch {
            case _: java.nio.file.AtomicMoveNotSupportedException =>
              Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
          }
        } catch {
          case writeFailure: Throwable =>
            Files.deleteIfExists(tempFile)
            throw writeFailure
        }

        logger.info(
          "Persisted Tier-2 cache for {} ({} checkpoints, {} continuation lines, {} KB) to {}",
          path.getFileName,
          checkpointCount,
          continuationBitmap.getLongCardinality,
          (Tier2HeaderBytes + (checkpointCount * 16L) + bitmapByteSize + TrailerBytes) / 1024L,
          targetFile.getFileName
        )
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Failed to persist Tier-2 cache for {}: {}", path, e.getMessage)
    }
  }

  /**
   * Attempts to deserialize previously cached Tier-2 metadata (.t2) for the specified log file.
   *
   * @param path local file path
   * @param storageReader storage reader for fingerprint validation
   * @param expectedTotalLines expected total lines matching the loaded Tier-1 index
   * @return Some(CachedTier2Metadata) if valid cache exists matching fingerprint and line count; None otherwise.
   */
  def tryLoadTier2(
    path: Path,
    storageReader: LogStorageReader,
    expectedTotalLines: Long
  ): Option[CachedTier2Metadata] = {
    try {
      if (path == null || !Files.exists(path)) {
        None
      } else {
        val fingerprint: String = computeFingerprint(path, storageReader)
        val cacheFile: Path = getTier2CacheFilePath(fingerprint)

        if (!Files.exists(cacheFile) || Files.size(cacheFile) < Tier2HeaderBytes + TrailerBytes) {
          None
        } else {
          val channel: FileChannel = FileChannel.open(cacheFile, StandardOpenOption.READ)
          try {
            readValidatedTier2(channel, Files.size(cacheFile), storageReader.sizeBytes, expectedTotalLines, path, cacheFile)
          } finally {
            channel.close()
          }
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Failed to load Tier-2 cache for {}: {}", path, e.getMessage)
        None
    }
  }

  private def readValidatedTier2(
    channel: FileChannel,
    cacheFileBytes: Long,
    expectedLogFileBytes: Long,
    expectedTotalLines: Long,
    logPath: Path,
    cacheFile: Path
  ): Option[CachedTier2Metadata] = {
    val headerBuf: ByteBuffer = ByteBuffer.allocate(Tier2HeaderBytes.toInt)
    if (!readFully(channel, headerBuf)) {
      None
    } else {
      headerBuf.flip()
      val magic: Long = headerBuf.getLong
      val version: Int = headerBuf.getInt
      val cachedFileSize: Long = headerBuf.getLong
      val cachedTotalLines: Long = headerBuf.getLong
      val checkpointCount: Int = headerBuf.getInt
      val bitmapByteSize: Long = headerBuf.getLong
      val hasDateByte: Byte = headerBuf.get()

      val expectedTotalBytes: Long = Tier2HeaderBytes + (checkpointCount.toLong * 16L) + bitmapByteSize + TrailerBytes

      if (magic != Tier2MagicSignature || version != Tier2CacheFormatVersion ||
          cachedFileSize != expectedLogFileBytes || cachedTotalLines != expectedTotalLines) {
        logger.debug("Tier-2 cache mismatch or invalid magic for {}", logPath)
        None
      } else if (checkpointCount < 0 || checkpointCount > 10000000 || bitmapByteSize < 0 || cacheFileBytes != expectedTotalBytes) {
        logger.warn("Corrupt or size mismatch in Tier-2 cache {}", cacheFile)
        None
      } else {
        val linesBuf: ByteBuffer = ByteBuffer.allocate(checkpointCount * 8)
        val microsBuf: ByteBuffer = ByteBuffer.allocate(checkpointCount * 8)
        val bitmapBytes: Array[Byte] = new Array[Byte](bitmapByteSize.toInt)
        val bitmapBuf: ByteBuffer = ByteBuffer.wrap(bitmapBytes)
        val trailerBuf: ByteBuffer = ByteBuffer.allocate(TrailerBytes.toInt)

        if (!readFully(channel, linesBuf) || !readFully(channel, microsBuf) ||
            !readFully(channel, bitmapBuf) || !readFully(channel, trailerBuf)) {
          logger.warn("Short read while loading Tier-2 cache {}", cacheFile)
          None
        } else {
          linesBuf.flip()
          microsBuf.flip()
          trailerBuf.flip()

          val bodyChecksum = new CRC32()
          bodyChecksum.update(linesBuf.duplicate())
          bodyChecksum.update(microsBuf.duplicate())
          bodyChecksum.update(bitmapBytes)

          if (bodyChecksum.getValue != trailerBuf.getLong) {
            logger.warn("Tier-2 cache checksum mismatch in {}", cacheFile)
            None
          } else {
            val linesArray = new Array[Long](checkpointCount)
            linesBuf.asLongBuffer().get(linesArray)
            val microsArray = new Array[Long](checkpointCount)
            microsBuf.asLongBuffer().get(microsArray)

            val continuationBitmap = new Roaring64NavigableMap()
            val dataIn = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bitmapBytes))
            continuationBitmap.deserialize(dataIn)

            try {
              Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()))
            } catch {
              case NonFatal(_) => ()
            }

            logger.info(
              "Instantly loaded cached Tier-2 metadata for {} ({} checkpoints, {} continuation lines) from {}",
              logPath.getFileName,
              checkpointCount,
              continuationBitmap.getLongCardinality,
              cacheFile.getFileName
            )

            val temporalTable = new TemporalCheckpointTable(
              LongArrayList.wrap(linesArray),
              LongArrayList.wrap(microsArray),
              LineIndex(expectedTotalLines),
              hasDateByte != 0.toByte
            )
            Some(CachedTier2Metadata(temporalTable, continuationBitmap))
          }
        }
      }
    }
  }

  /**
   * Scans ~/.umbrix/cache/indexes/ and deletes cache files that have not been accessed
   * within the specified age threshold (defaults to 7 days).
   *
   * @param maxAgeMillis age threshold in milliseconds (default: 7 days)
   * @return count of deleted stale cache files
   */
  def cleanStaleCaches(maxAgeMillis: Long = DefaultStaleThresholdMillis): Int = {
    var deletedCount: Int = 0
    try {
      val cacheDir: Path = getCacheDirectory
      if (Files.exists(cacheDir)) {
        val now: Long = System.currentTimeMillis()
        val stream = Files.list(cacheDir)
        try {
          val iterator = stream.iterator()
          while (iterator.hasNext) {
            val file: Path = iterator.next()
            val fileName: String = file.getFileName.toString
            if (fileName.endsWith(".idx") || fileName.endsWith(".t2") || fileName.endsWith(".tmp")) {
              try {
                val lastModified: Long = Files.getLastModifiedTime(file).toMillis
                val ageMillis: Long = now - lastModified
                if (ageMillis > maxAgeMillis || (fileName.endsWith(".tmp") && ageMillis > 3600000L)) {
                  Files.deleteIfExists(file)
                  deletedCount += 1
                  logger.debug("Pruned stale index cache file: {}", fileName)
                }
              } catch {
                case NonFatal(e) =>
                  logger.debug("Could not inspect/delete cache file {}: {}", file, e.getMessage)
              }
            }
          }
        } finally {
          stream.close()
        }
      }
      if (deletedCount > 0) {
        logger.info("Cleaned {} stale index cache files older than {} days", deletedCount, maxAgeMillis / (24L * 3600L * 1000L))
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Failed to clean stale index caches: {}", e.getMessage)
    }
    deletedCount
  }
}
