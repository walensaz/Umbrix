package umbrix.core.search

import java.nio.file.Files
import munit.FunSuite
import umbrix.index.PhysicalLineIndexerImpl
import umbrix.vfs.MemoryMappedLogReader

class RegexChunkBoundaryTest extends FunSuite {

  private val lineByteLength: Int = 100
  private val boundaryLineIndex: Int = 167772 // line containing the 16 MiB chunk edge at byte offset 16777216
  private val totalLineCount: Int = 200000

  private def overlay(content: Array[Byte], lineIndex: Int, columnOffset: Int, text: String): Unit = {
    val textBytes: Array[Byte] = text.getBytes("US-ASCII")
    System.arraycopy(textBytes, 0, content, lineIndex * lineByteLength + columnOffset, textBytes.length)
  }

  test("regex anchors and boundary-straddling matches are evaluated per line, not per 16MB chunk") {
    val content: Array[Byte] = new Array[Byte](totalLineCount * lineByteLength)
    java.util.Arrays.fill(content, 'x'.toByte)
    (0 until totalLineCount).foreach { lineIndex => content(lineIndex * lineByteLength + lineByteLength - 1) = '\n'.toByte }

    // Token begins 6 bytes before the chunk edge, so it straddles it.
    overlay(content, boundaryLineIndex, 10, "BOUNDARYTOKEN")
    overlay(content, boundaryLineIndex + 1, 0, "ANCHOR")

    val logFile = Files.createTempFile("umbrix-regex-boundary-", ".log")
    logFile.toFile.deleteOnExit()
    Files.write(logFile, content)

    val storageReader = MemoryMappedLogReader.open(logFile)
    val indexer = PhysicalLineIndexerImpl.build(storageReader)
    val searchEngine = new SimdSearchEngine(storageReader, indexer, None, 4)
    try {
      def matchedLines(query: String): List[Long] = {
        val result = searchEngine.search(query).toOption.get
        val iterator = result.matchingLines.getLongIterator
        Iterator.continually(iterator).takeWhile(_.hasNext).map(_.next()).toList
      }

      assertEquals(matchedLines("regex:BOUNDARYTOKEN"), List(boundaryLineIndex.toLong))
      assertEquals(matchedLines("regex:^ANCHOR"), List((boundaryLineIndex + 1).toLong))
      // Text that merely begins at the chunk edge must not satisfy a line-start anchor.
      assertEquals(matchedLines("regex:^RYTOKEN"), Nil)
    } finally {
      searchEngine.close()
      storageReader.close()
    }
  }
}
