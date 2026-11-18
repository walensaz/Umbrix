package umbrix.index

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.vfs.LogStorageReader
import umbrix.vfs.MemoryMappedLogReader

class SparseIndexTest extends FunSuite {

  test("TwoLevelSparseIndex indexes small file (<64 lines) and resolves line offsets") {
    val linesCount: Int = 50
    val sb: java.lang.StringBuilder = new java.lang.StringBuilder()
    val expectedOffsets: Array[Long] = new Array[Long](linesCount)

    var currentOffset: Long = 0L
    for (index <- 0 until linesCount) {
      expectedOffsets(index) = currentOffset
      val lineStr: String = s"Log entry $index: payload timestamp=2026-09-22 10:00:00\n"
      sb.append(lineStr)
      currentOffset += lineStr.getBytes(StandardCharsets.UTF_8).length
    }

    val tempFile: File = File.createTempFile("umbrix-sparse-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.totalLines.value, linesCount.toLong)

      // Test every line offset
      for (lineIdx <- 0 until linesCount) {
        val resolvedOffset: ByteOffset = indexer.byteOffsetForLine(LineIndex(lineIdx.toLong))
        assertEquals(resolvedOffset.value, expectedOffsets(lineIdx), s"Mismatch at line $lineIdx")
        val lineText: String = indexer.getLineText(LineIndex(lineIdx.toLong))
        assert(lineText.startsWith(s"Log entry $lineIdx:"))
      }
    } finally {
      reader.close()
    }
  }

  test("TwoLevelSparseIndex spans superblock boundary (16,384+ lines)") {
    val linesCount: Int = 17000 // Spans superblock 0 (0..16383) and enters superblock 1
    val sb: java.lang.StringBuilder = new java.lang.StringBuilder()
    for (index <- 0 until linesCount) {
      sb.append(s"line_$index\n")
    }

    val tempFile: File = File.createTempFile("umbrix-superblock-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.totalLines.value, linesCount.toLong)

      // Check first line
      assertEquals(indexer.byteOffsetForLine(LineIndex(0L)).value, 0L)
      assert(indexer.getLineText(LineIndex(0L)).startsWith("line_0"))

      // Check boundary line 16,384 (first line of superblock 1)
      val line16384Text: String = indexer.getLineText(LineIndex(16384L))
      assert(line16384Text.startsWith("line_16384"))

      // Check last line
      val lastLineText: String = indexer.getLineText(LineIndex(16999L))
      assert(lastLineText.startsWith("line_16999"))
    } finally {
      reader.close()
    }
  }

  test("TemporalCheckpointTable parses timestamps and seeks nearest line") {
    val logContent: String =
      "2026-09-22 10:00:00.000 [INFO] Start\n" +
      "2026-09-22 10:05:00.000 [INFO] Progress\n" +
      "2026-09-22 10:10:00.000 [ERROR] Crash\n"

    val tempFile: File = File.createTempFile("umbrix-temporal-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, logContent.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      val slice: java.lang.foreign.MemorySegment = indexer.getLineSlice(LineIndex(0L))
      val parsedMicros: Option[Long] = TemporalCheckpointTable.tryParseTimestamp(slice)
      assert(parsedMicros.isDefined)
    } finally {
      reader.close()
    }
  }

  test("PieceTable appends text without modifying original reader") {
    val initialText: String = "Line 1\nLine 2\n"
    val tempFile: File = File.createTempFile("umbrix-piecetable-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, initialText.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    val pieceTable: PieceTable = new PieceTable(reader, 2L)
    try {
      assertEquals(pieceTable.totalLines, 2L)
      assertEquals(pieceTable.totalLength, initialText.getBytes(StandardCharsets.UTF_8).length.toLong)

      val extraText: String = "Line 3 [User Note]\n"
      pieceTable.appendText(extraText.getBytes(StandardCharsets.UTF_8), 1L)

      assertEquals(pieceTable.totalLines, 3L)
      assertEquals(pieceTable.pieceCount, 2)
    } finally {
      pieceTable.close()
      reader.close()
    }
  }

  test("TwoLevelSparseIndex normalizes carriage returns and expands tabs to spaces") {
    val crlfAndTabContent: String = "Line 1 with CRLF\r\n\tIndented with tab\r\n\t\tDouble tab\r\n"
    val tempFile: File = File.createTempFile("umbrix-tab-crlf-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, crlfAndTabContent.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.totalLines.value, 3L)

      val line0: String = indexer.getLineText(LineIndex(0L))
      assertEquals(line0, "Line 1 with CRLF")
      assert(!line0.endsWith("\r"))

      val line1: String = indexer.getLineText(LineIndex(1L))
      assertEquals(line1, "    Indented with tab")

      val line2: String = indexer.getLineText(LineIndex(2L))
      assertEquals(line2, "        Double tab")
    } finally {
      reader.close()
    }
  }

  test("TwoLevelSparseIndex sanitizes null bytes and control characters to spaces preserving character parity") {
    // Binary payload with null bytes \u0000 and control characters \u0001, \u001F, followed by text
    val rawBinaryBytes: Array[Byte] = Array[Byte](
      0x00, 0x00, 0x01, 0x1F, // 4 non-printable control/null bytes
      'K'.toByte, 'i'.toByte, 'l'.toByte, 'l'.toByte, 'R'.toByte, 'i'.toByte, 'n'.toByte, 'g'.toByte,
      0x0A.toByte // newline
    )
    val tempFile: File = File.createTempFile("umbrix-binary-null-test-", ".bin")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, rawBinaryBytes)

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.totalLines.value, 1L)

      val lineText: String = indexer.getLineText(LineIndex(0L))
      // Exactly 12 characters: 4 spaces followed by "KillRing"
      assertEquals(lineText.length, 12)
      assertEquals(lineText, "    KillRing")
      // Null byte \u0000 must NOT exist in lineText
      assert(!lineText.contains('\u0000'))
      // Exact substring index of "Kill" must be 4
      assertEquals(lineText.indexOf("Kill"), 4)
    } finally {
      reader.close()
    }
  }
}
