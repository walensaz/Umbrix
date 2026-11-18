package umbrix.vfs

import munit.FunSuite
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.lang.foreign.ValueLayout
import umbrix.vfs.platform.PagingAdvisory

class MemoryEvictionTest extends FunSuite {

  test("PagingAdvisory evict and trimWorkingSet execute without errors") {
    val temporaryFile = Files.createTempFile("umbrix_eviction_advisory_test", ".log")
    try {
      val payload = "Proactive OS Working Set Eviction and Memory Reclamation Test Payload\n" * 1000
      Files.writeString(temporaryFile, payload, StandardCharsets.UTF_8)

      val reader = MemoryMappedLogReader.open(temporaryFile)
      try {
        val slice = reader.readSlice(0, 500)
        assertEquals(slice.byteSize(), 500L)

        // Evict specific slice
        PagingAdvisory.evict(slice)
        reader.evict(0, 500)

        // Trim process working set
        PagingAdvisory.trimWorkingSet()
        reader.trimWorkingSet()

        // Verify data remains 100% accessible and intact after eviction and working set trimming
        val verifySlice = reader.readSlice(0, 100)
        assertEquals(verifySlice.get(ValueLayout.JAVA_BYTE, 0L), 'P'.toByte)
      } finally {
        reader.close()
      }
    } finally {
      Files.deleteIfExists(temporaryFile)
    }
  }

  test("PositionalLogReader trimWorkingSet executes cleanly") {
    val temporaryFile = Files.createTempFile("umbrix_positional_evict_test", ".log")
    try {
      Files.writeString(temporaryFile, "Positional Log Line 1\nPositional Log Line 2\n", StandardCharsets.UTF_8)
      val positionalReader = PositionalLogReader.open(temporaryFile)
      try {
        positionalReader.evict(0, 20)
        positionalReader.trimWorkingSet()
        val slice = positionalReader.readSlice(0, 10)
        assert(!slice.equals(java.lang.foreign.MemorySegment.NULL))
      } finally {
        positionalReader.close()
      }
    } finally {
      Files.deleteIfExists(temporaryFile)
    }
  }
}
