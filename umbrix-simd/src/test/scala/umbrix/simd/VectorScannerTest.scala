package umbrix.simd

import munit.FunSuite
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import scala.util.Random

class VectorScannerTest extends FunSuite {

  test("preferredSpecies detects hardware SIMD width >= 16 bytes") {
    val registerByteWidth: Int = VectorScanner.vectorByteWidth
    assert(registerByteWidth >= 16, s"Expected SIMD vector register width >= 16 bytes, but was $registerByteWidth")
  }

  test("countByte and countNewlines return 0 on empty segment") {
    val emptySegment: MemorySegment = MemorySegment.ofArray(new Array[Byte](0))
    val matchCount: Long = VectorScanner.countNewlines(emptySegment, 0L, 0L)
    assertEquals(matchCount, 0L)
    assertEquals(VectorScanner.findFirstNewline(emptySegment, 0L, 0L), -1L)
  }

  test("countByte accurately handles segments smaller than vector byte width") {
    val testBytes: Array[Byte] = "Hello\nWorld\n!".getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val vectorCount: Long = VectorScanner.countNewlines(segment, 0L, testBytes.length.toLong)
    val scalarCount: Long = ScalarScanner.countNewlines(segment, 0L, testBytes.length.toLong)

    assertEquals(vectorCount, 2L)
    assertEquals(scalarCount, 2L)
  }

  test("scanNewlines records accurate absolute offsets with slicing") {
    val testText: String = "Line 1\nLine 2\nLine 3\nLine 4\n"
    val testBytes: Array[Byte] = testText.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val totalFound: Long = VectorScanner.scanNewlines(
      segment,
      0L,
      testBytes.length.toLong,
      (offset: Long) => recordedOffsets.add(offset)
    )

    assertEquals(totalFound, 4L)
    assertEquals(recordedOffsets.size(), 4)
    assertEquals(recordedOffsets.get(0).longValue(), 6L)   // After "Line 1"
    assertEquals(recordedOffsets.get(1).longValue(), 13L)  // After "Line 2"
    assertEquals(recordedOffsets.get(2).longValue(), 20L)  // After "Line 3"
    assertEquals(recordedOffsets.get(3).longValue(), 27L)  // After "Line 4"
  }

  test("scanByte matches ScalarScanner across arbitrary offsets and slice ranges") {
    val testText: String = "PREFIX__Line 1\nLine 2\nLine 3\n__SUFFIX"
    val testBytes: Array[Byte] = testText.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val sliceStartOffset: Long = 8L
    val sliceLength: Long = 21L // Covers "Line 1\nLine 2\nLine 3\n"

    val vectorOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val scalarOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()

    val vectorCount: Long = VectorScanner.scanNewlines(
      segment,
      sliceStartOffset,
      sliceLength,
      (offset: Long) => vectorOffsets.add(offset)
    )
    val scalarCount: Long = ScalarScanner.scanNewlines(
      segment,
      sliceStartOffset,
      sliceLength,
      (offset: Long) => scalarOffsets.add(offset)
    )

    assertEquals(vectorCount, 3L)
    assertEquals(scalarCount, 3L)
    assertEquals(vectorOffsets, scalarOffsets)
  }

  test("dense newline and zero newline boundary conditions") {
    val allNewlines: Array[Byte] = Array.fill[Byte](256)(0x0A.toByte)
    val noNewlines: Array[Byte] = Array.fill[Byte](256)('A'.toByte)

    val allSegment: MemorySegment = MemorySegment.ofArray(allNewlines)
    val noneSegment: MemorySegment = MemorySegment.ofArray(noNewlines)

    assertEquals(VectorScanner.countNewlines(allSegment, 0L, 256L), 256L)
    assertEquals(VectorScanner.countNewlines(noneSegment, 0L, 256L), 0L)
    assertEquals(VectorScanner.findFirstNewline(allSegment, 0L, 256L), 0L)
    assertEquals(VectorScanner.findFirstNewline(noneSegment, 0L, 256L), -1L)
  }

  test("parity verification: VectorScanner produces identical results to ScalarScanner on random data") {
    val randomGenerator: Random = new Random(42L)
    val randomDataSize: Int = 1024 * 64 + 37 // 64KB + 37 unaligned bytes
    val randomBytes: Array[Byte] = new Array[Byte](randomDataSize)
    randomGenerator.nextBytes(randomBytes)

    // Sprinkle newlines at ~2% frequency
    for (index <- 0 until randomDataSize) {
      if (randomGenerator.nextDouble() < 0.02) {
        randomBytes(index) = 0x0A.toByte
      }
    }

    val segment: MemorySegment = MemorySegment.ofArray(randomBytes)

    val vectorOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val scalarOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()

    val vectorCount: Long = VectorScanner.scanNewlines(
      segment,
      0L,
      randomDataSize.toLong,
      (offset: Long) => vectorOffsets.add(offset)
    )
    val scalarCount: Long = ScalarScanner.scanNewlines(
      segment,
      0L,
      randomDataSize.toLong,
      (offset: Long) => scalarOffsets.add(offset)
    )

    assertEquals(vectorCount, scalarCount)
    assertEquals(vectorOffsets.size(), scalarOffsets.size())
    assertEquals(vectorOffsets, scalarOffsets)
  }

