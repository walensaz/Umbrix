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
        var foundNewlineOffset: Long = -1L

        VectorScanner.scanNewlines(
          windowSlice,
          0L,
          searchWindowSize,
          (relativeOffset: Long) => {
            if (foundNewlineOffset == -1L) {
              foundNewlineOffset = relativeOffset
            }
          }
        )

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
      var relativeMatch: Long = -1L

      VectorScanner.scanNewlines(
        windowSlice,
        0L,
        windowSize,
        (offset: Long) => {
          if (relativeMatch == -1L) {
            relativeMatch = offset
          }
        }
      )

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
        MemorySegment.ofArray(new Array[Byte](0))
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
      val cleanWithoutCarriageReturn: String = decodedString.replace("\r", "")
      val expandedTabString: String = cleanWithoutCarriageReturn.replace("\t", "    ")

      // Sanitize null bytes (\u0000) and unprintable control characters (< 32) to spaces.
      // In native C++ Skia and font shaping engines, \0 terminates string rendering and hides
      // text in binary files (e.g. JAR/ZIP class headers), while spaces preserve 1:1 character index parity.
      val sanitizedCharacters: Array[Char] = expandedTabString.toCharArray
      var characterIndex: Int = 0
      while (characterIndex < sanitizedCharacters.length) {
        val charValue: Char = sanitizedCharacters(characterIndex)
        if (charValue < 32 && charValue != '\n' && charValue != '\t') {
          sanitizedCharacters(characterIndex) = ' '
        }
        characterIndex += 1
      }
      val sanitizedLineString: String = new String(sanitizedCharacters)

      if (sanitizedLineString.length > maxCharacters) {
        sanitizedLineString.substring(0, maxCharacters) + s" [… +${rawByteSize - maxCharacters} bytes clamped]"
      } else {
        sanitizedLineString
      }
    }
  }

  def close(): Unit = {
    storageReader.close()
  }
}

object PhysicalLineIndexerImpl {

  /**
   * Creates an indexer by indexing the storage reader with SIMD kernels.
   */
  def build(storageReader: LogStorageReader): PhysicalLineIndexerImpl = {
    val index: TwoLevelSparseIndex = TwoLevelSparseIndex.build(storageReader)
    new PhysicalLineIndexerImpl(storageReader, index)
  }
}
