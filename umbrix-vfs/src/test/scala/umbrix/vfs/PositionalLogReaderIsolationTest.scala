package umbrix.vfs

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.file.Files
import munit.FunSuite

class PositionalLogReaderIsolationTest extends FunSuite {

  private val megabyte: Long = 1024L * 1024L
  private val fileSizeBytes: Int = (16L * megabyte).toInt

  private def patternByte(offset: Long): Byte = (offset % 251L).toByte

  private def withReader(testBody: PositionalLogReader => Unit): Unit = {
    val logFile = Files.createTempFile("umbrix-pos-isolation-", ".log")
    logFile.toFile.deleteOnExit()
    Files.write(logFile, Array.tabulate[Byte](fileSizeBytes)(index => patternByte(index.toLong)))
    val reader = PositionalLogReader.open(logFile)
    try testBody(reader) finally reader.close()
  }

  private def assertSliceMatchesFile(slice: MemorySegment, fileOffset: Long): Unit = {
    var index: Long = 0L
    while (index < slice.byteSize()) {
      assertEquals(slice.get(ValueLayout.JAVA_BYTE, index), patternByte(fileOffset + index), s"mismatch at $index")
      index += 1L
    }
  }

  test("repeated reads larger than the old ring limit do not accumulate native memory") {
    withReader { reader =>
      (0 until 20).foreach { _ =>
        val slice = reader.readSlice(0L, 16L * megabyte)
        assertEquals(slice.byteSize(), 16L * megabyte)
      }
      assert(reader.scratchBytesAllocated <= 16L * megabyte, s"scratch grew to ${reader.scratchBytesAllocated}")
    }
  }

  test("a slice held by one thread is never overwritten by reads on other threads") {
    withReader { reader =>
      val heldSlice: MemorySegment = reader.readSlice(0L, megabyte)
      val interferingThread = new Thread(() => {
        (0 until 200).foreach(_ => reader.readSlice(5L * megabyte, megabyte))
      })
      interferingThread.start()
      interferingThread.join()
      assertSliceMatchesFile(heldSlice, 0L)
    }
  }

  test("scratch buffers of finished threads are released") {
    withReader { reader =>
      val shortLivedThreads = (0 until 20).map { _ =>
        val thread = new Thread(() => { reader.readSlice(0L, megabyte); () })
        thread.start()
        thread
      }
      shortLivedThreads.foreach(_.join())
      reader.readSlice(0L, megabyte)
      assertEquals(reader.scratchBytesAllocated, megabyte)
    }
  }
}