  test("alignment prologue: accurately scans off-heap native memory with deliberate byte misalignments") {
    val arena = Arena.ofConfined()
    try {
      val bufferSize = 8192
      val nativeSegment = arena.allocate(bufferSize.toLong, 64)
      val random = new Random(12345L)

      for (i <- 0L until bufferSize.toLong) {
        val b = if (random.nextDouble() < 0.05) 0x0A.toByte else ('A' + random.nextInt(26)).toByte
        nativeSegment.set(ValueLayout.JAVA_BYTE, i, b)
      }

      // Test every misalignment offset from 0 to 63
      for (misalignmentOffset <- 0 until 64) {
        val testLength = (bufferSize - misalignmentOffset - 37).toLong
        val vectorOffsets = new ArrayList[java.lang.Long]()
        val scalarOffsets = new ArrayList[java.lang.Long]()

        val vectorCount = VectorScanner.scanNewlines(
          nativeSegment,
          misalignmentOffset.toLong,
          testLength,
          (offset: Long) => vectorOffsets.add(offset)
        )
        val scalarCount = ScalarScanner.scanNewlines(
          nativeSegment,
          misalignmentOffset.toLong,
          testLength,
          (offset: Long) => scalarOffsets.add(offset)
        )

        assertEquals(vectorCount, scalarCount, s"Mismatch for misalignment offset $misalignmentOffset")
        assertEquals(vectorOffsets, scalarOffsets, s"Offset list mismatch for offset $misalignmentOffset")

        val countedNewlines = VectorScanner.countNewlines(nativeSegment, misalignmentOffset.toLong, testLength)
        assertEquals(countedNewlines, scalarCount, s"countNewlines mismatch for offset $misalignmentOffset")

        val firstVector = VectorScanner.findFirstNewline(nativeSegment, misalignmentOffset.toLong, testLength)
        val firstScalar = ScalarScanner.findFirstNewline(nativeSegment, misalignmentOffset.toLong, testLength)
        assertEquals(firstVector, firstScalar, s"findFirstNewline mismatch for offset $misalignmentOffset")
      }
    } finally {
      arena.close()
    }
  }

  test("findFirstNewline early-exit is significantly faster than full-window scanNewlines on 64KB buffers") {
    val arena = Arena.ofConfined()
    try {
      val windowSize = 65536L
      val nativeSegment = arena.allocate(windowSize, 64)
      // Fill with realistic 100-byte log lines (~655 newlines in 64KB)
      for (i <- 0L until windowSize) {
        val b: Byte = if (i % 100L == 99L) 0x0A.toByte else 'x'.toByte
        nativeSegment.set(ValueLayout.JAVA_BYTE, i, b)
      }

      // Warmup
      var warmup = 0
      while (warmup < 2000) {
        VectorScanner.findFirstNewline(nativeSegment, 0L, windowSize)
        var first = -1L
        VectorScanner.scanNewlines(nativeSegment, 0L, windowSize, (off: Long) => if (first == -1L) first = off)
        warmup += 1
      }

      val iterations = 10000
      val t0 = System.nanoTime()
      var i = 0
      var sumFast = 0L
      while (i < iterations) {
        sumFast += VectorScanner.findFirstNewline(nativeSegment, 0L, windowSize)
        i += 1
      }
      val fastNanos = System.nanoTime() - t0

      val t1 = System.nanoTime()
      var j = 0
      var sumFull = 0L
      while (j < iterations) {
        var first = -1L
        VectorScanner.scanNewlines(nativeSegment, 0L, windowSize, (off: Long) => if (first == -1L) first = off)
        sumFull += first
        j += 1
      }
      val fullNanos = System.nanoTime() - t1

      assertEquals(sumFast, 99L * iterations)
      assertEquals(sumFull, 99L * iterations)
      assert(
        fastNanos * 5L < fullNanos,
        s"Expected findFirstNewline ($fastNanos ns) to be >5x faster than 64KB scanNewlines ($fullNanos ns)"
      )
    } finally {
      arena.close()
    }
  }

  test("VectorSubstringScanner bitwise case-insensitive folding preserves exact parity around ASCII boundary chars") {
    // Test characters whose bit-5 (0x20) aliases with '@', '[', '`', '{', etc.
    val trickyText = "A@a` Z[z{ ERROR error ErRoR [ERROR] @error `ERROR {error}"
    val bytes = trickyText.getBytes(StandardCharsets.UTF_8)
    val segment = MemorySegment.ofArray(bytes)

    val needles = Seq("error", "ERROR", "a@", "z[", "[error]", "@error", "a", "z", "[")
    for (needle <- needles) {
      val needleBytes = needle.getBytes(StandardCharsets.UTF_8)
      val foundOffsets = new ArrayList[java.lang.Long]()
      VectorSubstringScanner.scanLiteralCaseInsensitive(
        segment,
        0L,
        bytes.length.toLong,
        needleBytes,
        (off: Long) => foundOffsets.add(off)
      )

      // Compute expected offsets via reference String.regionMatches(ignoreCase = true)
      val expectedOffsets = new ArrayList[java.lang.Long]()
      for (pos <- 0 to (trickyText.length - needle.length)) {
        if (trickyText.regionMatches(true, pos, needle, 0, needle.length)) {
          expectedOffsets.add(pos.toLong)
        }
      }

      assertEquals(foundOffsets, expectedOffsets, s"Case-insensitive mismatch for needle '$needle'")
    }
  }
}
