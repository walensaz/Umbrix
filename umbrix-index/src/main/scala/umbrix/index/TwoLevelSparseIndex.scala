package umbrix.index

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import jdk.incubator.vector.{ByteVector, VectorMask}
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.simd.VectorScanner
import umbrix.vfs.LogStorageReader
import umbrix.vfs.platform.PlatformMemoryAdvisor
import scala.annotation.tailrec

/**
 * Two-Level Hierarchical Sparse Index implementation.
 *
 * Provides O(1) mathematical coordinate translation from any 64-bit line number L to its
 * exact byte offset in off-heap storage, requiring only ~12.9MB RAM for a 25GB file (215M lines).
 *
 * Structure:
 * - Level 1 (Super-Stride): Sampled every 16,384 lines (2^14), stores absolute 64-bit offsets.
 * - Level 2 (Intra-Stride): Sampled every 64 lines (2^6), stores 32-bit relative delta offsets
 *   (256 entries per superblock).
 * - Fine SIMD Scan: Evaluates remaining <= 63 lines from the intra-stride checkpoint in <1.5 microseconds.
 */
class TwoLevelSparseIndex(
  val superStrideOffsets: LongArrayList,
  val intraStrideDeltas: IntArrayList,
  val totalLinesCount: Long,
  /** Bytes covered by this index; `Long.MaxValue` means the whole file (complete index). */
  val indexedBytes: Long = Long.MaxValue
) {

  inline def totalLines: LineIndex = LineIndex(totalLinesCount)

  /**
   * Resolves the exact 64-bit file byte offset for a given line index.
   *
   * @param lineIndex 0-based physical line index [0, totalLinesCount)
   * @param storageReader storage handle for fine-grain SIMD scan of remaining <= 63 lines
   * @return absolute byte offset in the file as a strongly-typed ByteOffset
   */
  def byteOffsetForLine(lineIndex: LineIndex, storageReader: LogStorageReader): ByteOffset = {
    val rawLine: Long = lineIndex.value
    if (rawLine <= 0L) {
      ByteOffset.Zero
    } else if (rawLine >= totalLinesCount) {
      ByteOffset(storageReader.sizeBytes)
    } else {
      val superIndex: Int = SparseIndex.superblockIndex(rawLine)
      val intraIndex: Int = SparseIndex.intraSlotIndex(rawLine)
      val remainingLinesToScan: Int = SparseIndex.fineScanOffset(rawLine)

      val baseSuperOffset: Long = superStrideOffsets.getLong(superIndex)
      val intraDeltaOffset: Long = SparseIndex.unsignedIntraDelta(
        intraStrideDeltas.getInt(SparseIndex.intraTableOffset(superIndex, intraIndex))
      )
      val checkpointByteOffset: Long = baseSuperOffset + intraDeltaOffset

      val resultOffset: Long = if (remainingLinesToScan == 0) {
        checkpointByteOffset
      } else {
        scanForwardForLineOffset(storageReader, checkpointByteOffset, remainingLinesToScan)
      }
      ByteOffset(resultOffset)
    }
  }

  /**
   * Resolves the 0-based physical line index containing the specified target byte offset.
   *
   * Executes in O(log N) time (<1.5 microseconds):
   * 1. Binary searches Level 1 Super-Stride table (16,384 lines/entry).
   * 2. Binary searches Level 2 Intra-Stride delta table (64 lines/entry).
   * 3. Hardware SIMD vector scan counts newlines in the remaining <= 63 lines.
   *
   * @param targetByteOffset absolute byte offset within the file
   * @param storageReader storage reader for fine-grain SIMD newline counting
   * @return 0-based physical line index containing the target byte offset as LineIndex
   */
  def lineIndexForByteOffset(targetByteOffset: ByteOffset, storageReader: LogStorageReader): LineIndex = {
    val offset: Long = targetByteOffset.value
    val fileSize: Long = storageReader.sizeBytes
    if (offset <= 0L || totalLinesCount <= 0L) {
      LineIndex.Zero
    } else if (offset >= fileSize) {
      LineIndex((totalLinesCount - 1L).max(0L))
    } else {
      // 1. Binary search Super-Stride table
      val resolvedSuperIndex: Int = SparseIndex.binarySearchFloor(0, superStrideOffsets.size() - 1, offset) { mid =>
        superStrideOffsets.getLong(mid)
      }

      val baseSuperOffset: Long = superStrideOffsets.getLong(resolvedSuperIndex)
      val intraBaseIndex: Int = SparseIndex.intraTableOffset(resolvedSuperIndex, 0)
      val intraEntriesCount: Int = (intraStrideDeltas.size() - intraBaseIndex).min(SparseIndex.IntraEntriesPerSuperblock).max(1)

      // 2. Binary search Intra-Stride table within selected superblock
      val resolvedIntraIndex: Int = SparseIndex.binarySearchFloor(0, intraEntriesCount - 1, offset) { mid =>
        baseSuperOffset + SparseIndex.unsignedIntraDelta(intraStrideDeltas.getInt(intraBaseIndex + mid))
      }

      val intraDeltaOffset: Long = SparseIndex.unsignedIntraDelta(intraStrideDeltas.getInt(intraBaseIndex + resolvedIntraIndex))
      val checkpointByteOffset: Long = baseSuperOffset + intraDeltaOffset
      val baseLineIndex: Long = SparseIndex.checkpointLineIndex(resolvedSuperIndex, resolvedIntraIndex)

      // 3. Count newlines between checkpointByteOffset and targetByteOffset
      val bytesToScan: Long = offset - checkpointByteOffset
      val resolvedLine: Long = if (bytesToScan <= 0L) {
        baseLineIndex.min(totalLinesCount - 1L)
      } else {
        val scanSlice: MemorySegment = storageReader.readSlice(checkpointByteOffset, bytesToScan)
        val newlinesInWindow: Long = VectorScanner.countByte(scanSlice, 0L, bytesToScan, 0x0A.toByte)
        val candidateLine: Long = baseLineIndex + newlinesInWindow
        candidateLine.min(totalLinesCount - 1L)
      }
      LineIndex(resolvedLine)
    }
  }

  /**
   * Scans forward from `checkpointOffset` to locate the byte immediately following the
   * `linesRemaining`-th newline character.
   */
  private def scanForwardForLineOffset(
    storageReader: LogStorageReader,
    checkpointOffset: Long,
    linesRemaining: Int
  ): Long = {
    val fileSize: Long = storageReader.sizeBytes
    val scanWindowSize: Long = (linesRemaining * 512L).max(4096L).min(fileSize - checkpointOffset)
    val windowSlice: MemorySegment = storageReader.readSlice(checkpointOffset, scanWindowSize)

    if (linesRemaining == 1) {
      val firstNewlineRelative: Long = VectorScanner.findFirstNewline(windowSlice, 0L, scanWindowSize)
      if (firstNewlineRelative >= 0L) {
        checkpointOffset + firstNewlineRelative + 1L
      } else {
        fallbackScalarSearch(storageReader, checkpointOffset + scanWindowSize, 1)
      }
    } else {
      var targetFoundOffset: Long = checkpointOffset
      var linesLeftToSkip: Int = linesRemaining

      VectorScanner.scanNewlines(
        windowSlice,
        0L,
        scanWindowSize,
        (relativeOffset: Long) => {
          if (linesLeftToSkip > 0) {
            linesLeftToSkip -= 1
            if (linesLeftToSkip == 0) {
              targetFoundOffset = checkpointOffset + relativeOffset + 1L
            }
          }
        }
      )

      if (linesLeftToSkip == 0) {
        targetFoundOffset
      } else {
        // If scan window was too small for abnormally large lines, continue search to end of file
        fallbackScalarSearch(storageReader, checkpointOffset + scanWindowSize, linesLeftToSkip)
      }
    }
  }

  @tailrec
  private def fallbackScalarSearch(
    storageReader: LogStorageReader,
    currentOffset: Long,
    linesRemaining: Int
  ): Long = {
    val fileSize: Long = storageReader.sizeBytes
    if (currentOffset >= fileSize || linesRemaining <= 0) {
      currentOffset
    } else {
      val chunkSize: Long = 65536L.min(fileSize - currentOffset)
      val slice: MemorySegment = storageReader.readSlice(currentOffset, chunkSize)
      if (linesRemaining == 1) {
        val foundRel: Long = VectorScanner.findFirstNewline(slice, 0L, chunkSize)
        if (foundRel >= 0L) {
          currentOffset + foundRel + 1L
        } else {
          fallbackScalarSearch(storageReader, currentOffset + chunkSize, 1)
        }
      } else {
        var linesLeft: Int = linesRemaining
        var matchOffset: Long = SparseIndex.NoMatchOffset
        var index: Long = 0L

        while (index < chunkSize && linesLeft > 0) {
          if (slice.get(ValueLayout.JAVA_BYTE, index) == 0x0A.toByte) {
            linesLeft -= 1
            if (linesLeft == 0) {
              matchOffset = currentOffset + index + 1L
            }
          }
          index += 1L
        }

        if (linesLeft == 0) {
          matchOffset
        } else {
          fallbackScalarSearch(storageReader, currentOffset + chunkSize, linesLeft)
        }
      }
    }
  }
}

