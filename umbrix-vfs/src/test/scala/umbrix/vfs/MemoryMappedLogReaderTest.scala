package umbrix.vfs

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets

class MemoryMappedLogReaderTest extends FunSuite {

  test("MemoryMappedLogReader accurately reads slices and respects bounds") {
    val temporaryFile = Files.createTempFile("umbrix_mmap_test", ".log")
    val logContent = "Line 1: 0123456789\nLine 2: ABCDEFGHIJKLMNOPQRSTUVWXYZ\nLine 3: End of file.\n"
    Files.writeString(temporaryFile, logContent, StandardCharsets.UTF_8)
    val expectedBytes = logContent.getBytes(StandardCharsets.UTF_8)

    val storageReader = MemoryMappedLogReader.open(temporaryFile)
    try {
      assertEquals(storageReader.sizeBytes, expectedBytes.length.toLong)
      assertEquals(storageReader.path, temporaryFile)

      val initialSlice = storageReader.readSlice(0, 6)
      assertEquals(initialSlice.byteSize(), 6L)
      for (byteIndex <- 0 until 6) {
        assertEquals(initialSlice.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), expectedBytes(byteIndex))
      }

      val middleSliceOffset = 19L
      val middleSlice = storageReader.readSlice(middleSliceOffset, 6)
      assertEquals(middleSlice.get(ValueLayout.JAVA_BYTE, 0), 'L'.toByte)
      assertEquals(middleSlice.get(ValueLayout.JAVA_BYTE, 5), '2'.toByte)

      val zeroLengthSlice = storageReader.readSlice(10, 0)
      assertEquals(zeroLengthSlice, MemorySegment.NULL)

      val confinedArena = Arena.ofConfined()
      try {
        val destinationBuffer = confinedArena.allocate(10, 1)
        val copiedByteCount = storageReader.readInto(middleSliceOffset, 10, destinationBuffer)
        assertEquals(copiedByteCount, 10L)
        for (byteIndex <- 0 until 10) {
          assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), expectedBytes(middleSliceOffset.toInt + byteIndex))
        }
      } finally {
        confinedArena.close()
      }

      storageReader.prefetch(0, 50)

      intercept[IndexOutOfBoundsException] {
        storageReader.readSlice(-1, 5)
      }
      intercept[IllegalArgumentException] {
        storageReader.readSlice(0, -5)
      }
      intercept[IndexOutOfBoundsException] {
        storageReader.readSlice(0, storageReader.sizeBytes + 1)
      }
      intercept[IndexOutOfBoundsException] {
        storageReader.readSlice(storageReader.sizeBytes - 5, 10)
      }
    } finally {
      storageReader.close()
      Files.deleteIfExists(temporaryFile)
    }

    intercept[IllegalStateException] {
      storageReader.readSlice(0, 5)
    }
  }

  test("MemoryMappedLogReader handles empty (0-byte) files gracefully") {
    val emptyFile = Files.createTempFile("umbrix_empty_mmap", ".log")
    val storageReader = MemoryMappedLogReader.open(emptyFile)
    try {
      assertEquals(storageReader.sizeBytes, 0L)
      val zeroLengthSlice = storageReader.readSlice(0, 0)
      assertEquals(zeroLengthSlice, MemorySegment.NULL)
    } finally {
      storageReader.close()
      Files.deleteIfExists(emptyFile)
    }
  }
}
