package umbrix.index

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.vfs.LogStorageReader
import umbrix.vfs.MemoryMappedLogReader

class TwoLevelSparseIndexReverseTest extends FunSuite {

  test("TwoLevelSparseIndex accurately reverse-maps byte offsets to line numbers for medium files") {
    val linesCount: Int = 300
    val sb: java.lang.StringBuilder = new java.lang.StringBuilder()
    val expectedOffsets: Array[Long] = new Array[Long](linesCount)
    val lineLengths: Array[Int] = new Array[Int](linesCount)

    var currentOffset: Long = 0L
    for (index <- 0 until linesCount) {
      expectedOffsets(index) = currentOffset
      val lineStr: String = s"Log entry $index: payload timestamp=2026-09-22 10:00:00, details=active-test\n"
      val lineBytesLength: Int = lineStr.getBytes(StandardCharsets.UTF_8).length
      lineLengths(index) = lineBytesLength
      sb.append(lineStr)
      currentOffset += lineBytesLength
    }

    val tempFile: File = File.createTempFile("umbrix-reverse-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.totalLines.value, linesCount.toLong)

      for (lineIdx <- 0 until linesCount) {
        val startOffset: Long = expectedOffsets(lineIdx)
        val len: Int = lineLengths(lineIdx)

        // Start of line must resolve to lineIdx
        val lineAtStart: LineIndex = indexer.lineIndexForByteOffset(ByteOffset(startOffset))
        assertEquals(lineAtStart.value, lineIdx.toLong, s"Start of line mismatch at line $lineIdx, offset $startOffset")

        // Middle of line must resolve to lineIdx
        val lineAtMid: LineIndex = indexer.lineIndexForByteOffset(ByteOffset(startOffset + (len / 2)))
        assertEquals(lineAtMid.value, lineIdx.toLong, s"Middle of line mismatch at line $lineIdx")

        // At the newline byte itself must resolve to lineIdx
        val lineAtNewline: LineIndex = indexer.lineIndexForByteOffset(ByteOffset(startOffset + len - 1L))
        assertEquals(lineAtNewline.value, lineIdx.toLong, s"Newline byte mismatch at line $lineIdx")
      }
    } finally {
      reader.close()
    }
  }

  test("TwoLevelSparseIndex reverse-maps across superblock boundaries (17,000+ lines)") {
    val linesCount: Int = 17000
    val sb: java.lang.StringBuilder = new java.lang.StringBuilder()
    for (index <- 0 until linesCount) {
      sb.append(s"line_$index\n")
    }

    val tempFile: File = File.createTempFile("umbrix-superblock-reverse-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.totalLines.value, linesCount.toLong)

      val probeLines: List[Long] = List(
        0L, 1L, 63L, 64L, 65L, 255L, 256L, 1023L, 1024L,
        16383L, 16384L, 16385L, 16500L, 16998L, 16999L
      )

      for (targetLine <- probeLines) {
        val byteOffset: ByteOffset = indexer.byteOffsetForLine(LineIndex(targetLine))
        val resolvedLine: LineIndex = indexer.lineIndexForByteOffset(byteOffset)
        assertEquals(resolvedLine.value, targetLine, s"Mismatch for target line $targetLine at offset $byteOffset")
      }
    } finally {
      reader.close()
    }
  }

  test("TwoLevelSparseIndex handles boundary and out-of-range offsets gracefully") {
    val tempFile: File = File.createTempFile("umbrix-edge-reverse-test-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, "single line without newline".getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(reader)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(-100L)).value, 0L)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(0L)).value, 0L)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(5L)).value, 0L)
      assertEquals(indexer.lineIndexForByteOffset(ByteOffset(reader.sizeBytes + 100L)).value, 0L)
    } finally {
      reader.close()
    }
  }
}
