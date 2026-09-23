package umbrix.simd

import munit.FunSuite
import java.lang.foreign.MemorySegment

class Utf8ChunkCarryoverTest extends FunSuite {

  test("expectedSequenceLength correctly classifies 1, 2, 3, and 4-byte headers") {
    // 1-byte ASCII
    assertEquals(Utf8ChunkCarryover.expectedSequenceLength('A'.toByte), 1)

    // 2-byte Greek alpha: 0xCE 0xB1
    val greekAlphaBytes: Array[Byte] = "α".getBytes("UTF-8")
    assertEquals(greekAlphaBytes.length, 2)
    assertEquals(Utf8ChunkCarryover.expectedSequenceLength(greekAlphaBytes(0)), 2)

    // 3-byte Japanese hiragana: 0xE3 0x81 0x82
    val japaneseBytes: Array[Byte] = "あ".getBytes("UTF-8")
    assertEquals(japaneseBytes.length, 3)
    assertEquals(Utf8ChunkCarryover.expectedSequenceLength(japaneseBytes(0)), 3)

    // 4-byte Rocket emoji: 0xF0 0x9F 0x9a 0x80
    val emojiBytes: Array[Byte] = "🚀".getBytes("UTF-8")
    assertEquals(emojiBytes.length, 4)
    assertEquals(Utf8ChunkCarryover.expectedSequenceLength(emojiBytes(0)), 4)
  }

  test("alignToCodepointBoundary skips continuation bytes inside multi-byte codepoint") {
    val text: String = "Hello 🚀 World"
    val testBytes: Array[Byte] = text.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val rocketStartOffset: Long = text.indexOf("🚀").toLong
    // Rocket emoji is 4 bytes: [rocketStartOffset, rocketStartOffset + 4)
    // Continuation bytes are at offset + 1, offset + 2, offset + 3

    // If slice candidate starts at rocketStartOffset, it's already aligned
    val alignedAtStart: Long = Utf8ChunkCarryover.alignToCodepointBoundary(segment, rocketStartOffset, testBytes.length.toLong)
    assertEquals(alignedAtStart, rocketStartOffset)

    // If candidate lands on continuation byte 1, 2, or 3, it should advance to rocketStartOffset + 4 (" World")
    val alignedFromContinuation1: Long = Utf8ChunkCarryover.alignToCodepointBoundary(segment, rocketStartOffset + 1L, testBytes.length.toLong)
    assertEquals(alignedFromContinuation1, rocketStartOffset + 4L)

    val alignedFromContinuation2: Long = Utf8ChunkCarryover.alignToCodepointBoundary(segment, rocketStartOffset + 2L, testBytes.length.toLong)
    assertEquals(alignedFromContinuation2, rocketStartOffset + 4L)

    val alignedFromContinuation3: Long = Utf8ChunkCarryover.alignToCodepointBoundary(segment, rocketStartOffset + 3L, testBytes.length.toLong)
    assertEquals(alignedFromContinuation3, rocketStartOffset + 4L)
  }

  test("alignToLineBoundary advances to next line start") {
    val text: String = "Line One\nLine Two\nLine Three"
    val testBytes: Array[Byte] = text.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(testBytes)

    val alignedOffset: Long = Utf8ChunkCarryover.alignToLineBoundary(segment, 2L, testBytes.length.toLong)
    assertEquals(alignedOffset, 9L) // Index of 'L' in "Line Two"
  }

  test("calculateTrailingIncompleteBytes detects 1, 2, and 3 trailing sliced bytes") {
    val fullEmojiText: String = "Prefix 🚀"
    val fullEmojiBytes: Array[Byte] = fullEmojiText.getBytes("UTF-8")
    val segment: MemorySegment = MemorySegment.ofArray(fullEmojiBytes)

    // Full emoji present: 0 incomplete bytes
    val completeCount: Int = Utf8ChunkCarryover.calculateTrailingIncompleteBytes(segment, 0L, fullEmojiBytes.length.toLong)
    assertEquals(completeCount, 0)

    // Sliced with only 3 of 4 bytes: 3 trailing incomplete bytes
    val slice3Of4: Int = Utf8ChunkCarryover.calculateTrailingIncompleteBytes(segment, 0L, fullEmojiBytes.length.toLong - 1L)
    assertEquals(slice3Of4, 3)

    // Sliced with only 2 of 4 bytes: 2 trailing incomplete bytes
    val slice2Of4: Int = Utf8ChunkCarryover.calculateTrailingIncompleteBytes(segment, 0L, fullEmojiBytes.length.toLong - 2L)
    assertEquals(slice2Of4, 2)

    // Sliced with only 1 of 4 bytes (the leading byte): 1 trailing incomplete byte
    val slice1Of4: Int = Utf8ChunkCarryover.calculateTrailingIncompleteBytes(segment, 0L, fullEmojiBytes.length.toLong - 3L)
    assertEquals(slice1Of4, 1)
  }
}
