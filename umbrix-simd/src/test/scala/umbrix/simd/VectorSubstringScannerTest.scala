package umbrix.simd

import munit.FunSuite
import java.lang.foreign.MemorySegment
import java.util.ArrayList
import scala.util.Random

class VectorSubstringScannerTest extends FunSuite {

  test("scanLiteral correctly handles empty needles and empty segments") {
    val segment: MemorySegment = MemorySegment.ofArray("Some log line\n".getBytes("UTF-8"))
    val emptyNeedle: Array[Byte] = new Array[Byte](0)

    val countOnEmptyNeedle: Long = VectorSubstringScanner.countLiteral(segment, 0L, segment.byteSize(), emptyNeedle)
    assertEquals(countOnEmptyNeedle, 0L)

    val emptySegment: MemorySegment = MemorySegment.ofArray(new Array[Byte](0))
    val countOnEmptySegment: Long = VectorSubstringScanner.countLiteral(emptySegment, 0L, 0L, "test".getBytes("UTF-8"))
    assertEquals(countOnEmptySegment, 0L)
  }

  test("scanLiteral matches single-byte needles correctly") {
    val text: String = "alpha beta gamma delta"
    val testBytes: Array[Byte] = text.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val matchCount: Long = VectorSubstringScanner.scanLiteral(
      segment,
      0L,
      testBytes.length.toLong,
      Array('a'.toByte),
      (offset: Long) => recordedOffsets.add(offset)
    )

    assertEquals(matchCount, 6L) // 'a' appears 6 times in "alpha beta gamma delta"
    assertEquals(recordedOffsets.size(), 6)
  }

  test("scanLiteral matches two-byte needles correctly") {
    val text: String = "AB CD AB EF AB"
    val testBytes: Array[Byte] = text.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val matchCount: Long = VectorSubstringScanner.scanLiteral(
      segment,
      0L,
      testBytes.length.toLong,
      "AB".getBytes("UTF-8"),
      (offset: Long) => recordedOffsets.add(offset)
    )

    assertEquals(matchCount, 3L)
    assertEquals(recordedOffsets.get(0).longValue(), 0L)
    assertEquals(recordedOffsets.get(1).longValue(), 6L)
    assertEquals(recordedOffsets.get(2).longValue(), 12L)
  }

  test("scanLiteral accurately pinpoints multi-byte terms across vector boundaries") {
    val logContent: String =
      "2026-03-22 10:00:00 [INFO] System started\n" +
      "2026-03-22 10:00:01 [ERROR] Connection timed out to db.internal:5432\n" +
      "2026-03-22 10:00:02 [DEBUG] Retrying connection attempt 1\n" +
      "2026-03-22 10:00:03 [ERROR] Handshake failure on socket\n"

    val testBytes: Array[Byte] = logContent.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val needle: Array[Byte] = "[ERROR]".getBytes("UTF-8")

    val matchCount: Long = VectorSubstringScanner.scanLiteral(
      segment,
      0L,
      testBytes.length.toLong,
      needle,
      (offset: Long) => recordedOffsets.add(offset)
    )

    assertEquals(matchCount, 2L)
    assertEquals(recordedOffsets.get(0).longValue(), logContent.indexOf("[ERROR]").toLong)
    assertEquals(recordedOffsets.get(1).longValue(), logContent.lastIndexOf("[ERROR]").toLong)
  }

  test("scanLiteral handles overlapping patterns properly") {
    val text: String = "abababa"
    val testBytes: Array[Byte] = text.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val needle: Array[Byte] = "aba".getBytes("UTF-8")

    val matchCount: Long = VectorSubstringScanner.scanLiteral(
      segment,
      0L,
      testBytes.length.toLong,
      needle,
      (offset: Long) => recordedOffsets.add(offset)
    )

    assertEquals(matchCount, 3L)
    assertEquals(recordedOffsets.get(0).longValue(), 0L)
    assertEquals(recordedOffsets.get(1).longValue(), 2L)
    assertEquals(recordedOffsets.get(2).longValue(), 4L)
  }

