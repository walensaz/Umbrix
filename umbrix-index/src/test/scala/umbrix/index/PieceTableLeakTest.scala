package umbrix.index

import munit.FunSuite
import java.io.File
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.vfs.{LogStorageReader, MemoryMappedLogReader}

class PieceTableLeakTest extends FunSuite {

  test("PieceTable chunked segments allocate without leaking or abandoning memory across multiple chunk boundaries") {
    val initialText: String = "Original log line 1\nOriginal log line 2\n"
    val tempFile: File = File.createTempFile("umbrix-piecetable-leak-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, initialText.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    // Use a small 256-byte chunk capacity to force allocation across multiple chunks
    val pieceTable: PieceTable = new PieceTable(reader, 2L, chunkCapacity = 256L)

    try {
      assertEquals(pieceTable.totalLines, 2L)
      assertEquals(pieceTable.totalLength, initialText.getBytes(StandardCharsets.UTF_8).length.toLong)
      assertEquals(pieceTable.allocatedChunkCount, 0) // No chunks until first append

      // Append 10 separate annotations, each 64 bytes (total 640 bytes -> spans 3 chunks of 256 bytes)
      val appendsCount: Int = 10
      val appendTextTemplate: String = "Annotation entry #%02d: testing piece table chunk boundary write\n"
      val allAppendedTextBuilder: StringBuilder = new StringBuilder()

      for (i <- 0 until appendsCount) {
        val entryText: String = f"$appendTextTemplate".format(i)
        allAppendedTextBuilder.append(entryText)
        pieceTable.appendText(entryText.getBytes(StandardCharsets.UTF_8), 1L)
      }

      assertEquals(pieceTable.totalLines, 2L + appendsCount.toLong)
      assertEquals(pieceTable.pieceCount, 1 + appendsCount) // 1 original + 10 appends
      // 640 bytes in 256-byte chunks requires ceil(640 / 256) = 3 chunks
      assertEquals(pieceTable.allocatedChunkCount, 3)

      // Verify content of each piece by reading via readPiece
      val confinedArena: Arena = Arena.ofConfined()
      try {
        // Read original piece
        val origPiece: Piece = pieceTable.getPiece(0)
        val origBuffer: MemorySegment = confinedArena.allocate(origPiece.length, 1)
        val origBytesRead: Long = pieceTable.readPiece(origPiece, origBuffer)
        assertEquals(origBytesRead, origPiece.length)
        val origReadString: String = new String(origBuffer.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
        assertEquals(origReadString, initialText)

        // Read all append pieces and verify
        val totalAppendBytes: Long = pieceTable.totalLength - origPiece.length
        val appendBuffer: MemorySegment = confinedArena.allocate(totalAppendBytes, 1)
        val appendBytesRead: Long = pieceTable.readAppendBytes(0L, totalAppendBytes, appendBuffer)
        assertEquals(appendBytesRead, totalAppendBytes)
        val appendsReadString: String = new String(appendBuffer.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
        assertEquals(appendsReadString, allAppendedTextBuilder.toString)
      } finally {
        confinedArena.close()
      }
    } finally {
      pieceTable.close()
      reader.close()
    }
  }

  test("PieceTable handles large single append spanning multiple chunks seamlessly") {
    val tempFile: File = File.createTempFile("umbrix-piecetable-large-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, "Header\n".getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    // 512-byte chunks
    val pieceTable: PieceTable = new PieceTable(reader, 1L, chunkCapacity = 512L)

    try {
      // Create a 2,000-byte single payload that spans ~4 chunks
      val largePayload: String = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789\n" * 55)
      val payloadBytes: Array[Byte] = largePayload.getBytes(StandardCharsets.UTF_8)
      assert(payloadBytes.length > 1500)

      pieceTable.appendText(payloadBytes, 55L)

      assertEquals(pieceTable.totalLines, 56L)
      assertEquals(pieceTable.pieceCount, 2)
      assert(pieceTable.allocatedChunkCount >= 3)

      val confinedArena: Arena = Arena.ofConfined()
      try {
        val destBuffer: MemorySegment = confinedArena.allocate(payloadBytes.length.toLong, 1)
        val bytesRead: Long = pieceTable.readPiece(pieceTable.getPiece(1), destBuffer)
        assertEquals(bytesRead, payloadBytes.length.toLong)
        val readPayload: String = new String(destBuffer.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
        assertEquals(readPayload, largePayload)
      } finally {
        confinedArena.close()
      }
    } finally {
      pieceTable.close()
      reader.close()
    }
  }
}