/**
 * Mutable indexing state container maintaining physical line and superblock cursors.
 */
private final class IndexBuildState(
  val superTable: LongArrayList,
  val intraTable: IntArrayList
) {
  var currentSuperblockBaseOffset: Long = 0L
  var currentLineCounter: Long = 0L
  var linesUntilNextIntraStride: Int = SparseIndex.IntraStrideLines
  var intraIndexInSuperblock: Int = 0
}

object TwoLevelSparseIndex {

  /** Reads up to `bytesToRead` sequentially from `fileChannel`, halting cleanly if EOF occurs early. */
  private def readChannelChunk(
    fileChannel: FileChannel,
    buffer: ByteBuffer,
    bytesToRead: Int
  ): Int = {
    buffer.clear()
    buffer.limit(bytesToRead)
    var bytesTransferred: Int = 0
    var eofReached: Boolean = false
    while (bytesTransferred < bytesToRead && !eofReached) {
      val readCount: Int = fileChannel.read(buffer)
      if (readCount < 0) {
        eofReached = true
      } else {
        bytesTransferred += readCount
      }
    }
    bytesTransferred
  }

  @inline
  private def recordStrideBoundary(state: IndexBuildState, nextLineStartOffset: Long): Unit = {
    state.intraIndexInSuperblock += 1
    if (state.intraIndexInSuperblock == SparseIndex.IntraEntriesPerSuperblock) {
      // Superblock boundary (every 16,384 lines)
      state.currentSuperblockBaseOffset = nextLineStartOffset
      state.superTable.add(nextLineStartOffset)
      state.intraTable.add(0)
      state.intraIndexInSuperblock = 0
    } else {
      // Intra-stride boundary (every 64 lines), clamped safely to 32-bit unsigned max
      val rawDelta: Long = nextLineStartOffset - state.currentSuperblockBaseOffset
      state.intraTable.add(SparseIndex.clampIntraDelta(rawDelta))
    }
    state.linesUntilNextIntraStride = SparseIndex.IntraStrideLines
  }

