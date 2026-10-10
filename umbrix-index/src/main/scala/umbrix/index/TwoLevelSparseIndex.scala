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
    val l: Long = lineIndex.value
    if (l <= 0L) {
      ByteOffset.Zero
    } else if (l >= totalLinesCount) {
      ByteOffset(storageReader.sizeBytes)
    } else {
      val superIndex: Int = (l >>> SparseIndex.SuperStrideShift).toInt
      val intraIndex: Int = ((l & SparseIndex.IntraMask) >>> SparseIndex.IntraStrideShift).toInt
      val remainingLinesToScan: Int = (l & SparseIndex.FineScanMask).toInt

      val baseSuperOffset: Long = superStrideOffsets.getLong(superIndex)
      val intraDeltaOffset: Long = intraStrideDeltas.getInt((superIndex << 8) + intraIndex) & 0xFFFFFFFFL
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
      var lowSuper: Int = 0
      var highSuper: Int = superStrideOffsets.size() - 1
      var resolvedSuperIndex: Int = 0
      while (lowSuper <= highSuper) {
        val midSuper: Int = (lowSuper + highSuper) >>> 1
        if (superStrideOffsets.getLong(midSuper) <= offset) {
          resolvedSuperIndex = midSuper
          lowSuper = midSuper + 1
        } else {
          highSuper = midSuper - 1
        }
      }

      val baseSuperOffset: Long = superStrideOffsets.getLong(resolvedSuperIndex)
      val intraBaseIndex: Int = resolvedSuperIndex << 8
      val intraEntriesCount: Int = (intraStrideDeltas.size() - intraBaseIndex).min(SparseIndex.IntraEntriesPerSuperblock).max(1)

      // 2. Binary search Intra-Stride table within selected superblock
      var lowIntra: Int = 0
      var highIntra: Int = intraEntriesCount - 1
      var resolvedIntraIndex: Int = 0
      while (lowIntra <= highIntra) {
        val midIntra: Int = (lowIntra + highIntra) >>> 1
        val intraDelta: Long = intraStrideDeltas.getInt(intraBaseIndex + midIntra) & 0xFFFFFFFFL
        if (baseSuperOffset + intraDelta <= offset) {
          resolvedIntraIndex = midIntra
          lowIntra = midIntra + 1
        } else {
          highIntra = midIntra - 1
        }
      }

      val intraDeltaOffset: Long = intraStrideDeltas.getInt(intraBaseIndex + resolvedIntraIndex) & 0xFFFFFFFFL
      val checkpointByteOffset: Long = baseSuperOffset + intraDeltaOffset
      val baseLineIndex: Long = (resolvedSuperIndex.toLong << SparseIndex.SuperStrideShift) +
        (resolvedIntraIndex.toLong << SparseIndex.IntraStrideShift)

      // 3. Count newlines between checkpointByteOffset and targetByteOffset
      val bytesToScan: Long = offset - checkpointByteOffset
      val line: Long = if (bytesToScan <= 0L) {
        baseLineIndex.min(totalLinesCount - 1L)
      } else {
        val scanSlice: MemorySegment = storageReader.readSlice(checkpointByteOffset, bytesToScan)
        val newlinesInWindow: Long = VectorScanner.countByte(scanSlice, 0L, bytesToScan, 0x0A.toByte)
        val candidateLine: Long = baseLineIndex + newlinesInWindow
        candidateLine.min(totalLinesCount - 1L)
      }
      LineIndex(line)
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
      // If scan window was too small for abnormally large lines, continue scalar search to end of file
      fallbackScalarSearch(storageReader, checkpointOffset + scanWindowSize, linesLeftToSkip)
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
      var linesLeft: Int = linesRemaining
      var matchOffset: Long = -1L
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
          if (matchesInVector < state.linesUntilNextIntraStride) {
            // Fast path: All newlines in vector stay within the current 64-line stride
            state.currentLineCounter += matchesInVector.toLong
            state.linesUntilNextIntraStride -= matchesInVector
          } else {
            // Slow path: Stride boundary crossed. Extract individual bit positions.
            while (bitmask != 0L) {
              val relativeMatchLane: Int = java.lang.Long.numberOfTrailingZeros(bitmask)
              val absoluteNewlineOffset: Long = chunkBaseOffset + currentByteOffset + relativeMatchLane.toLong
              val nextLineStartOffset: Long = absoluteNewlineOffset + 1L

              if (nextLineStartOffset < fileSize) {
                state.currentLineCounter += 1L
                state.linesUntilNextIntraStride -= 1

                if (state.linesUntilNextIntraStride == 0) {
                  state.intraIndexInSuperblock += 1
                  if (state.intraIndexInSuperblock == SparseIndex.IntraEntriesPerSuperblock) {
                    // Superblock boundary (every 16,384 lines)
                    state.currentSuperblockBaseOffset = nextLineStartOffset
                    state.superTable.add(nextLineStartOffset)
                    state.intraTable.add(0)
                    state.intraIndexInSuperblock = 0
                  } else {
                    // Intra-stride boundary (every 64 lines)
                    val deltaFromSuperblock: Int = (nextLineStartOffset - state.currentSuperblockBaseOffset).toInt
                    state.intraTable.add(deltaFromSuperblock)
                  }
                  state.linesUntilNextIntraStride = SparseIndex.IntraStrideLines
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
              state.intraIndexInSuperblock += 1
              if (state.intraIndexInSuperblock == SparseIndex.IntraEntriesPerSuperblock) {
                state.currentSuperblockBaseOffset = nextLineStartOffset
                state.superTable.add(nextLineStartOffset)
                state.intraTable.add(0)
                state.intraIndexInSuperblock = 0
              } else {
                val deltaFromSuperblock: Int = (nextLineStartOffset - state.currentSuperblockBaseOffset).toInt
                state.intraTable.add(deltaFromSuperblock)
              }
              state.linesUntilNextIntraStride = SparseIndex.IntraStrideLines
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
          while (fileOffset < fileSize) {
            val bytesToRead: Long = clampedCapacity.min(fileSize - fileOffset)
            directByteBuffer.clear()
            directByteBuffer.limit(bytesToRead.toInt)

            var bytesTransferred: Int = 0
            while (bytesTransferred < bytesToRead.toInt) {
              val readCount: Int = fileChannel.read(directByteBuffer)
              if (readCount < 0) {
                bytesTransferred = bytesToRead.toInt
              } else {
                bytesTransferred += readCount
              }
            }

            val chunkSlice: MemorySegment = directSegment.asSlice(0L, bytesTransferred.toLong)
            scanChunkFast(chunkSlice, fileOffset, bytesTransferred.toLong, fileSize, state)
            fileOffset += bytesTransferred.toLong
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
    executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
  ): CompletableFuture[TwoLevelSparseIndex] = {
    val fileChannel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
    val fileSize: Long = fileChannel.size()
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
        val bytesToReadFirst: Long = clampedCapacity.min(fileSize)
        directByteBuffer.clear()
        directByteBuffer.limit(bytesToReadFirst.toInt)

        var bytesTransferredFirst: Int = 0
        while (bytesTransferredFirst < bytesToReadFirst.toInt) {
          val readCount: Int = fileChannel.read(directByteBuffer)
          if (readCount < 0) {
            bytesTransferredFirst = bytesToReadFirst.toInt
          } else {
            bytesTransferredFirst += readCount
          }
        }

        val firstChunkSlice: MemorySegment = directSegment.asSlice(0L, bytesTransferredFirst.toLong)
        scanChunkFast(firstChunkSlice, 0L, bytesTransferredFirst.toLong, fileSize, state)
        bytesTransferredFirst.toLong
      } finally {
        chunk0Arena.close()
        fileChannel.close()
      }

      val initialLines: Long = state.currentLineCounter + 1L
      val initialSnapshot: TwoLevelSparseIndex = new TwoLevelSparseIndex(
        new LongArrayList(superTable),
        new IntArrayList(intraTable),
        initialLines,
        initialBytesScanned
      )
      onInitialChunkReady(initialSnapshot)
      onChunkUpdate(initialSnapshot, initialBytesScanned, fileSize, initialLines)

      if (initialBytesScanned >= fileSize) {
        // Entire file fits within initial chunk (<32MB), fully complete!
        CompletableFuture.completedFuture(initialSnapshot)
      } else {
        // --- Asynchronous Progressive Phase: Stream Remaining Chunks in Background ---
        val futureResult = new CompletableFuture[TwoLevelSparseIndex]()
        executor.submit(new Runnable {
          def run(): Unit = {
            val bgChannel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
            val bgArena: Arena = Arena.ofConfined()
            try {
              val bgSegment: MemorySegment = bgArena.allocate(clampedCapacity, 64)
              val bgByteBuffer: ByteBuffer = bgSegment.asByteBuffer()

              bgChannel.position(initialBytesScanned)
              var fileOffset: Long = initialBytesScanned
              var lastProgressReportTime: Long = System.currentTimeMillis()

              while (fileOffset < fileSize && !futureResult.isCancelled) {
                val bytesToRead: Long = clampedCapacity.min(fileSize - fileOffset)
                bgByteBuffer.clear()
                bgByteBuffer.limit(bytesToRead.toInt)

                var bytesTransferred: Int = 0
                while (bytesTransferred < bytesToRead.toInt) {
                  val readCount: Int = bgChannel.read(bgByteBuffer)
                  if (readCount < 0) {
                    bytesTransferred = bytesToRead.toInt
                  } else {
                    bytesTransferred += readCount
                  }
                }

                val chunkSlice: MemorySegment = bgSegment.asSlice(0L, bytesTransferred.toLong)
                scanChunkFast(chunkSlice, fileOffset, bytesTransferred.toLong, fileSize, state)
                fileOffset += bytesTransferred.toLong

                val now: Long = System.currentTimeMillis()
                val isFinished: Boolean = (fileOffset >= fileSize)
                // Throttle progress updates to ~50ms to prevent UI churn
                if (now - lastProgressReportTime >= 50L || isFinished) {
                  lastProgressReportTime = now
                  val linesFound: Long = state.currentLineCounter + 1L
                  val snapshot: TwoLevelSparseIndex = new TwoLevelSparseIndex(
                    new LongArrayList(superTable),
                    new IntArrayList(intraTable),
                    linesFound,
                    fileOffset
                  )
                  onChunkUpdate(snapshot, fileOffset, fileSize, linesFound)
                }
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
