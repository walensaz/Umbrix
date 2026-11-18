package umbrix.index

import java.lang.foreign.MemorySegment
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.vfs.LogStorageReader

/**
 * High-performance record indexer for framed non-newline formats (e.g. PCAP, HPROF, FIX).
 *
 * Employs `TwoLevelSparseIndex` checkpoints (16,384 super-stride / 64 intra-stride)
 * combined with custom `RecordBoundaryFramer` and `RecordTextFormatter` delegates to provide
 * <1.5us coordinate translation and on-demand viewport text formatting without heap ingestion.
 */
class FramedRecordIndexerImpl(
  val storageReader: LogStorageReader,
  val sparseIndex: TwoLevelSparseIndex,
  val framer: RecordBoundaryFramer,
  val formatter: RecordTextFormatter
) extends PhysicalLineIndexer {

  override def indexedByteLimit: Long = sparseIndex.indexedBytes.min(storageReader.sizeBytes)

  def totalLines: LineIndex = LineIndex(sparseIndex.totalLinesCount)

  def byteOffsetForLine(lineIndex: LineIndex): ByteOffset = {
    val rawLine: Long = lineIndex.value
    if (rawLine <= 0L) {
      ByteOffset.Zero
    } else if (rawLine >= sparseIndex.totalLinesCount) {
      ByteOffset(storageReader.sizeBytes)
    } else {
      val superIndex: Int = SparseIndex.superblockIndex(rawLine)
      val intraIndex: Int = SparseIndex.intraSlotIndex(rawLine)
      val remainingLinesToScan: Int = SparseIndex.fineScanOffset(rawLine)

      val baseSuperOffset: Long = sparseIndex.superStrideOffsets.getLong(superIndex)
      val intraDeltaOffset: Long = SparseIndex.unsignedIntraDelta(
        sparseIndex.intraStrideDeltas.getInt(SparseIndex.intraTableOffset(superIndex, intraIndex))
      )
      val checkpointByteOffset: Long = baseSuperOffset + intraDeltaOffset

      val resultOffset: Long = if (remainingLinesToScan == 0) {
        checkpointByteOffset
      } else {
        val fileSlice: MemorySegment = storageReader.readSlice(0L, storageReader.sizeBytes)
        framer.advanceRecordsFromCheckpoint(fileSlice, storageReader.sizeBytes, checkpointByteOffset, remainingLinesToScan)
      }
      ByteOffset(resultOffset)
    }
  }

  def lineIndexForByteOffset(byteOffset: ByteOffset): LineIndex = {
    val offset: Long = byteOffset.value
    val fileSize: Long = storageReader.sizeBytes
    if (offset <= 0L || sparseIndex.totalLinesCount <= 0L) {
      LineIndex.Zero
    } else if (offset >= fileSize) {
      LineIndex((sparseIndex.totalLinesCount - 1L).max(0L))
    } else {
      // 1. Binary search Super-Stride table
      val resolvedSuperIndex: Int = SparseIndex.binarySearchFloor(0, sparseIndex.superStrideOffsets.size() - 1, offset) { mid =>
        sparseIndex.superStrideOffsets.getLong(mid)
      }

      val baseSuperOffset: Long = sparseIndex.superStrideOffsets.getLong(resolvedSuperIndex)
      val baseIntraArrayIndex: Int = SparseIndex.intraTableOffset(resolvedSuperIndex, 0)

      // 2. Binary search Intra-Stride table
      val maxIntraInSuperblock: Int = (sparseIndex.intraStrideDeltas.size() - 1 - baseIntraArrayIndex).min(255).max(0)
      val resolvedIntraIndex: Int = SparseIndex.binarySearchFloor(0, maxIntraInSuperblock, offset) { mid =>
        baseSuperOffset + SparseIndex.unsignedIntraDelta(sparseIndex.intraStrideDeltas.getInt(baseIntraArrayIndex + mid))
      }

      val checkpointRecordIndex: Long = SparseIndex.checkpointLineIndex(resolvedSuperIndex, resolvedIntraIndex)
      val checkpointByteOffset: Long = baseSuperOffset + SparseIndex.unsignedIntraDelta(
        sparseIndex.intraStrideDeltas.getInt(baseIntraArrayIndex + resolvedIntraIndex)
      )

      if (checkpointByteOffset == offset) {
        LineIndex(checkpointRecordIndex)
      } else {
        // Step forward from checkpointByteOffset using framer
        val fileSlice: MemorySegment = storageReader.readSlice(0L, storageReader.sizeBytes)
        var currentOffset: Long = checkpointByteOffset
        var currentRecord: Long = checkpointRecordIndex
        var advanced = true
        while (advanced && currentRecord < sparseIndex.totalLinesCount - 1L) {
          val recLen: Long = framer.recordByteLength(fileSlice, fileSize, currentOffset)
          val nextOffset = currentOffset + recLen
          if (nextOffset <= offset && recLen > 0L) {
            currentOffset = nextOffset
            currentRecord += 1L
          } else {
            advanced = false
          }
        }
        LineIndex(currentRecord)
      }
    }
  }

  def getLineSlice(lineIndex: LineIndex): MemorySegment = {
    if (lineIndex < LineIndex.Zero || lineIndex >= totalLines) {
      MemorySegment.NULL
    } else {
      val startOffset: ByteOffset = byteOffsetForLine(lineIndex)
      val fileSlice: MemorySegment = storageReader.readSlice(0L, storageReader.sizeBytes)
      val length: Long = framer.recordByteLength(fileSlice, storageReader.sizeBytes, startOffset.value)
      if (length <= 0L) {
        FramedRecordIndexerImpl.EmptySegment
      } else {
        storageReader.readSlice(startOffset.value, length)
      }
    }
  }

  def getLineText(lineIndex: LineIndex, maxCharacters: Int = 4096): String = {
    val slice: MemorySegment = getLineSlice(lineIndex)
    if (slice == MemorySegment.NULL || slice.byteSize() == 0L) {
      ""
    } else {
      val sb = new java.lang.StringBuilder(maxCharacters + 64)
      formatter.formatRecord(lineIndex.value, slice, maxCharacters, sb)
      val raw = sb.toString
      if (raw.length > maxCharacters) {
        raw.substring(0, maxCharacters) + s" [… +${raw.length - maxCharacters} chars clamped]"
      } else {
        raw
      }
    }
  }

  def close(): Unit = {
    storageReader.close()
  }
}

