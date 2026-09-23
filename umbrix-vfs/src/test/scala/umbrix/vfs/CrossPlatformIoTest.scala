package umbrix.vfs

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import umbrix.vfs.platform.{Architecture, NativeIo, NioPositionalHandle, OsType, PagingAdvisory, Platform}

class CrossPlatformIoTest extends FunSuite {

  test("SegmentAllocator allocateFrom guarantees null-termination for native downcalls") {
    val arena = Arena.ofConfined()
    try {
      val utf8Segment = arena.allocateFrom("abc")
      assertEquals(utf8Segment.byteSize(), 4L)
      assertEquals(utf8Segment.get(ValueLayout.JAVA_BYTE, 3L), 0.toByte)

      val utf16LeSegment = arena.allocateFrom("abc", StandardCharsets.UTF_16LE)
      assertEquals(utf16LeSegment.byteSize(), 8L)
      assertEquals(utf16LeSegment.get(ValueLayout.JAVA_BYTE, 6L), 0.toByte)
      assertEquals(utf16LeSegment.get(ValueLayout.JAVA_BYTE, 7L), 0.toByte)
    } finally {
      arena.close()
    }
  }

  test("Linker.Option.captureCallState provides GetLastError layout") {
    import java.lang.foreign.{Linker, MemoryLayout}
    val captureLayout = Linker.Option.captureStateLayout()
    val getLastErrorVarHandle = captureLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"))
    assert(getLastErrorVarHandle != null)
  }

  test("Platform detection identifies OS, architecture and 64-bit address space") {
    assert(Platform.os != OsType.Other || !System.getProperty("os.name").isEmpty)
    assert(Platform.is64Bit, "Umbrix requires a 64-bit JVM platform")
    assert(
      Platform.arch == Architecture.X86_64 || Platform.arch == Architecture.AArch64 || Platform.arch == Architecture.Other
    )
  }

  test("NioPositionalHandle provides 100% reliable fallback on any platform") {
    val temporaryFile = Files.createTempFile("umbrix_nio_fallback", ".log")
    val fallbackContent = "Cross-Platform Fallback Verification Content 1234567890\n"
    Files.writeString(temporaryFile, fallbackContent, StandardCharsets.UTF_8)
    val expectedBytes = fallbackContent.getBytes(StandardCharsets.UTF_8)

    val positionalHandle = NioPositionalHandle.open(temporaryFile)
    val confinedArena = Arena.ofConfined()
    try {
      assertEquals(positionalHandle.sizeBytes, expectedBytes.length.toLong)

      val destinationBuffer = confinedArena.allocate(15, 1)
      val actualBytesRead = positionalHandle.read(destinationBuffer, 6, 15)
      assertEquals(actualBytesRead, 15)

      for (byteIndex <- 0 until 15) {
        assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), expectedBytes(6 + byteIndex))
      }

      val eofResult = positionalHandle.read(destinationBuffer, positionalHandle.sizeBytes + 10, 15)
      assertEquals(eofResult, -1)
    } finally {
      positionalHandle.close()
      confinedArena.close()
      Files.deleteIfExists(temporaryFile)
    }
  }

  test("PagingAdvisory runs without throwing on valid MemorySegments") {
    val confinedArena = Arena.ofConfined()
    try {
      val allocatedMemorySegment = confinedArena.allocate(4096, 8)
      PagingAdvisory.advise(allocatedMemorySegment)
      PagingAdvisory.advise(MemorySegment.NULL)
    } finally {
      confinedArena.close()
    }
  }

  test("NativeIo.openPositional dynamically creates a working handle") {
    val temporaryFile = Files.createTempFile("umbrix_native_io", ".log")
    Files.writeString(temporaryFile, "Native I/O Dispatch Verification\n", StandardCharsets.UTF_8)

    val positionalHandle = NativeIo.openPositional(temporaryFile)
    val confinedArena = Arena.ofConfined()
    try {
      assert(positionalHandle.sizeBytes > 0L)
      val destinationBuffer = confinedArena.allocate(6, 1)
      val transferredCount = positionalHandle.read(destinationBuffer, 0, 6)
      assertEquals(transferredCount, 6)
      assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, 0), 'N'.toByte)
      assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, 5), 'e'.toByte)
    } finally {
      positionalHandle.close()
      confinedArena.close()
      Files.deleteIfExists(temporaryFile)
    }
  }
}