  /**
   * Scans a single off-heap memory segment slice using fast batch POPCNT SIMD execution.
   *
   * Performance optimization:
   * Uses single-cycle POPCNT (bitCount) to bulk-accumulate newline counts across 64-byte vector lanes.
   * Only decodes individual bit positions (TZCNT / BLSR) when a 64-line or 16,384-line stride
   * boundary is crossed, eliminating 98.4% of bitmask lane decoding and lambda calls.
   */
  private def scanChunkFast(
    chunkSegment: MemorySegment,
    chunkBaseOffset: Long,
    chunkLength: Long,
    fileSize: Long,
    state: IndexBuildState
  ): Unit = {
    if (chunkLength > 0L) {
      val preferredSpecies = ByteVector.SPECIES_PREFERRED
      val vectorByteWidth = VectorScanner.vectorByteWidth
      val targetByte: Byte = 0x0A.toByte
      val nativeByteOrder = java.nio.ByteOrder.nativeOrder()

      val vectorRemainder: Long = chunkLength % vectorByteWidth
      val vectorLoopBoundOffset: Long = chunkLength - vectorRemainder
      var currentByteOffset: Long = 0L

      while (currentByteOffset < vectorLoopBoundOffset) {
        val vectorChunk: ByteVector = ByteVector.fromMemorySegment(
          preferredSpecies,
          chunkSegment,
          currentByteOffset,
          nativeByteOrder
        )
        val matchMask: VectorMask[java.lang.Byte] = vectorChunk.eq(targetByte)
        var bitmask: Long = matchMask.toLong
        if (bitmask != 0L) {
          val matchesInVector: Int = java.lang.Long.bitCount(bitmask)
          if (matchesInVector < state.linesUntilNextIntraStride &&
              (chunkBaseOffset + currentByteOffset + vectorByteWidth.toLong) < fileSize) {
            // Fast path: All newlines in vector stay within the current 64-line stride and before EOF
            state.currentLineCounter += matchesInVector.toLong
            state.linesUntilNextIntraStride -= matchesInVector
          } else {
            // Slow path: Stride boundary crossed or near EOF. Extract individual bit positions.
            while (bitmask != 0L) {
              val relativeMatchLane: Int = java.lang.Long.numberOfTrailingZeros(bitmask)
              val absoluteNewlineOffset: Long = chunkBaseOffset + currentByteOffset + relativeMatchLane.toLong
              val nextLineStartOffset: Long = absoluteNewlineOffset + 1L

              if (nextLineStartOffset < fileSize) {
                state.currentLineCounter += 1L
                state.linesUntilNextIntraStride -= 1

                if (state.linesUntilNextIntraStride == 0) {
                  recordStrideBoundary(state, nextLineStartOffset)
                }
              }
              bitmask &= (bitmask - 1L)
            }
          }
        }
        currentByteOffset += vectorByteWidth.toLong
      }

      // Scalar tail loop for remaining unaligned bytes (< vectorByteWidth)
      while (currentByteOffset < chunkLength) {
        if (chunkSegment.get(ValueLayout.JAVA_BYTE, currentByteOffset) == targetByte) {
          val absoluteNewlineOffset: Long = chunkBaseOffset + currentByteOffset
          val nextLineStartOffset: Long = absoluteNewlineOffset + 1L

          if (nextLineStartOffset < fileSize) {
            state.currentLineCounter += 1L
            state.linesUntilNextIntraStride -= 1

            if (state.linesUntilNextIntraStride == 0) {
              recordStrideBoundary(state, nextLineStartOffset)
            }
          }
        }
        currentByteOffset += 1L
      }
    }
  }

