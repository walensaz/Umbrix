package umbrix.vfs

import munit.FunSuite
import java.nio.file.{Files, Path, StandardOpenOption}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{Executors, TimeUnit}

class PositionalLogReaderTest extends FunSuite {

  test("PositionalLogReader accurately reads slices and respects bounds") {
    val temporaryFile = Files.createTempFile("umbrix_pos_test", ".log")
    val fileContent = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\n"
    Files.writeString(temporaryFile, fileContent, StandardCharsets.UTF_8)
    val expectedBytes = fileContent.getBytes(StandardCharsets.UTF_8)

    val storageReader = PositionalLogReader.open(temporaryFile)
    try {
      assertEquals(storageReader.sizeBytes, expectedBytes.length.toLong)
      assertEquals(storageReader.path, temporaryFile)

      val initialSlice = storageReader.readSlice(0, 10)
      assertEquals(initialSlice.byteSize(), 10L)
      for (byteIndex <- 0 until 10) {
        assertEquals(initialSlice.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), expectedBytes(byteIndex))
      }

      val middleSlice = storageReader.readSlice(10, 10)
      assertEquals(middleSlice.byteSize(), 10L)
      for (byteIndex <- 0 until 10) {
        assertEquals(middleSlice.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), expectedBytes(10 + byteIndex))
      }

      val confinedArena = Arena.ofConfined()
      try {
        val destinationBuffer = confinedArena.allocate(10, 1)
        val readCount = storageReader.readInto(10, 10, destinationBuffer)
        assertEquals(readCount, 10L)
        for (byteIndex <- 0 until 10) {
          assertEquals(destinationBuffer.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), expectedBytes(10 + byteIndex))
        }
      } finally {
        confinedArena.close()
      }

      intercept[IndexOutOfBoundsException] {
        storageReader.readSlice(-1, 5)
      }
      intercept[IllegalArgumentException] {
        storageReader.readSlice(0, -5)
      }

      val pastEofSlice = storageReader.readSlice(storageReader.sizeBytes + 100, 10)
      assertEquals(pastEofSlice, MemorySegment.NULL)
    } finally {
      storageReader.close()
      Files.deleteIfExists(temporaryFile)
    }

    intercept[IllegalStateException] {
      storageReader.readSlice(0, 5)
    }
  }

  test("PositionalLogReader handles concurrent multi-threaded reads safely") {
    val temporaryFile = Files.createTempFile("umbrix_concurrent_test", ".log")
    val stringBuilder = new java.lang.StringBuilder()
    for (lineIndex <- 0 until 1000) {
      stringBuilder.append(f"Line $lineIndex%05d: Sample log record payload with timestamp 2026-09-21T23:00:00.000Z\n")
    }
    val fullLogText = stringBuilder.toString
    Files.writeString(temporaryFile, fullLogText, StandardCharsets.UTF_8)

    val storageReader = PositionalLogReader.open(temporaryFile)
    val workerThreadCount = 8
    val iterationsPerThread = 500
    val threadPoolExecutor = Executors.newFixedThreadPool(workerThreadCount)

    try {
      val executionFutures = for (threadIdentifier <- 0 until workerThreadCount) yield {
        threadPoolExecutor.submit(new Runnable {
          def run(): Unit = {
            val threadArena = Arena.ofConfined()
            try {
              val threadBuffer = threadArena.allocate(64, 1)
              for (iterationIndex <- 0 until iterationsPerThread) {
                val targetLineIndex = (threadIdentifier * 100 + iterationIndex) % 990
                val targetSearchToken = f"Line $targetLineIndex%05d:"
                val targetFileOffset = fullLogText.indexOf(targetSearchToken).toLong
                assert(targetFileOffset >= 0L)

                val bytesTransferred = storageReader.readInto(targetFileOffset, targetSearchToken.length.toLong, threadBuffer)
                assertEquals(bytesTransferred, targetSearchToken.length.toLong)
                for (charOffset <- 0 until targetSearchToken.length) {
                  assertEquals(threadBuffer.get(ValueLayout.JAVA_BYTE, charOffset.toLong), targetSearchToken.charAt(charOffset).toByte)
                }
              }
            } finally {
              threadArena.close()
            }
          }
        })
      }

      for (futureResult <- executionFutures) {
        futureResult.get(10, TimeUnit.SECONDS)
      }
    } finally {
      threadPoolExecutor.shutdown()
      storageReader.close()
      Files.deleteIfExists(temporaryFile)
    }
  }

  test("PositionalLogReader permits concurrent external file appending and rotation") {
    val temporaryFile = Files.createTempFile("umbrix_rotation_test", ".log")
    Files.writeString(temporaryFile, "Initial log line 1\n", StandardCharsets.UTF_8)

    val storageReader = PositionalLogReader.open(temporaryFile)
    try {
      val firstSlice = storageReader.readSlice(0, 18)
      assertEquals(firstSlice.byteSize(), 18L)

      Files.writeString(temporaryFile, "Appended line 2\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND)

      assert(storageReader.sizeBytes > 18L)
      val secondSlice = storageReader.readSlice(19, 15)
      val appendedBytes = "Appended line 2".getBytes(StandardCharsets.UTF_8)
      for (byteIndex <- appendedBytes.indices) {
        assertEquals(secondSlice.get(ValueLayout.JAVA_BYTE, byteIndex.toLong), appendedBytes(byteIndex))
      }
    } finally {
      storageReader.close()
      Files.deleteIfExists(temporaryFile)
    }
  }
}
