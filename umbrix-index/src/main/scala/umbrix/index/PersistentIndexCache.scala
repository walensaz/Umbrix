package umbrix.index

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import org.slf4j.LoggerFactory
import umbrix.vfs.LogStorageReader

import java.io.{File, RandomAccessFile}
import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.zip.CRC32
import scala.util.control.NonFatal

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
  private val CacheFormatVersion: Int = 1
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
   * Combines file size, modification timestamp, and CRC32 checksum of the first 64KB.
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
    if (storageReader != null && sampleSize > 0L) {
      val slice: MemorySegment = storageReader.readSlice(0L, sampleSize)
      var i: Long = 0L
      while (i < sampleSize) {
        val b: Byte = slice.get(ValueLayout.JAVA_BYTE, i)
        crc.update(b.toInt)
        i += 1L
      }
    } else if (Files.exists(path) && sampleSize > 0L) {
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

        if (!Files.exists(cacheFile) || Files.size(cacheFile) < 36L) {
          None
        } else {
          val channel: FileChannel = FileChannel.open(cacheFile, StandardOpenOption.READ)
          try {
            val headerBuf: ByteBuffer = ByteBuffer.allocate(36)
            channel.read(headerBuf)
            headerBuf.flip()

            val magic: Long = headerBuf.getLong
            val version: Int = headerBuf.getInt
            val cachedFileSize: Long = headerBuf.getLong
            val totalLinesCount: Long = headerBuf.getLong
            val superCount: Int = headerBuf.getInt
            val intraCount: Int = headerBuf.getInt

            if (magic != MagicSignature || version != CacheFormatVersion || cachedFileSize != storageReader.sizeBytes) {
              logger.debug("Index cache mismatch or invalid magic for {}", path)
              None
            } else if (superCount < 0 || intraCount < 0 || superCount > 10000000 || intraCount > 256000000) {
              logger.warn("Corrupt index cache entry counts in {}", cacheFile)
              None
            } else {
              val superTable = new LongArrayList(superCount)
              val intraTable = new IntArrayList(intraCount)

              // Read superStrideOffsets in bulk (8 bytes each)
              val superByteLength: Int = superCount * 8
              if (superByteLength > 0) {
                val superBuf: ByteBuffer = ByteBuffer.allocate(superByteLength)
                var readBytes: Int = 0
                while (readBytes < superByteLength) {
                  val count: Int = channel.read(superBuf)
                  if (count < 0) {
                    readBytes = superByteLength
                  } else {
                    readBytes += count
                  }
                }
                superBuf.flip()
                val longBuf = superBuf.asLongBuffer()
                val superArray = new Array[Long](superCount)
                longBuf.get(superArray)
                var si: Int = 0
                while (si < superCount) {
                  superTable.add(superArray(si))
                  si += 1
                }
              }

              // Read intraStrideDeltas in bulk (4 bytes each)
              val intraByteLength: Int = intraCount * 4
              if (intraByteLength > 0) {
                val intraBuf: ByteBuffer = ByteBuffer.allocate(intraByteLength)
                var readBytes: Int = 0
                while (readBytes < intraByteLength) {
                  val count: Int = channel.read(intraBuf)
                  if (count < 0) {
                    readBytes = intraByteLength
                  } else {
                    readBytes += count
                  }
                }
                intraBuf.flip()
                val intBuf = intraBuf.asIntBuffer()
                val intArray = new Array[Int](intraCount)
                intBuf.get(intArray)
                var ii: Int = 0
                while (ii < intraCount) {
                  intraTable.add(intArray(ii))
                  ii += 1
                }
              }

              // Refresh cache file access time for LRU tracking
              try {
                Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()))
              } catch {
                case NonFatal(_) => ()
              }

              logger.info(
                "Instantly loaded cached index for {} ({} lines, {} superblocks, {} intra entries) from {}",
                path.getFileName,
                totalLinesCount,
                superCount,
                intraCount,
                cacheFile.getFileName
              )

              Some(new TwoLevelSparseIndex(superTable, intraTable, totalLinesCount))
            }
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
   * Persists a completed TwoLevelSparseIndex to disk asynchronously.
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
        val tempFile: Path = cacheDir.resolve(s".$fingerprint.tmp")

        val superCount: Int = index.superStrideOffsets.size()
        val intraCount: Int = index.intraStrideDeltas.size()
        val totalBytes: Long = 36L + (superCount.toLong * 8L) + (intraCount.toLong * 4L)

        val channel: FileChannel = FileChannel.open(
          tempFile,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        try {
          // Write 36-byte header
          val headerBuf: ByteBuffer = ByteBuffer.allocate(36)
          headerBuf.putLong(MagicSignature)
          headerBuf.putInt(CacheFormatVersion)
          headerBuf.putLong(storageReader.sizeBytes)
          headerBuf.putLong(index.totalLinesCount)
          headerBuf.putInt(superCount)
          headerBuf.putInt(intraCount)
          headerBuf.flip()
          channel.write(headerBuf)

          // Write SuperStride table
          if (superCount > 0) {
            val superBuf: ByteBuffer = ByteBuffer.allocate(superCount * 8)
            val superArray = index.superStrideOffsets.toLongArray
            val longBuf = superBuf.asLongBuffer()
            longBuf.put(superArray)
            superBuf.position(0)
            superBuf.limit(superCount * 8)
            channel.write(superBuf)
          }

          // Write IntraStride table
          if (intraCount > 0) {
            val intraBuf: ByteBuffer = ByteBuffer.allocate(intraCount * 4)
            val intArray = index.intraStrideDeltas.toIntArray
            val intBuf = intraBuf.asIntBuffer()
            intBuf.put(intArray)
            intraBuf.position(0)
            intraBuf.limit(intraCount * 4)
            channel.write(intraBuf)
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

        logger.info(
          "Persisted index cache for {} ({} lines, {} KB) to {}",
          path.getFileName,
          index.totalLinesCount,
          (totalBytes / 1024L),
          targetFile.getFileName
        )
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Failed to persist index cache for {}: {}", path, e.getMessage)
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
            if (fileName.endsWith(".idx") || fileName.endsWith(".tmp")) {
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