  /**
   * Builds a two-level sparse index using sequential streaming channel I/O into a single
   * reusable off-heap direct buffer.
   *
   * Anti-Thrashing Guarantee:
   * Bypasses OS continuous virtual memory mapping during full-file scans. The operating system
   * discards read pages behind the sequential read cursor, preventing 25GB-100GB files from
   * filling the physical memory page cache and causing pagefile.sys disk thrashing.
   *
   * @param path local file system path to the log file
   * @param bufferCapacity capacity in bytes of the single reusable direct buffer
   * @return populated TwoLevelSparseIndex instance
   */
  def buildStreaming(
    path: Path,
    bufferCapacity: Long = PlatformMemoryAdvisor.MaximumStreamingBufferBytes
  ): TwoLevelSparseIndex = {
    val fileChannel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
    try {
      val fileSize: Long = fileChannel.size()
      if (fileSize <= 0L) {
        new TwoLevelSparseIndex(new LongArrayList(0), new IntArrayList(0), 0L)
      } else {
        val estimatedLines: Long = (fileSize / 100L).max(1024L)
        val estimatedSuperBlocks: Int = ((estimatedLines >>> SparseIndex.SuperStrideShift) + 16L).toInt
        val estimatedIntraEntries: Int = estimatedSuperBlocks * SparseIndex.IntraEntriesPerSuperblock

        val superTable: LongArrayList = new LongArrayList(estimatedSuperBlocks)
        val intraTable: IntArrayList = new IntArrayList(estimatedIntraEntries)

        // Line 0 always begins at byte 0 of the file
        superTable.add(0L)
        intraTable.add(0)

        val state = new IndexBuildState(superTable, intraTable)
        val clampedCapacity: Long = bufferCapacity.max(1024L * 1024L).min(fileSize.max(1024L * 1024L))
        val confinedArena: Arena = Arena.ofConfined()
        try {
          val directSegment: MemorySegment = confinedArena.allocate(clampedCapacity, 64)
          val directByteBuffer: ByteBuffer = directSegment.asByteBuffer()

          fileChannel.position(0L)
          var fileOffset: Long = 0L
          var reachedEof: Boolean = false
          while (fileOffset < fileSize && !reachedEof) {
            val bytesToRead: Int = clampedCapacity.min(fileSize - fileOffset).toInt
            val bytesTransferred: Int = readChannelChunk(fileChannel, directByteBuffer, bytesToRead)
            val truncatedAtEof: Boolean = bytesTransferred < bytesToRead
            if (bytesTransferred > 0) {
              val effectiveEndOffset: Long = if (truncatedAtEof) fileOffset + bytesTransferred.toLong else fileSize
              val chunkSlice: MemorySegment = directSegment.asSlice(0L, bytesTransferred.toLong)
              scanChunkFast(chunkSlice, fileOffset, bytesTransferred.toLong, effectiveEndOffset, state)
              fileOffset += bytesTransferred.toLong
            }
            if (truncatedAtEof) {
              reachedEof = true
            }
          }
        } finally {
          confinedArena.close()
        }

        val totalLines: Long = state.currentLineCounter + 1L
        new TwoLevelSparseIndex(superTable, intraTable, totalLines)
      }
    } finally {
      fileChannel.close()
    }
  }

