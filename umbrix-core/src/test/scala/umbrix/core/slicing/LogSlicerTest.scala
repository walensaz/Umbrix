package umbrix.core.slicing

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.types.LineIndex
import umbrix.core.view.FilteredLineView
import umbrix.index.PhysicalLineIndexerImpl
import umbrix.vfs.LogStorageReader
import umbrix.vfs.MemoryMappedLogReader

class LogSlicerTest extends FunSuite {

  test("LogSlicer exports contiguous ranges and filtered subsets without modifying source") {
    val linesCount: Int = 10
    val sb: java.lang.StringBuilder = new java.lang.StringBuilder()
    for (i <- 0 until linesCount) {
      sb.append(s"line $i: payload data for slicing test\n")
    }

    val tempOriginal: File = File.createTempFile("umbrix-slicer-orig-", ".log")
    tempOriginal.deleteOnExit()
    Files.write(tempOriginal.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))

    val storageReader: LogStorageReader = MemoryMappedLogReader.open(tempOriginal.toPath)
    try {
      val indexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(storageReader)
      val slicer: LogSlicer = new LogSlicer(storageReader, indexer)

      // 1. Test Contiguous Range Export [2, 4]
      val tempContiguous: File = File.createTempFile("umbrix-slicer-range-", ".log")
      tempContiguous.deleteOnExit()
      val rangeResult = slicer.exportContiguousRange(LineIndex(2L), LineIndex(4L), tempContiguous.toPath)
      assertEquals(rangeResult.linesExported, 3L)

      // Verify the sub-file by indexing it directly
      val rangeReader: LogStorageReader = MemoryMappedLogReader.open(tempContiguous.toPath)
      try {
        val rangeIndexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(rangeReader)
        assertEquals(rangeIndexer.totalLines.value, 3L)
        assert(rangeIndexer.getLineText(LineIndex(0L)).startsWith("line 2:"))
        assert(rangeIndexer.getLineText(LineIndex(1L)).startsWith("line 3:"))
        assert(rangeIndexer.getLineText(LineIndex(2L)).startsWith("line 4:"))
      } finally {
        rangeReader.close()
      }

      // 2. Test Filtered Lines Export {3, 5}
      val matchingBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()
      matchingBitmap.addLong(3L)
      matchingBitmap.addLong(5L)

      // Verify FilteredLineView
      val filteredView: FilteredLineView = new FilteredLineView(matchingBitmap, indexer)
      assertEquals(filteredView.totalFilteredLines.value, 2L)
      assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(0L)).value, 3L)
      assertEquals(filteredView.physicalLineForVirtualIndex(LineIndex(1L)).value, 5L)
      assert(filteredView.getVirtualLineText(LineIndex(0L)).startsWith("line 3:"))
      assert(filteredView.getVirtualLineText(LineIndex(1L)).startsWith("line 5:"))

      val tempFiltered: File = File.createTempFile("umbrix-slicer-filtered-", ".log")
      tempFiltered.deleteOnExit()
      val filteredResult = slicer.exportFilteredLines(matchingBitmap, tempFiltered.toPath)
      assertEquals(filteredResult.linesExported, 2L)

      val filteredReader: LogStorageReader = MemoryMappedLogReader.open(tempFiltered.toPath)
      try {
        val filteredIndexer: PhysicalLineIndexerImpl = PhysicalLineIndexerImpl.build(filteredReader)
        assertEquals(filteredIndexer.totalLines.value, 2L)
        assert(filteredIndexer.getLineText(LineIndex(0L)).startsWith("line 3:"))
        assert(filteredIndexer.getLineText(LineIndex(1L)).startsWith("line 5:"))
      } finally {
        filteredReader.close()
      }

    } finally {
      storageReader.close()
    }
  }
}
