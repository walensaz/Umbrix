package umbrix.index

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.simd.VectorScanner
import umbrix.vfs.LogStorageReader

/**
 * Concrete high-performance implementation of `PhysicalLineIndexer`.
 *
 * Encapsulates the `LogStorageReader` handle and `TwoLevelSparseIndex`, providing
 * sub-microsecond line seeking, zero-copy line memory segment slicing, and clamped text decoding.
 */
class PhysicalLineIndexerImpl(
  val storageReader: LogStorageReader,
  @volatile var sparseIndex: TwoLevelSparseIndex
) extends PhysicalLineIndexer {

  /**
   * Atomically swaps the active TwoLevelSparseIndex with an expanded snapshot during progressive background indexing.
   */
  def updateSparseIndex(newIndex: TwoLevelSparseIndex): Unit = {
    this.sparseIndex = newIndex
  }

  override def indexedByteLimit: Long = sparseIndex.indexedBytes.min(storageReader.sizeBytes)

  def totalLines: LineIndex = {
    LineIndex(sparseIndex.totalLinesCount)
  }

  def byteOffsetForLine(lineIndex: LineIndex): ByteOffset = {
    sparseIndex.byteOffsetForLine(lineIndex, storageReader)
  }

  def lineIndexForByteOffset(byteOffset: ByteOffset): LineIndex = {
    sparseIndex.lineIndexForByteOffset(byteOffset, storageReader)
  }

  /**
   * Computes the byte length of line `lineIndex`, bounded by the next newline or EOF.
   */
  def lineLength(lineIndex: LineIndex): Long = {
    if (lineIndex < LineIndex.Zero || lineIndex >= totalLines) {
      0L
    } else {
      val lineStartOffset: ByteOffset = byteOffsetForLine(lineIndex)
      val remainingFileSize: Long = storageReader.sizeBytes - lineStartOffset.value
      if (remainingFileSize <= 0L) {
        0L
      } else {
        val searchWindowSize: Long = remainingFileSize.min(65536L)
        val windowSlice: MemorySegment = storageReader.readSlice(lineStartOffset.value, searchWindowSize)
        val foundNewlineOffset: Long = VectorScanner.findFirstNewline(windowSlice, 0L, searchWindowSize)

        if (foundNewlineOffset >= 0L) {
          foundNewlineOffset
        } else {
          // If line is longer than 64KB, scan forward until newline or EOF
          findNextNewlineDistance(lineStartOffset.value + searchWindowSize, searchWindowSize)
        }
      }
    }
  }

  @tailrec
  private def findNextNewlineDistance(currentOffset: Long, accumulatedLength: Long): Long = {
    val remainingFileSize: Long = storageReader.sizeBytes - currentOffset
    if (remainingFileSize <= 0L) {
      accumulatedLength
    } else {
      val windowSize: Long = remainingFileSize.min(65536L)
      val windowSlice: MemorySegment = storageReader.readSlice(currentOffset, windowSize)
      val relativeMatch: Long = VectorScanner.findFirstNewline(windowSlice, 0L, windowSize)

      if (relativeMatch >= 0L) {
        accumulatedLength + relativeMatch
      } else {
        findNextNewlineDistance(currentOffset + windowSize, accumulatedLength + windowSize)
      }
    }
  }

  /**
   * Returns a direct zero-copy off-heap memory segment slice for the given line.
   */
  def getLineSlice(lineIndex: LineIndex): MemorySegment = {
    if (lineIndex < LineIndex.Zero || lineIndex >= totalLines) {
      MemorySegment.NULL
    } else {
      val startOffset: ByteOffset = byteOffsetForLine(lineIndex)
      val length: Long = lineLength(lineIndex)
      if (length <= 0L) {
        PhysicalLineIndexerImpl.EmptySegment
      } else {
        storageReader.readSlice(startOffset.value, length)
      }
    }
  }

  /**
   * Decodes UTF-8 string for display in the UI viewport, strictly clamping length
   * to protect the graphics engine from gigantic (e.g. 20MB) single-line payloads.
   *
   * Automatically normalizes line endings by stripping carriage returns (`\r`)
   * and expands horizontal tabs (`\t`) to 4 spaces to prevent font glyph replacement issues (`?`)
   * in desktop rendering engines.
   */
  def getLineText(lineIndex: LineIndex, maxCharacters: Int = 4096): String = {
    val slice: MemorySegment = getLineSlice(lineIndex)
    if (slice == MemorySegment.NULL || slice.byteSize() == 0L) {
      ""
    } else {
      val rawByteSize: Long = slice.byteSize()
      val bytesToRead: Int = rawByteSize.min(maxCharacters.toLong * 4L).toInt // UTF-8 up to 4 bytes per char
      val byteBuffer: Array[Byte] = new Array[Byte](bytesToRead)
      MemorySegment.copy(slice, ValueLayout.JAVA_BYTE, 0L, byteBuffer, 0, bytesToRead)
      val decodedString: String = new String(byteBuffer, StandardCharsets.UTF_8)

      // Fast path check: if string is clean ASCII/UTF-8 without \r, \t, or control chars (< 32)
      // and within maxCharacters, return decodedString directly with zero extra allocations!
      val decodedLength: Int = decodedString.length
      var needsSanitization: Boolean = false
      var checkIndex: Int = 0
      while (checkIndex < decodedLength && !needsSanitization) {
        val currentChar: Char = decodedString.charAt(checkIndex)
        if (currentChar < 32 && currentChar != '\n') {
          needsSanitization = true
        }
        checkIndex += 1
      }

      if (!needsSanitization) {
        if (decodedLength > maxCharacters) {
          decodedString.substring(0, maxCharacters) + s" [… +${rawByteSize - maxCharacters} bytes clamped]"
        } else {
          decodedString
        }
      } else {
        // Single-pass sanitization: strip \r, expand \t -> 4 spaces, replace control chars (< 32) with ' '
        val sanitizedBuilder = new java.lang.StringBuilder(decodedLength.min(maxCharacters) + 16)
        var charIndex: Int = 0
        while (charIndex < decodedLength) {
          val currentChar: Char = decodedString.charAt(charIndex)
          if (currentChar == '\r') {
            // strip carriage return
          } else if (currentChar == '\t') {
            sanitizedBuilder.append("    ")
          } else if (currentChar < 32 && currentChar != '\n') {
            sanitizedBuilder.append(' ')
          } else {
            sanitizedBuilder.append(currentChar)
          }
          charIndex += 1
        }

        if (sanitizedBuilder.length() > maxCharacters) {
          sanitizedBuilder.setLength(maxCharacters)
          sanitizedBuilder.append(s" [… +${rawByteSize - maxCharacters} bytes clamped]")
        }
        sanitizedBuilder.toString
      }
    }
  }

  def close(): Unit = {
    storageReader.close()
  }
}

object PhysicalLineIndexerImpl {

  private val EmptySegment: MemorySegment = MemorySegment.ofArray(new Array[Byte](0))

  /**
   * Creates an indexer by indexing the storage reader with SIMD kernels.
   */
  def build(storageReader: LogStorageReader): PhysicalLineIndexerImpl = {
    val index: TwoLevelSparseIndex = TwoLevelSparseIndex.build(storageReader)
    new PhysicalLineIndexerImpl(storageReader, index)
  }
}
