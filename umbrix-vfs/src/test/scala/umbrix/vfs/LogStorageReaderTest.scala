package umbrix.vfs

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets

class LogStorageReaderTest extends FunSuite {

  test("LogStorageReader contract can be implemented with off-heap MemorySegment") {
    val confinedArena = Arena.ofConfined()
    try {
      val testDataBytes = Array[Byte](0x41, 0x42, 0x43, 0x0A, 0x44, 0x45)
      val memorySegment = confinedArena.allocate(testDataBytes.length.toLong, 1)
      MemorySegment.copy(MemorySegment.ofArray(testDataBytes), 0, memorySegment, 0, testDataBytes.length.toLong)

      val mockStorageReader = new LogStorageReader {
        def path: Path = Path.of("test.log")
        def sizeBytes: Long = testDataBytes.length.toLong
        def readSlice(offset: Long, length: Long): MemorySegment = {
          memorySegment.asSlice(offset, length)
        }
        def close(): Unit = ()
      }

      assertEquals(mockStorageReader.sizeBytes, 6L)
      val extractedSlice = mockStorageReader.readSlice(1, 3)
      assertEquals(extractedSlice.get(ValueLayout.JAVA_BYTE, 0), 0x42.toByte)
      assertEquals(extractedSlice.get(ValueLayout.JAVA_BYTE, 2), 0x0A.toByte)

      val destinationBuffer = confinedArena.allocate(3, 1)
      val copiedByteCount = mockStorageReader.readInto(1, 3, destinationBuffer)
      assertEquals(copiedByteCount, 3L)
      assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, 0), 0x42.toByte)
      assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, 2), 0x0A.toByte)

      mockStorageReader.prefetch(0, 6)
    } finally {
      confinedArena.close()
    }
  }

  test("LogStorageReader.open dispatches correctly between static and live modes") {
    val temporaryFile = Files.createTempFile("umbrix_dispatch_test", ".log")
    try {
      Files.writeString(temporaryFile, "Dispatcher Test Content\n", StandardCharsets.UTF_8)

      val staticStorageReader = LogStorageReader.open(temporaryFile, isLive = false)
      try {
        assert(staticStorageReader.isInstanceOf[MemoryMappedLogReader])
        assertEquals(staticStorageReader.sizeBytes, Files.size(temporaryFile))
      } finally {
        staticStorageReader.close()
      }

      val liveStorageReader = LogStorageReader.open(temporaryFile, isLive = true)
      try {
        assert(liveStorageReader.isInstanceOf[PositionalLogReader])
        assertEquals(liveStorageReader.sizeBytes, Files.size(temporaryFile))
      } finally {
        liveStorageReader.close()
      }
    } finally {
      Files.deleteIfExists(temporaryFile)
    }
  }
}