  test("parity verification: VectorSubstringScanner matches Java String indexOf across large buffer") {
    val randomGenerator: Random = new Random(999L)
    val textLength: Int = 1024 * 128 // 128KB
    val alphabet: String = "abcdefghijklmnopqrstuvwxyz \n"
    val stringBuilder: java.lang.StringBuilder = new java.lang.StringBuilder(textLength)

    for (_ <- 0 until textLength) {
      stringBuilder.append(alphabet.charAt(randomGenerator.nextInt(alphabet.length)))
    }

    // Inject needle at controlled locations
    val needleString: String = "needle"
    stringBuilder.insert(100, needleString)
    stringBuilder.insert(5000, needleString)
    stringBuilder.insert(65535, needleString)
    stringBuilder.insert(100000, needleString)

    val fullText: String = stringBuilder.toString
    val textBytes: Array[Byte] = fullText.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(textBytes)

    val vectorOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    VectorSubstringScanner.scanLiteral(
      segment,
      0L,
      textBytes.length.toLong,
      needleString.getBytes("UTF-8"),
      (offset: Long) => vectorOffsets.add(offset)
    )

    val expectedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    var searchFromIndex: Int = 0
    var foundIndex: Int = fullText.indexOf(needleString, searchFromIndex)
    while (foundIndex != -1) {
      expectedOffsets.add(foundIndex.toLong)
      searchFromIndex = foundIndex + 1
      foundIndex = fullText.indexOf(needleString, searchFromIndex)
    }

    assertEquals(vectorOffsets, expectedOffsets)
  }

  test("scanLiteralCaseInsensitive matches single-byte needles across case variations") {
    val text: String = "Alpha BETA gamma Delta"
    val testBytes: Array[Byte] = text.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val matchCount: Long = VectorSubstringScanner.scanLiteralCaseInsensitive(
      segment,
      0L,
      testBytes.length.toLong,
      Array('a'.toByte),
      (offset: Long) => recordedOffsets.add(offset)
    )

    // 'A' at 0, 'a' at 4, 'A' at 10, 'a' at 12, 'a' at 16, 'a' at 21 -> 6 occurrences
    assertEquals(matchCount, 6L)
    assertEquals(recordedOffsets.size(), 6)
  }

  test("scanLiteralCaseInsensitive matches multi-byte terms across mixed casing") {
    val logContent: String =
      "2026-03-22 10:00:00 [info] System started\n" +
      "2026-03-22 10:00:01 [ERROR] Connection timed out to db.internal:5432\n" +
      "2026-03-22 10:00:02 [Debug] Retrying connection attempt 1\n" +
      "2026-03-22 10:00:03 [error] Handshake failure on socket\n" +
      "2026-03-22 10:00:04 [ErRoR] Terminal fault detected\n"

    val testBytes: Array[Byte] = logContent.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val recordedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    val needle: Array[Byte] = "Error".getBytes("UTF-8")

    val matchCount: Long = VectorSubstringScanner.scanLiteralCaseInsensitive(
      segment,
      0L,
      testBytes.length.toLong,
      needle,
      (offset: Long) => recordedOffsets.add(offset)
    )

    assertEquals(matchCount, 3L)
    assertEquals(recordedOffsets.get(0).longValue(), logContent.indexOf("[ERROR]") + 1L)
    assertEquals(recordedOffsets.get(1).longValue(), logContent.indexOf("[error]") + 1L)
    assertEquals(recordedOffsets.get(2).longValue(), logContent.indexOf("[ErRoR]") + 1L)
  }

  test("parity verification: scanLiteralCaseInsensitive matches case-insensitive search across 128KB buffer") {
    val randomGenerator: Random = new Random(12345L)
    val textLength: Int = 1024 * 128 // 128KB
    val alphabet: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 \n"
    val stringBuilder: java.lang.StringBuilder = new java.lang.StringBuilder(textLength)

    for (_ <- 0 until textLength) {
      stringBuilder.append(alphabet.charAt(randomGenerator.nextInt(alphabet.length)))
    }

    val needleString: String = "dOuBlE"
    stringBuilder.insert(50, "Double")
    stringBuilder.insert(1000, "DOUBLE")
    stringBuilder.insert(25000, "double")
    stringBuilder.insert(70000, "dOuBlE")
    stringBuilder.insert(120000, "DOUBle")

    val fullText: String = stringBuilder.toString
    val lowerFullText: String = fullText.toLowerCase()
    val lowerNeedle: String = needleString.toLowerCase()
    val textBytes: Array[Byte] = fullText.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(textBytes)

    val vectorOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    VectorSubstringScanner.scanLiteralCaseInsensitive(
      segment,
      0L,
      textBytes.length.toLong,
      needleString.getBytes("UTF-8"),
      (offset: Long) => vectorOffsets.add(offset)
    )

    val expectedOffsets: ArrayList[java.lang.Long] = new ArrayList[java.lang.Long]()
    var searchFromIndex: Int = 0
    var foundIndex: Int = lowerFullText.indexOf(lowerNeedle, searchFromIndex)
    while (foundIndex != -1) {
      expectedOffsets.add(foundIndex.toLong)
      searchFromIndex = foundIndex + 1
      foundIndex = lowerFullText.indexOf(lowerNeedle, searchFromIndex)
    }

    assertEquals(vectorOffsets, expectedOffsets)
  }
}
