package umbrix.index

import munit.FunSuite
import java.io.File
import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.function.LongConsumer
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.vfs.{LogStorageReader, MemoryMappedLogReader}

class FramedRecordIndexerTest extends FunSuite {

  test("FramedRecordIndexerImpl indexes fixed-size binary records and performs coordinate translation") {
    val recordSize = 16
    val recordCount = 100
    val totalBytes = recordSize * recordCount
    val data = new Array[Byte](totalBytes)
    for (i <- 0 until recordCount) {
      val recordHeader = f"REC_${i}%04d:OK_____".getBytes(StandardCharsets.US_ASCII)
      System.arraycopy(recordHeader, 0, data, i * recordSize, recordSize)
    }

    val tempFile = File.createTempFile("umbrix-framed-record-", ".bin")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, data)

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)

    val framer = new RecordBoundaryFramer {
      def scanRecordBoundaries(
        fileSegment: MemorySegment,
        startOffset: Long,
        length: Long,
        recordStartOffsetConsumer: LongConsumer
      ): Long = {
        var offset = recordSize.toLong
        while (offset < length) {
          recordStartOffsetConsumer.accept(offset)
          offset += recordSize.toLong
        }
        recordCount.toLong
      }

      def advanceRecordsFromCheckpoint(
        fileSegment: MemorySegment,
        fileSizeBytes: Long,
        checkpointByteOffset: Long,
        recordsToAdvance: Int
      ): Long = {
        checkpointByteOffset + (recordsToAdvance.toLong * recordSize.toLong)
      }

      def recordByteLength(
        fileSegment: MemorySegment,
        fileSizeBytes: Long,
        recordByteOffset: Long
      ): Long = {
        recordSize.toLong.min(fileSizeBytes - recordByteOffset)
      }
    }

    val formatter = new RecordTextFormatter {
      def formatRecord(
        recordIndex: Long,
        rawRecordSlice: MemorySegment,
        maxChars: Int,
        targetBuilder: java.lang.StringBuilder
      ): Unit = {
        val bytes = rawRecordSlice.toArray(ValueLayout.JAVA_BYTE)
        targetBuilder.append(new String(bytes, StandardCharsets.US_ASCII))
      }
    }

    val indexer = FramedRecordIndexerImpl.build(reader, framer, formatter)
    try {
      assertEquals(indexer.totalLines.value, 100L)
      assertEquals(indexer.byteOffsetForLine(LineIndex(0L)).value, 0L)
      assertEquals(indexer.byteOffsetForLine(LineIndex(1L)).value, 16L)
      assertEquals(indexer.byteOffsetForLine(LineIndex(42L)).value, 42L * 16L)
      assertEquals(indexer.byteOffsetForLine(LineIndex(99L)).value, 99L * 16L)

      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(0L)).value, 0L)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(16L)).value, 1L)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(42L * 16L)).value, 42L)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(42L * 16L + 8L)).value, 42L)

      val text0 = indexer.getLineText(LineIndex(0L))
      assert(text0.startsWith("REC_0000:OK____"))

      val text42 = indexer.getLineText(LineIndex(42L))
      assert(text42.startsWith("REC_0042:OK____"))
    } finally {
      indexer.close()
    }
  }
}
