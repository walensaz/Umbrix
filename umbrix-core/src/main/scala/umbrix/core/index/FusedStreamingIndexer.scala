package umbrix.core.index

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import jdk.incubator.vector.{ByteVector, VectorMask, VectorOperators}
import org.roaringbitmap.longlong.Roaring64NavigableMap
import org.slf4j.LoggerFactory
import umbrix.core.classifier.EventBoundaryClassifier
import umbrix.core.types.LineIndex
import umbrix.index.{TemporalCheckpointTable, TwoLevelSparseIndex}
import umbrix.vfs.LogStorageReader
import umbrix.vfs.platform.{PagingAdvisory, PlatformMemoryAdvisor}

/**
 * Result bundle produced by single-pass fused indexing.
 */
final case class FusedIndexingResult(
  sparseIndex: TwoLevelSparseIndex,
  temporalTable: TemporalCheckpointTable,
  continuationBitmap: Roaring64NavigableMap
)

/**
 * Mutable state accumulator for single-pass fused indexing.
 */
private final class FusedBuildState(
  val superTable: LongArrayList,
  val intraTable: IntArrayList,
  val temporalLines: LongArrayList,
  val temporalMicros: LongArrayList,
  val continuationBitmap: Roaring64NavigableMap
) {
  var currentSuperblockBaseOffset: Long = 0L
  var currentLineCounter: Long = 0L
  var linesUntilNextIntraStride: Int = 64
  var intraIndexInSuperblock: Int = 0
  var temporalProbeLinesRemaining: Int = 0
  var detectedTemporalFormat: Int = TemporalCheckpointTable.FormatUnknown

  private var runStart: Long = -1L
  private var runEndExclusive: Long = -1L

  def recordContinuation(line: Long): Unit = {
    if (runStart < 0L) {
      runStart = line
      runEndExclusive = line + 1L
    } else if (line == runEndExclusive) {
      runEndExclusive = line + 1L
    } else {
      flushContinuationRun()
      runStart = line
      runEndExclusive = line + 1L
    }
  }

  def flushContinuationRun(): Unit = {
    if (runStart >= 0L) {
      if (runEndExclusive - runStart == 1L) {
        continuationBitmap.addLong(runStart)
      } else {
        continuationBitmap.addRange(runStart, runEndExclusive)
      }
      runStart = -1L
      runEndExclusive = -1L
    }
  }
}

/**
 * Single-pass fused sequential streaming indexer.
 *
 * Simultaneously constructs:
 * 1. Two-Level Sparse Index (`TwoLevelSparseIndex`: Level 1 Super-stride 16,384 lines, Level 2 Intra-stride 64 lines)
 * 2. Sampled Temporal Checkpoint Table (`TemporalCheckpointTable`: sampled every 8,192 lines)
 * 3. Logical Event Boundary Classification (`continuationLinesBitmap` in `Roaring64NavigableMap`)
 *
 * Exploits mathematical stride harmony (64 intra, 8,192 temporal = 128 * 64, 16,384 super = 256 * 64)
 * and vectorized first-byte continuation testing to preserve single-cycle POPCNT on >98% of vector lanes.
 *
 * Utilizes double-buffered ping-pong prefetching to hide NVMe read latency behind CPU SIMD execution.
 */
object FusedStreamingIndexer {

  private val logger = LoggerFactory.getLogger(FusedStreamingIndexer.getClass)
  private final val MaxUnsignedIntraDelta: Long = 0xFFFFFFFFL

  @inline
  private def recordStrideBoundary(state: FusedBuildState, nextLineStartOffset: Long): Unit = {
    state.intraIndexInSuperblock += 1
    if (state.intraIndexInSuperblock == 256) {
      state.currentSuperblockBaseOffset = nextLineStartOffset
      state.superTable.add(nextLineStartOffset)
      state.intraTable.add(0)
      state.intraIndexInSuperblock = 0
    } else {
      val rawDelta: Long = nextLineStartOffset - state.currentSuperblockBaseOffset
      val clampedDelta: Int = rawDelta.min(MaxUnsignedIntraDelta).toInt
      state.intraTable.add(clampedDelta)
    }
    state.linesUntilNextIntraStride = 64
  }

