package umbrix.index

import java.lang.foreign.MemorySegment
import java.util.function.LongConsumer

/**
 * Framing kernel that identifies record boundaries in binary or custom-delimited files.
 */
trait RecordBoundaryFramer {
  def supportsParallelChunkFraming: Boolean = false

  def scanRecordBoundaries(
    fileSegment: MemorySegment,
    startOffset: Long,
    length: Long,
    recordStartOffsetConsumer: LongConsumer
  ): Long

  def advanceRecordsFromCheckpoint(
    fileSegment: MemorySegment,
    fileSizeBytes: Long,
    checkpointByteOffset: Long,
    recordsToAdvance: Int
  ): Long

  def recordByteLength(
    fileSegment: MemorySegment,
    fileSizeBytes: Long,
    recordByteOffset: Long
  ): Long

  def extractTimestampMicros(
    fileSegment: MemorySegment,
    recordByteOffset: Long,
    recordLength: Long
  ): Long = Long.MinValue
}

/**
 * Formats raw record bytes into display text for the ~50 visible rows in the 64-bit viewport.
 */
trait RecordTextFormatter {
  def formatRecord(
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    maxChars: Int,
    targetBuilder: java.lang.StringBuilder
  ): Unit
}