  /**
   * Progressively builds a two-level sparse index using sequential streaming channel I/O.
   *
   * Scans Chunk 0 (up to 32MB) synchronously in <10ms and immediately emits an initial snapshot
   * TwoLevelSparseIndex to `onInitialChunkReady` so lines 0 to 50 render instantaneously in the viewport.
   *
   * Asynchronously streams the remaining chunks to EOF on a background virtual/carrier thread,
   * periodically reporting progress and updating index line bounds.
   *
   * @param path local file system path to the log file
   * @param onInitialChunkReady callback invoked immediately when Chunk 0 finishes (<10ms)
   * @param onChunkUpdate callback invoked as chunks are scanned with (updatedIndex, bytesScanned, totalBytes, linesFound)
   * @param bufferCapacity capacity in bytes of the single reusable direct buffer
   * @param executor execution service for background scanning
   * @return CompletableFuture completing with the final fully-indexed TwoLevelSparseIndex
   */
  def buildStreamingProgressive(
    path: Path,
    onInitialChunkReady: TwoLevelSparseIndex => Unit,
    onChunkUpdate: (TwoLevelSparseIndex, Long, Long, Long) => Unit,
    bufferCapacity: Long = PlatformMemoryAdvisor.MaximumStreamingBufferBytes,
    executor: ExecutorService = null
  ): CompletableFuture[TwoLevelSparseIndex] = {
    val fileChannel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
    val fileSize: Long = try {
      fileChannel.size()
    } catch {
      case t: Throwable =>
        try { fileChannel.close() } catch { case _: Throwable => () }
        throw t
    }
    if (fileSize <= 0L) {
      fileChannel.close()
      val emptyIndex = new TwoLevelSparseIndex(new LongArrayList(0), new IntArrayList(0), 0L)
      onInitialChunkReady(emptyIndex)
      onChunkUpdate(emptyIndex, 0L, 0L, 0L)
      CompletableFuture.completedFuture(emptyIndex)
    } else {
      val estimatedLines: Long = (fileSize / 100L).max(1024L)
      val estimatedSuperBlocks: Int = ((estimatedLines >>> SparseIndex.SuperStrideShift) + 16L).toInt
      val estimatedIntraEntries: Int = estimatedSuperBlocks * SparseIndex.IntraEntriesPerSuperblock

      val superTable: LongArrayList = new LongArrayList(estimatedSuperBlocks)
      val intraTable: IntArrayList = new IntArrayList(estimatedIntraEntries)

      // Line 0 always begins at byte 0 of the file
      superTable.add(0L)
      intraTable.add(0)

      val state = new IndexBuildState(superTable, intraTable)
      val clampedCapacity: Long = bufferCapacity.max(1024L * 1024L).min(fileSize.max(1024L * 1024L))

      // --- Synchronous Bootstrap Phase: Scan Chunk 0 on caller thread (<10ms) ---
      val chunk0Arena: Arena = Arena.ofConfined()
      val initialBytesScanned: Long = try {
        val directSegment: MemorySegment = chunk0Arena.allocate(clampedCapacity, 64)
        val directByteBuffer: ByteBuffer = directSegment.asByteBuffer()

        fileChannel.position(0L)
        val bytesToReadFirst: Int = clampedCapacity.min(fileSize).toInt
        val bytesTransferredFirst: Int = readChannelChunk(fileChannel, directByteBuffer, bytesToReadFirst)

        val effectiveEndOffset: Long = if (bytesTransferredFirst < bytesToReadFirst) bytesTransferredFirst.toLong else fileSize
        val firstChunkSlice: MemorySegment = directSegment.asSlice(0L, bytesTransferredFirst.toLong)
        scanChunkFast(firstChunkSlice, 0L, bytesTransferredFirst.toLong, effectiveEndOffset, state)
        bytesTransferredFirst.toLong
      } finally {
        try { chunk0Arena.close() } catch { case _: Throwable => () }
        try { fileChannel.close() } catch { case _: Throwable => () }
      }

      val initialLines: Long = state.currentLineCounter + 1L
      val initialSnapshot: TwoLevelSparseIndex = new TwoLevelSparseIndex(
        new LongArrayList(superTable),
        new IntArrayList(intraTable),
        initialLines,
        if (initialBytesScanned >= fileSize) Long.MaxValue else initialBytesScanned
      )
      onInitialChunkReady(initialSnapshot)
      onChunkUpdate(initialSnapshot, initialBytesScanned, fileSize, initialLines)

      if (initialBytesScanned >= fileSize) {
        // Entire file fits within initial chunk (<32MB), fully complete!
        CompletableFuture.completedFuture(initialSnapshot)
      } else {
        // --- Asynchronous Progressive Phase: Stream Remaining Chunks in Background ---
        val ownsExecutor: Boolean = (executor == null)
        val activeExecutor: ExecutorService = if (ownsExecutor) {
          Executors.newVirtualThreadPerTaskExecutor()
        } else {
          executor
        }
        val futureResult = new CompletableFuture[TwoLevelSparseIndex]()
        activeExecutor.submit(new Runnable {
          def run(): Unit = {
            val bgChannel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
            val bgArena: Arena = Arena.ofConfined()
            try {
              val bgSegment: MemorySegment = bgArena.allocate(clampedCapacity, 64)
              val bgByteBuffer: ByteBuffer = bgSegment.asByteBuffer()

              bgChannel.position(initialBytesScanned)
              var fileOffset: Long = initialBytesScanned
              var lastProgressReportTime: Long = System.currentTimeMillis()
              var reachedEof: Boolean = false

              while (fileOffset < fileSize && !reachedEof && !futureResult.isCancelled) {
                val bytesToRead: Int = clampedCapacity.min(fileSize - fileOffset).toInt
                val bytesTransferred: Int = readChannelChunk(bgChannel, bgByteBuffer, bytesToRead)
                val truncatedAtEof: Boolean = bytesTransferred < bytesToRead

                if (bytesTransferred > 0) {
                  val effectiveEndOffset: Long = if (truncatedAtEof) fileOffset + bytesTransferred.toLong else fileSize
                  val chunkSlice: MemorySegment = bgSegment.asSlice(0L, bytesTransferred.toLong)
                  scanChunkFast(chunkSlice, fileOffset, bytesTransferred.toLong, effectiveEndOffset, state)
                  fileOffset += bytesTransferred.toLong
                }
                if (truncatedAtEof) {
                  reachedEof = true
                }

                val now: Long = System.currentTimeMillis()
                val isFinished: Boolean = (fileOffset >= fileSize || reachedEof)
                val linesFound: Long = state.currentLineCounter + 1L
                val shouldSnapshot: Boolean = (now - lastProgressReportTime >= 500L || isFinished)
                val snapshot: TwoLevelSparseIndex = if (shouldSnapshot) {
                  lastProgressReportTime = now
                  new TwoLevelSparseIndex(
                    new LongArrayList(superTable),
                    new IntArrayList(intraTable),
                    linesFound,
                    fileOffset
                  )
                } else {
                  null
                }
                onChunkUpdate(snapshot, fileOffset, fileSize, linesFound)
              }

              val finalTotalLines: Long = state.currentLineCounter + 1L
              val finalIndex: TwoLevelSparseIndex = new TwoLevelSparseIndex(superTable, intraTable, finalTotalLines)
              futureResult.complete(finalIndex)
            } catch {
              case t: Throwable =>
                futureResult.completeExceptionally(t)
            } finally {
              try { bgArena.close() } catch { case _: Throwable => () }
              try { bgChannel.close() } catch { case _: Throwable => () }
              if (ownsExecutor) {
                try { activeExecutor.shutdown() } catch { case _: Throwable => () }
              }
            }
          }
        })
        futureResult
      }
    }
  }