  private def readSliceFromFile(
    storageReader: LogStorageReader,
    channel: FileChannel,
    offset: Long,
    length: Long
  ): MemorySegment = {
    if (storageReader != null) {
      storageReader.readSlice(offset, length)
    } else {
      val buf: ByteBuffer = ByteBuffer.allocate(length.toInt)
      channel.read(buf, offset)
      buf.flip()
      MemorySegment.ofBuffer(buf)
    }
  }

  /** Reads up to `bytesToRead` bytes into `buffer`, stopping cleanly if EOF is reached early. */
  private def readChannelFully(
    fileChannel: FileChannel,
    buffer: ByteBuffer,
    fileOffset: Long,
    bytesToRead: Int
  ): Int = {
    buffer.clear()
    buffer.limit(bytesToRead)
    var transferred: Int = 0
    var eofReached: Boolean = false
    while (transferred < bytesToRead && !eofReached) {
      val bytesRead: Int = fileChannel.read(buffer, fileOffset + transferred.toLong)
      if (bytesRead < 0) {
        eofReached = true
      } else {
        transferred += bytesRead
      }
    }
    transferred
  }

  private def submitAsyncRead(
    fileChannel: FileChannel,
    buffer: ByteBuffer,
    offset: Long,
    length: Long,
    executor: ExecutorService
  ): CompletableFuture[Int] = {
    CompletableFuture.supplyAsync(
      () => readChannelFully(fileChannel, buffer, offset, length.toInt),
      executor
    )
  }

  private def trimWorkingSetSafely(storageReader: LogStorageReader): Unit = {
    if (storageReader != null) {
      try { storageReader.trimWorkingSet() } catch { case _: Throwable => () }
    } else {
      PagingAdvisory.trimWorkingSet()
    }
  }

  private def buildFinalResult(state: FusedBuildState): FusedIndexingResult = {
    state.flushContinuationRun()
    val totalLines = state.currentLineCounter + 1L
    val finalIndex = new TwoLevelSparseIndex(state.superTable, state.intraTable, totalLines)
    val hasDate: Boolean = state.detectedTemporalFormat != TemporalCheckpointTable.FormatTimeOfDay
    val finalTemporal = new TemporalCheckpointTable(state.temporalLines, state.temporalMicros, LineIndex(totalLines), hasDate)
    FusedIndexingResult(finalIndex, finalTemporal, state.continuationBitmap)
  }

  private inline def trySampleTimestampAtLine(
    chunkSlice: MemorySegment,
    nextLineRelOffset: Long,
    nextLineStartOffset: Long,
    remainingInChunk: Long,
    fileSize: Long,
    currentLine: Long,
    resetProbeOnMiss: Boolean,
    state: FusedBuildState,
    channel: FileChannel,
    storageReader: LogStorageReader
  ): Unit = {
    val sampleLength: Long = math.min(128L, fileSize - nextLineStartOffset)
    val lineSlice: MemorySegment = if (remainingInChunk >= sampleLength) {
      chunkSlice.asSlice(nextLineRelOffset, sampleLength)
    } else {
      readSliceFromFile(storageReader, channel, nextLineStartOffset, sampleLength)
    }
    TemporalCheckpointTable.tryParseTimestamp(lineSlice, state.detectedTemporalFormat) match {
      case Some(micros) =>
        if (state.detectedTemporalFormat == TemporalCheckpointTable.FormatUnknown) {
          val detected = TemporalCheckpointTable.detectLineFormat(lineSlice)
          if (detected != TemporalCheckpointTable.FormatUnknown) {
            state.detectedTemporalFormat = detected
          }
        }
        state.temporalLines.add(currentLine)
        state.temporalMicros.add(micros)
        state.temporalProbeLinesRemaining = 0
      case None =>
        if (resetProbeOnMiss) {
          state.temporalProbeLinesRemaining = 15
        } else {
          state.temporalProbeLinesRemaining -= 1
        }
    }
  }