object FramedRecordIndexerImpl {

  private val EmptySegment: MemorySegment = MemorySegment.ofArray(new Array[Byte](0))

  def build(
    storageReader: LogStorageReader,
    framer: RecordBoundaryFramer,
    formatter: RecordTextFormatter
  ): FramedRecordIndexerImpl = {
    val superTable = new LongArrayList()
    val intraTable = new IntArrayList()
    val fileSize = storageReader.sizeBytes

    if (fileSize <= 0L) {
      superTable.add(0L)
      intraTable.add(0)
      val sparseIndex = new TwoLevelSparseIndex(superTable, intraTable, 0L, 0L)
      new FramedRecordIndexerImpl(storageReader, sparseIndex, framer, formatter)
    } else {
      superTable.add(0L)
      intraTable.add(0)
      var currentSuperblockBaseOffset: Long = 0L
      var currentRecordCounter: Long = 0L
      var recordsUntilNextIntraStride: Int = SparseIndex.IntraStrideLines
      var intraIndexInSuperblock: Int = 0

      val fileSlice = storageReader.readSlice(0L, fileSize)
      val recordStartOffsetConsumer = new java.util.function.LongConsumer {
        def accept(offset: Long): Unit = {
          if (offset > 0L && offset < fileSize) {
            currentRecordCounter += 1L
            recordsUntilNextIntraStride -= 1
            if (recordsUntilNextIntraStride == 0) {
              intraIndexInSuperblock += 1
              if (intraIndexInSuperblock == SparseIndex.IntraEntriesPerSuperblock) {
                currentSuperblockBaseOffset = offset
                superTable.add(offset)
                intraTable.add(0)
                intraIndexInSuperblock = 0
              } else {
                val rawDelta = offset - currentSuperblockBaseOffset
                val clampedDelta = rawDelta.min(0xFFFFFFFFL).toInt
                intraTable.add(clampedDelta)
              }
              recordsUntilNextIntraStride = SparseIndex.IntraStrideLines
            }
          }
        }
      }

      val totalScanned = framer.scanRecordBoundaries(fileSlice, 0L, fileSize, recordStartOffsetConsumer)
      val totalRecords = if (totalScanned > 0L) totalScanned else currentRecordCounter + 1L
      val sparseIndex = new TwoLevelSparseIndex(superTable, intraTable, totalRecords, fileSize)
      new FramedRecordIndexerImpl(storageReader, sparseIndex, framer, formatter)
    }
  }
}