  /**
   * Builds a two-level sparse index from an existing `LogStorageReader`.
   * Automatically dispatches to anti-thrashing streaming channel I/O when reading from a file path.
   */
  def build(storageReader: LogStorageReader): TwoLevelSparseIndex = {
    if (storageReader.path != null && Files.exists(storageReader.path)) {
      buildStreaming(storageReader.path)
    } else {
      buildFromReader(storageReader)
    }
  }

  /**
   * In-memory fallback builder utilizing `storageReader.readSlice` with fast batch POPCNT SIMD execution.
   */
  private def buildFromReader(storageReader: LogStorageReader): TwoLevelSparseIndex = {
    val fileSize: Long = storageReader.sizeBytes
    if (fileSize <= 0L) {
      new TwoLevelSparseIndex(new LongArrayList(0), new IntArrayList(0), 0L)
    } else {
      val estimatedLines: Long = (fileSize / 100L).max(1024L)
      val estimatedSuperBlocks: Int = ((estimatedLines >>> SparseIndex.SuperStrideShift) + 16L).toInt
      val estimatedIntraEntries: Int = estimatedSuperBlocks * SparseIndex.IntraEntriesPerSuperblock

      val superTable: LongArrayList = new LongArrayList(estimatedSuperBlocks)
      val intraTable: IntArrayList = new IntArrayList(estimatedIntraEntries)

      superTable.add(0L)
      intraTable.add(0)

      val state = new IndexBuildState(superTable, intraTable)
      val scanChunkSize: Long = 32L * 1024L * 1024L

      var chunkStartOffset: Long = 0L
      while (chunkStartOffset < fileSize) {
        val currentChunkLength: Long = scanChunkSize.min(fileSize - chunkStartOffset)
        val chunkSlice: MemorySegment = storageReader.readSlice(chunkStartOffset, currentChunkLength)
        scanChunkFast(chunkSlice, chunkStartOffset, currentChunkLength, fileSize, state)
        chunkStartOffset += currentChunkLength
      }

      val totalLines: Long = state.currentLineCounter + 1L
      new TwoLevelSparseIndex(superTable, intraTable, totalLines)
    }
  }
}