  private inline def checkContinuationLine(
    chunkSlice: MemorySegment,
    nextLineRelOffset: Long,
    nextLineStartOffset: Long,
    remainingInChunk: Long,
    fileSize: Long,
    channel: FileChannel,
    storageReader: LogStorageReader
  ): Boolean = {
    val firstByte: Byte = if (remainingInChunk > 0L) {
      chunkSlice.get(ValueLayout.JAVA_BYTE, nextLineRelOffset)
    } else {
      readSliceFromFile(storageReader, channel, nextLineStartOffset, 1L).get(ValueLayout.JAVA_BYTE, 0L)
    }

    if (firstByte != '\t'.toByte && firstByte != ' '.toByte &&
        firstByte != 'a'.toByte && firstByte != '.'.toByte &&
        firstByte != 'C'.toByte && firstByte != 'S'.toByte) {
      false
    } else if (remainingInChunk >= 12L) {
      !EventBoundaryClassifier.isEventHeaderPrefix(chunkSlice, nextLineRelOffset, remainingInChunk)
    } else {
      val remainingInFile = fileSize - nextLineStartOffset
      if (remainingInFile <= 0L) {
        false
      } else {
        val edgeSlice = readSliceFromFile(storageReader, channel, nextLineStartOffset, remainingInFile.min(12L))
        !EventBoundaryClassifier.isEventHeaderPrefix(edgeSlice, 0L, edgeSlice.byteSize())
      }
    }
  }

  private inline def handleNewlineBoundary(
    chunkSlice: MemorySegment,
    chunkBaseOffset: Long,
    chunkLength: Long,
    fileSize: Long,
    newlineRelOffset: Long,
    state: FusedBuildState,
    channel: FileChannel,
    storageReader: LogStorageReader
  ): Unit = {
    val nextLineRelOffset: Long = newlineRelOffset + 1L
    val nextLineStartOffset: Long = chunkBaseOffset + nextLineRelOffset

    if (nextLineStartOffset < fileSize) {
      state.currentLineCounter += 1L
      state.linesUntilNextIntraStride -= 1
      val currentLine: Long = state.currentLineCounter
      val remainingInChunk: Long = chunkLength - nextLineRelOffset

      if (state.linesUntilNextIntraStride == 0) {
        recordStrideBoundary(state, nextLineStartOffset)
        if ((currentLine & 8191L) == 0L) {
          trySampleTimestampAtLine(
            chunkSlice, nextLineRelOffset, nextLineStartOffset, remainingInChunk,
            fileSize, currentLine, resetProbeOnMiss = true, state, channel, storageReader
          )
        }
      } else if (state.temporalProbeLinesRemaining > 0) {
        trySampleTimestampAtLine(
          chunkSlice, nextLineRelOffset, nextLineStartOffset, remainingInChunk,
          fileSize, currentLine, resetProbeOnMiss = false, state, channel, storageReader
        )
      }

      if (checkContinuationLine(chunkSlice, nextLineRelOffset, nextLineStartOffset, remainingInChunk, fileSize, channel, storageReader)) {
        state.recordContinuation(currentLine)
      } else {
        state.flushContinuationRun()
      }
    }
  }

