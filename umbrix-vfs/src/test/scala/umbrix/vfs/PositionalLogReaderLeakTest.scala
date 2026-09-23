package umbrix.vfs

import munit.FunSuite
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.util.concurrent.{Executors, TimeUnit}

class PositionalLogReaderLeakTest extends FunSuite {

  test("PositionalLogReader handles high-volume repeated readSlice without unbounded native growth") {
    val tempFile: File = File.createTempFile("umbrix-pos-leak-test-", ".log")
    tempFile.deleteOnExit()

    val lineTemplate: String = "2026-09-22 10:00:00 [worker-thread] INFO High volume message payload for memory testing\n"
    val fileLinesCount: Int = 500
    val contentBuilder: StringBuilder = new StringBuilder()
    var lineIdx: Int = 0
    while (lineIdx < fileLinesCount) {
      contentBuilder.append(f"Line $lineIdx%04d: $lineTemplate")
      lineIdx += 1
    }
    val contentBytes: Array[Byte] = contentBuilder.toString.getBytes(StandardCharsets.UTF_8)
    Files.write(tempFile.toPath, contentBytes)

    // Open with a small 2MB ring buffer to test multiple wrap-arounds thoroughly
    val smallRingCapacity: Long = 2L * 1024L * 1024L
    val reader: PositionalLogReader = PositionalLogReader.open(tempFile.toPath, smallRingCapacity)

    try {
      assertEquals(reader.sizeBytes, contentBytes.length.toLong)

      // Perform 50,000 readSlice calls of 256 bytes each (~12.8 MB of read traffic through 2MB ring)
      val iterationsCount: Int = 50000
      val sliceLength: Long = 256L
      var iteration: Int = 0

      while (iteration < iterationsCount) {
        val readOffset: Long = (iteration.toLong * 64L) % (contentBytes.length.toLong - sliceLength)
        val slice: MemorySegment = reader.readSlice(readOffset, sliceLength)

        assert(slice != MemorySegment.NULL)
        assertEquals(slice.byteSize(), sliceLength)

        // Verify content correctness
        var byteIdx: Int = 0
        while (byteIdx < 16) { // spot check leading bytes
          val expectedByte: Byte = contentBytes((readOffset + byteIdx.toLong).toInt)
          val actualByte: Byte = slice.get(ValueLayout.JAVA_BYTE, byteIdx.toLong)
          assertEquals(actualByte, expectedByte)
          byteIdx += 1
        }

        iteration += 1
      }
    } finally {
      reader.close()
    }
  }

  test("PositionalLogReader supports caller-scoped Arena for readSlice") {
    val tempFile: File = File.createTempFile("umbrix-pos-arena-test-", ".log")
    tempFile.deleteOnExit()

    val testContent: String = "Deterministic off-heap memory testing with explicit Arena lifecycle"
    val testBytes: Array[Byte] = testContent.getBytes(StandardCharsets.UTF_8)
    Files.write(tempFile.toPath, testBytes)

    val reader: PositionalLogReader = PositionalLogReader.open(tempFile.toPath)
    try {
      val confinedArena: Arena = Arena.ofConfined()
      try {
        val slice: MemorySegment = reader.readSlice(0L, testBytes.length.toLong, confinedArena)
        assertEquals(slice.byteSize(), testBytes.length.toLong)
        var i: Int = 0
        while (i < testBytes.length) {
          assertEquals(slice.get(ValueLayout.JAVA_BYTE, i.toLong), testBytes(i))
          i += 1
        }
      } finally {
        confinedArena.close()
      }
    } finally {
      reader.close()
    }
  }

  test("PositionalLogReader concurrent multi-threaded readSlice ring buffer safety") {
    val tempFile: File = File.createTempFile("umbrix-pos-concurrent-ring-", ".log")
    tempFile.deleteOnExit()

    val fileContent: String = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\n" * 100
    val contentBytes: Array[Byte] = fileContent.getBytes(StandardCharsets.UTF_8)
    Files.write(tempFile.toPath, contentBytes)

    val reader: PositionalLogReader = PositionalLogReader.open(tempFile.toPath, 1024L * 1024L) // 1MB ring
    val threadCount: Int = 8
    val readsPerThread: Int = 2000
    val executor = Executors.newFixedThreadPool(threadCount)

    try {
      val tasks = for (t <- 0 until threadCount) yield {
        executor.submit(new Runnable {
          def run(): Unit = {
            var i: Int = 0
            while (i < readsPerThread) {
              val offset: Long = (t.toLong * 100L + i.toLong * 7L) % (contentBytes.length.toLong - 32L)
              val slice: MemorySegment = reader.readSlice(offset, 32L)
              assert(slice != MemorySegment.NULL)
              assertEquals(slice.byteSize(), 32L)
              assertEquals(slice.get(ValueLayout.JAVA_BYTE, 0L), contentBytes(offset.toInt))
              i += 1
            }
          }
        })
      }

      tasks.foreach(_.get(15, TimeUnit.SECONDS))
    } finally {
      executor.shutdown()
      reader.close()
    }
  }
}
