package umbrix.index

import munit.FunSuite
import java.io.File
import java.lang.foreign.MemorySegment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import umbrix.vfs.{LogStorageReader, MemoryMappedLogReader}

class PieceTableVirtualExpansionTest extends FunSuite {

  test("PieceTable supports non-destructive virtual expansions keyed by physical line") {
    val initialText = "Line 0: error happened\nLine 1: system shutdown\n"
    val tempFile = File.createTempFile("umbrix-piecetable-expansion-", ".log")
    tempFile.deleteOnExit()
    Files.write(tempFile.toPath, initialText.getBytes(StandardCharsets.UTF_8))

    val reader: LogStorageReader = MemoryMappedLogReader.open(tempFile.toPath)
    val pieceTable: PieceTable = new PieceTable(reader, 2L, chunkCapacity = 1024L)

    try {
      assert(!pieceTable.hasVirtualExpansion(0L))
      assert(!pieceTable.hasVirtualExpansion(1L))

      // Register virtual expansion on line 0
      val expansionText = "Caused by: java.lang.NullPointerException\n\tat com.foo.Bar.run(Bar.java:42)\n\tat com.foo.Main.main(Main.java:10)"
      pieceTable.putVirtualExpansionText(0L, expansionText)

      assert(pieceTable.hasVirtualExpansion(0L))
      assert(!pieceTable.hasVirtualExpansion(1L))

      val lines = pieceTable.getVirtualExpansionLines(0L)
      assertEquals(lines.length, 3)
      assertEquals(lines(0), "Caused by: java.lang.NullPointerException")
      assertEquals(lines(1), "\tat com.foo.Bar.run(Bar.java:42)")
      assertEquals(lines(2), "\tat com.foo.Main.main(Main.java:10)")

      // Register off-heap MemorySegment expansion on line 1
      val line1Payload = "Thread dump: TID=42 WAITING\n"
      val line1Bytes = line1Payload.getBytes(StandardCharsets.UTF_8)
      pieceTable.putVirtualExpansion(1L, MemorySegment.ofArray(line1Bytes), 1)

      assert(pieceTable.hasVirtualExpansion(1L))
      val line1Expansion = pieceTable.getVirtualExpansionLines(1L)
      assertEquals(line1Expansion.length, 1)
      assertEquals(line1Expansion(0), "Thread dump: TID=42 WAITING")

      // Clear line 0
      pieceTable.clearVirtualExpansion(0L)
      assert(!pieceTable.hasVirtualExpansion(0L))
      assertEquals(pieceTable.getVirtualExpansionLines(0L).length, 0)
      assert(pieceTable.hasVirtualExpansion(1L))
    } finally {
      pieceTable.close()
    }
  }
}