  /**
   * Scans a single off-heap memory segment slice, simultaneously evaluating newlines,
   * stride coordinates, temporal checkpoints, and continuation line boundaries.
   */
  private def scanChunkFused(
    chunkSlice: MemorySegment,
    chunkBaseOffset: Long,
    chunkLength: Long,
    fileSize: Long,
    state: FusedBuildState,
    channel: FileChannel,
    storageReader: LogStorageReader
  ): Unit = {
    if (chunkLength > 0L) {
      val preferredSpecies = ByteVector.SPECIES_PREFERRED
      val vectorByteWidth = preferredSpecies.vectorBitSize() / 8
      val targetByte: Byte = 0x0A.toByte
      val nativeByteOrder = java.nio.ByteOrder.nativeOrder()

      // Ensure nextByteChunk can be loaded safely at currentByteOffset + 1L
      val vectorLoopBoundOffset: Long = (chunkLength - vectorByteWidth.toLong - 1L).max(0L)
      var currentByteOffset: Long = 0L

      while (currentByteOffset < vectorLoopBoundOffset) {
        val vectorChunk: ByteVector = ByteVector.fromMemorySegment(
          preferredSpecies,
          chunkSlice,
          currentByteOffset,
          nativeByteOrder
        )
        val matchMask: VectorMask[java.lang.Byte] = vectorChunk.eq(targetByte)
        val nlMask: Long = matchMask.toLong

        if (nlMask != 0L) {
          val nextByteChunk: ByteVector = ByteVector.fromMemorySegment(
            preferredSpecies,
            chunkSlice,
            currentByteOffset + 1L,
            nativeByteOrder
          )
          val isWhitespaceOrControl: VectorMask[java.lang.Byte] =
            nextByteChunk.compare(VectorOperators.ULE, 0x20.toByte)
          val isContinuationChar: VectorMask[java.lang.Byte] = isWhitespaceOrControl
            .or(nextByteChunk.eq('a'.toByte))
            .or(nextByteChunk.eq('.'.toByte))
            .or(nextByteChunk.eq('C'.toByte))
            .or(nextByteChunk.eq('S'.toByte))
          val hasContinuationCandidate: Boolean = (nlMask & isContinuationChar.toLong) != 0L
          val matchesInVector: Int = java.lang.Long.bitCount(nlMask)

          if (!hasContinuationCandidate &&
              matchesInVector < state.linesUntilNextIntraStride &&
              state.temporalProbeLinesRemaining == 0 &&
              (chunkBaseOffset + currentByteOffset + vectorByteWidth.toLong + 1L) < fileSize) {
            // POPCNT fast path (2 CPU cycles): No continuation candidates, no stride boundary, no temporal probe
            state.flushContinuationRun()
            state.currentLineCounter += matchesInVector.toLong
            state.linesUntilNextIntraStride -= matchesInVector
          } else {
            // Slow path: decode individual newlines via TZCNT / BLSR
            var mask: Long = nlMask
            while (mask != 0L) {
              val relativeMatchLane: Int = java.lang.Long.numberOfTrailingZeros(mask)
              handleNewlineBoundary(
                chunkSlice, chunkBaseOffset, chunkLength, fileSize,
                currentByteOffset + relativeMatchLane.toLong, state, channel, storageReader
              )
              mask &= (mask - 1L)
            }
          }
        }

        currentByteOffset += vectorByteWidth.toLong
      }

      // Scalar tail loop for remaining unaligned bytes
      while (currentByteOffset < chunkLength) {
        if (chunkSlice.get(ValueLayout.JAVA_BYTE, currentByteOffset) == targetByte) {
          handleNewlineBoundary(
            chunkSlice, chunkBaseOffset, chunkLength, fileSize,
            currentByteOffset, state, channel, storageReader
          )
        }
        currentByteOffset += 1L
      }
    }
  }

  /**
   * Synchronously builds a complete FusedIndexingResult via sequential single-pass streaming.
   */
  def buildStreaming(
    path: Path,
    storageReader: LogStorageReader = null,
    bufferCapacity: Long = PlatformMemoryAdvisor.MaximumStreamingBufferBytes
  ): FusedIndexingResult = {
    val future = buildStreamingProgressive(
      path = path,
      storageReader = storageReader,
      onInitialChunkReady = _ => (),
      onChunkUpdate = (_, _, _, _) => (),
      bufferCapacity = bufferCapacity,
      executor = Executors.newVirtualThreadPerTaskExecutor()
    )
    future.join()
  }

  /**
   * Progressively builds the TwoLevelSparseIndex, TemporalCheckpointTable, and EventBoundaryClassifier
   * continuation bitmap simultaneously in a single sequential streaming pass.
   *
   * Scans Chunk 0 synchronously (<10ms) and invokes `onInitialChunkReady` for instant viewport presentation.
   * Streams remaining chunks with double-buffered ping-pong prefetching on a background fiber.
   */
  def buildStreamingProgressive(
    path: Path,
    storageReader: LogStorageReader = null,
    onInitialChunkReady: TwoLevelSparseIndex => Unit = _ => (),
    onChunkUpdate: (TwoLevelSparseIndex, Long, Long, Long) => Unit = (_, _, _, _) => (),
    bufferCapacity: Long = PlatformMemoryAdvisor.MaximumStreamingBufferBytes,
    executor: ExecutorService = null
  ): CompletableFuture[FusedIndexingResult] = {
    val fileChannel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
    val fileSize: Long = try {
      fileChannel.size()
    } catch {
      case e: Throwable =>
        fileChannel.close()
        throw e
    }

    if (fileSize <= 0L) {
      fileChannel.close()
      val emptyIndex = new TwoLevelSparseIndex(new LongArrayList(0), new IntArrayList(0), 0L)
      val emptyTemporal = TemporalCheckpointTable.empty
      val emptyContinuation = new Roaring64NavigableMap()
      val emptyResult = FusedIndexingResult(emptyIndex, emptyTemporal, emptyContinuation)
      onInitialChunkReady(emptyIndex)
      onChunkUpdate(emptyIndex, 0L, 0L, 0L)
      CompletableFuture.completedFuture(emptyResult)
    } else {
      val ownsExecutor: Boolean = (executor == null)
      val activeExecutor: ExecutorService = if (ownsExecutor) {
        Executors.newVirtualThreadPerTaskExecutor()
      } else {
        executor
      }
      val estimatedLines: Long = (fileSize / 100L).max(1024L)
      val estimatedSuperBlocks: Int = ((estimatedLines >>> 14) + 16L).toInt
      val estimatedIntraEntries: Int = estimatedSuperBlocks * 256
      val estimatedCheckpoints: Int = ((estimatedLines / 8192L) + 16L).toInt

      val superTable = new LongArrayList(estimatedSuperBlocks)
      val intraTable = new IntArrayList(estimatedIntraEntries)
      val temporalLines = new LongArrayList(estimatedCheckpoints)
      val temporalMicros = new LongArrayList(estimatedCheckpoints)
      val continuationBitmap = new Roaring64NavigableMap()

      val state = new FusedBuildState(superTable, intraTable, temporalLines, temporalMicros, continuationBitmap)
      state.superTable.add(0L)
      state.intraTable.add(0)

      val clampedCapacity: Long = bufferCapacity.max(1024L * 1024L).min(fileSize.max(1024L * 1024L))
      val sharedArena = Arena.ofShared()

      try {
        val segmentA = sharedArena.allocate(clampedCapacity, 64)
        val segmentB = sharedArena.allocate(clampedCapacity, 64)
        val byteBufA = segmentA.asByteBuffer()
        val byteBufB = segmentB.asByteBuffer()

        // 1. Scan Chunk 0 synchronously on the caller thread (<10ms)
        val chunk0BytesToRead: Int = clampedCapacity.min(fileSize).toInt
        val transferred0: Int = readChannelFully(fileChannel, byteBufA, 0L, chunk0BytesToRead)

        val slice0 = segmentA.asSlice(0L, transferred0.toLong)

        val headerDetectLen: Long = math.min(65536L, transferred0.toLong)
        state.detectedTemporalFormat = TemporalCheckpointTable.detectDominantFormatFromSegment(slice0.asSlice(0L, headerDetectLen))

        // Check Line 0 timestamp once at byte 0
        val sample0Len: Long = math.min(128L, transferred0.toLong)
        val sample0Slice = slice0.asSlice(0L, sample0Len)
        TemporalCheckpointTable.tryParseTimestamp(sample0Slice, state.detectedTemporalFormat) match {
          case Some(micros) =>
            if (state.detectedTemporalFormat == TemporalCheckpointTable.FormatUnknown) {
              val detected = TemporalCheckpointTable.detectLineFormat(sample0Slice)
              if (detected != TemporalCheckpointTable.FormatUnknown) {
                state.detectedTemporalFormat = detected
              }
            }
            state.temporalLines.add(0L)
            state.temporalMicros.add(micros)
            state.temporalProbeLinesRemaining = 0
          case None =>
            state.temporalProbeLinesRemaining = 15
        }

        val effectiveEndOffset: Long = if (transferred0 < chunk0BytesToRead) transferred0.toLong else fileSize
        scanChunkFused(slice0, 0L, transferred0.toLong, effectiveEndOffset, state, fileChannel, storageReader)

        val snapSuper = state.superTable.clone()
        val snapIntra = state.intraTable.clone()
        val snapLines = state.currentLineCounter + 1L
        val snapshot0 = new TwoLevelSparseIndex(snapSuper, snapIntra, snapLines, transferred0.toLong)
        onInitialChunkReady(snapshot0)
        onChunkUpdate(snapshot0, transferred0.toLong, fileSize, snapLines)

        if (transferred0 < chunk0BytesToRead || transferred0.toLong >= fileSize) {
          // File fits entirely in Chunk 0 (or truncated cleanly before EOF)
          val result = buildFinalResult(state)
          sharedArena.close()
          fileChannel.close()
          trimWorkingSetSafely(storageReader)
          if (ownsExecutor) {
            try { activeExecutor.shutdown() } catch { case _: Throwable => () }
          }
          CompletableFuture.completedFuture(result)
        } else {
          // 2. Stream remaining chunks with double-buffered ping-pong prefetching in background
          val resultFuture = new CompletableFuture[FusedIndexingResult]()

          activeExecutor.submit(new Runnable {
            def run(): Unit = {
              try {
                var activeSegment = segmentA
                var standbySegment = segmentB
                var activeBuf = byteBufA
                var standbyBuf = byteBufB
                var scannedOffset: Long = transferred0.toLong
                var reachedEof: Boolean = false

                // Start async prefetch for Chunk 1 into standbyBuf
                var pendingBytesExpected: Long = clampedCapacity.min(fileSize - scannedOffset)
                var nextReadFuture: CompletableFuture[Int] = submitAsyncRead(fileChannel, standbyBuf, scannedOffset, pendingBytesExpected, activeExecutor)

                var lastProgressReportTime: Long = System.currentTimeMillis()

                while (scannedOffset < fileSize && !reachedEof && !Thread.currentThread().isInterrupted) {
                  val bytesRead: Int = nextReadFuture.join()
                  val currentChunkBase: Long = scannedOffset
                  val currentChunkLen: Long = bytesRead.toLong
                  val truncatedAtEof: Boolean = currentChunkLen < pendingBytesExpected
                  val nextOffset: Long = currentChunkBase + currentChunkLen

                  // Swap active and standby buffers
                  val tempSeg = activeSegment
                  activeSegment = standbySegment
                  standbySegment = tempSeg

                  val tempBuf = activeBuf
                  activeBuf = standbyBuf
                  standbyBuf = tempBuf

                  // Immediately submit async prefetch for the NEXT chunk (k+1) into standbyBuf
                  if (!truncatedAtEof && nextOffset < fileSize) {
                    pendingBytesExpected = clampedCapacity.min(fileSize - nextOffset)
                    nextReadFuture = submitAsyncRead(fileChannel, standbyBuf, nextOffset, pendingBytesExpected, activeExecutor)
                  } else {
                    reachedEof = truncatedAtEof
                  }

                  if (currentChunkLen > 0L) {
                    val scanEndOffset: Long = if (truncatedAtEof) nextOffset else fileSize
                    val chunkSlice = activeSegment.asSlice(0L, currentChunkLen)
                    scanChunkFused(chunkSlice, currentChunkBase, currentChunkLen, scanEndOffset, state, fileChannel, storageReader)
                  }
                  scannedOffset = nextOffset

                  val now: Long = System.currentTimeMillis()
                  val isFinished: Boolean = (scannedOffset >= fileSize || reachedEof)
                  val linesFound: Long = state.currentLineCounter + 1L
                  val shouldSnapshot: Boolean = (now - lastProgressReportTime >= 500L || isFinished)
                  val snap: TwoLevelSparseIndex = if (shouldSnapshot) {
                    lastProgressReportTime = now
                    new TwoLevelSparseIndex(state.superTable.clone(), state.intraTable.clone(), linesFound, scannedOffset)
                  } else {
                    null
                  }
                  onChunkUpdate(snap, scannedOffset, fileSize, linesFound)
                }

                resultFuture.complete(buildFinalResult(state))
              } catch {
                case e: Throwable =>
                  resultFuture.completeExceptionally(e)
              } finally {
                try {
                  sharedArena.close()
                } catch {
                  case _: Throwable => ()
                }
                try {
                  fileChannel.close()
                } catch {
                  case _: Throwable => ()
                }
                trimWorkingSetSafely(storageReader)
                if (ownsExecutor) {
                  try {
                    activeExecutor.shutdown()
                  } catch {
                    case _: Throwable => ()
                  }
                }
              }
            }
          })

          resultFuture
        }
      } catch {
        case e: Throwable =>
          sharedArena.close()
          fileChannel.close()
          if (ownsExecutor) {
            try { activeExecutor.shutdown() } catch { case _: Throwable => () }
          }
          throw e
      }
    }
  }
}
