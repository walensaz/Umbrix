package umbrix.index

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.ArrayList
import it.unimi.dsi.fastutil.longs.{Long2IntOpenHashMap, Long2LongOpenHashMap}
import umbrix.vfs.LogStorageReader
import scala.jdk.CollectionConverters.*

/** Buffer source indicator for Piece Table segments. */
enum BufferSource {
  case Original
  case Append
}

/**
 * Descriptor of a contiguous slice within the Piece Table.
 *
 * @param source identifies whether slice resides in original file or append buffer
 * @param bufferOffset 64-bit offset within the source buffer
 * @param length byte length of this slice
 * @param lineCount number of lines spanned by this piece
 */
case class Piece(
  source: BufferSource,
  bufferOffset: Long,
  length: Long,
  lineCount: Long
)

/**
 * Off-Heap Non-Destructive Piece Table.
 *
 * Enables instant, zero-copy mutations (annotations, trimming, in-place bookmarks) without
 * rewriting multi-gigabyte files on disk. Tracks original file slices alongside a growing
 * direct native chunked append buffer.
 *
 * Memory Safety Invariant:
 * Employs a segmented list of fixed-size off-heap chunks (1MB default) allocated within
 * a deterministic Arena. When append capacity is exceeded, a new fixed chunk is allocated
 * and appended to the list without reallocating, copying, or abandoning existing segments,
 * eliminating off-heap native memory leaks.
 */
class PieceTable(
  val originalReader: LogStorageReader,
  initialLineCount: Long,
  val chunkCapacity: Long = 1024L * 1024L // 1MB per fixed chunk
) extends AutoCloseable {

  private val arena: Arena = Arena.ofShared()
  private val appendChunks: ArrayList[MemorySegment] = new ArrayList[MemorySegment]()
  private var currentChunkOffset: Long = 0L
  private var currentChunkRemaining: Long = 0L
  private var totalAppendBytes: Long = 0L

  private val piecesList: ArrayList[Piece] = new ArrayList[Piece]()
  if (originalReader.sizeBytes > 0L) {
    piecesList.add(Piece(BufferSource.Original, 0L, originalReader.sizeBytes, initialLineCount))
  }

  private val virtualExpansionsOffset: Long2LongOpenHashMap = new Long2LongOpenHashMap()
  private val virtualExpansionsLength: Long2IntOpenHashMap = new Long2IntOpenHashMap()
  private val virtualExpansionsLineCount: Long2IntOpenHashMap = new Long2IntOpenHashMap()

  /** Total virtual length of the document across all pieces. */
  def totalLength: Long = {
    piecesList.asScala.foldLeft(0L)(_ + _.length)
  }

  /** Total virtual line count across all pieces. */
  def totalLines: Long = {
    piecesList.asScala.foldLeft(0L)(_ + _.lineCount)
  }

  /** Current count of active piece segments. */
  def pieceCount: Int = piecesList.size()

  /** Total allocated append chunk count. */
  def allocatedChunkCount: Int = appendChunks.size()

  /** Retrieves the Piece descriptor at index `pieceIndex`. */
  def getPiece(pieceIndex: Int): Piece = {
    piecesList.get(pieceIndex)
  }

  /** Returns all active pieces as an immutable list view. */
  def allPieces: List[Piece] = {
    piecesList.asScala.toList
  }

  /**
   * Internal helper to copy arbitrary off-heap bytes into the chunked append buffer.
   *
   * @param source source memory segment
   * @param length number of bytes to copy
   * @return starting virtual offset in the append space
   */
  private def writeToAppendBuffer(source: MemorySegment, length: Long): Long = {
    if (length <= 0L) {
      0L
    } else {
      val startVirtualOffset: Long = totalAppendBytes
      var bytesRemaining: Long = length
      var sourceOffset: Long = 0L

      while (bytesRemaining > 0L) {
        if (appendChunks.isEmpty || currentChunkRemaining == 0L) {
          val newChunk: MemorySegment = arena.allocate(chunkCapacity, 64)
          appendChunks.add(newChunk)
          currentChunkOffset = 0L
          currentChunkRemaining = chunkCapacity
        }

        val toWrite: Long = math.min(bytesRemaining, currentChunkRemaining)
        val currentChunk: MemorySegment = appendChunks.get(appendChunks.size() - 1)
        MemorySegment.copy(
          source,
          sourceOffset,
          currentChunk,
          currentChunkOffset,
          toWrite
        )

        currentChunkOffset += toWrite
        currentChunkRemaining -= toWrite
        sourceOffset += toWrite
        bytesRemaining -= toWrite
        totalAppendBytes += toWrite
      }
      startVirtualOffset
    }
  }

  /**
   * Appends text to the document non-destructively.
   *
   * Writes bytes into the current chunk or allocates additional chunks as needed.
   * Never discards or reallocates existing memory segments.
   *
   * @param utf8Bytes raw bytes to append
   * @param lineCount number of newlines contained within utf8Bytes
   */
  def appendText(utf8Bytes: Array[Byte], lineCount: Long): Unit = {
    val bytesToAdd: Long = utf8Bytes.length.toLong
    if (bytesToAdd > 0L) {
      val startVirtualOffset: Long = writeToAppendBuffer(MemorySegment.ofArray(utf8Bytes), bytesToAdd)
      piecesList.add(Piece(BufferSource.Append, startVirtualOffset, bytesToAdd, lineCount))
    }
  }

  /**
   * Registers a non-destructive off-heap virtual expansion associated with a physical line.
   */
  def putVirtualExpansion(physicalLine: Long, utf8Payload: MemorySegment, lineCount: Int): Unit = {
    val length: Long = utf8Payload.byteSize()
    if (length > 0L) {
      val startVirtualOffset: Long = writeToAppendBuffer(utf8Payload, length)
      virtualExpansionsOffset.put(physicalLine, startVirtualOffset)
      virtualExpansionsLength.put(physicalLine, length.toInt)
      virtualExpansionsLineCount.put(physicalLine, lineCount)
    }
  }

  /**
   * Convenience overload: registers multi-line text as a virtual expansion.
   */
  def putVirtualExpansionText(physicalLine: Long, multiLineText: String): Unit = {
    val utf8Bytes = multiLineText.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    var lineCount = 1
    var byteIndex = 0
    while (byteIndex < utf8Bytes.length) {
      if (utf8Bytes(byteIndex) == 0x0A.toByte) {
        lineCount += 1
      }
      byteIndex += 1
    }
    putVirtualExpansion(physicalLine, MemorySegment.ofArray(utf8Bytes), lineCount)
  }

  /**
   * Clears any active virtual expansion for the specified line.
   */
  def clearVirtualExpansion(physicalLine: Long): Unit = {
    virtualExpansionsOffset.remove(physicalLine)
    virtualExpansionsLength.remove(physicalLine)
    virtualExpansionsLineCount.remove(physicalLine)
  }

  /**
   * Checks whether the specified line has a virtual expansion registered.
   */
  def hasVirtualExpansion(physicalLine: Long): Boolean = {
    virtualExpansionsOffset.containsKey(physicalLine)
  }

  /**
   * Decodes lines from the virtual expansion for display in the UI viewport.
   */
  def getVirtualExpansionLines(
    physicalLine: Long,
    maxLines: Int = 50,
    maxCharsPerLine: Int = 2048
  ): Array[String] = {
    if (!hasVirtualExpansion(physicalLine)) {
      Array.empty[String]
    } else {
      val virtualOffset: Long = virtualExpansionsOffset.get(physicalLine)
      val length: Int = virtualExpansionsLength.get(physicalLine)
      if (length <= 0) {
        Array.empty[String]
      } else {
        val destArray: Array[Byte] = new Array[Byte](length)
        val destSegment: MemorySegment = MemorySegment.ofArray(destArray)
        readAppendBytes(virtualOffset, length.toLong, destSegment)
        val text: String = new String(destArray, java.nio.charset.StandardCharsets.UTF_8).stripLineEnd
        val rawLines: Array[String] = if (text.isEmpty) Array.empty[String] else splitLines(text)
        val resultCount: Int = math.min(rawLines.length, maxLines)
        val result: Array[String] = new Array[String](resultCount)
        var lineIndex: Int = 0
        while (lineIndex < resultCount) {
          val lineText = rawLines(lineIndex)
          result(lineIndex) = if (lineText.length > maxCharsPerLine) {
            lineText.substring(0, maxCharsPerLine) + s" [… +${lineText.length - maxCharsPerLine} chars clamped]"
          } else {
            lineText
          }
          lineIndex += 1
        }
        result
      }
    }
  }

  private def splitLines(text: String): Array[String] = {
    if (text.isEmpty) {
      Array.empty[String]
    } else {
      val lines = scala.collection.mutable.ArrayBuffer[String]()
      var lineStart = 0
      var charIndex = 0
      val textLength = text.length
      while (charIndex < textLength) {
        val currentChar = text.charAt(charIndex)
        if (currentChar == '\n') {
          val lineEnd = if (charIndex > lineStart && text.charAt(charIndex - 1) == '\r') charIndex - 1 else charIndex
          lines += text.substring(lineStart, lineEnd)
          lineStart = charIndex + 1
        }
        charIndex += 1
      }
      if (lineStart <= textLength) {
        lines += text.substring(lineStart, textLength)
      }
      lines.toArray
    }
  }

  /**
   * Reads raw bytes from a Piece into `destination`.
   *
   * @param piece piece descriptor
   * @param destination destination off-heap memory segment
   * @return total bytes transferred
   */
  def readPiece(piece: Piece, destination: MemorySegment): Long = {
    piece.source match {
      case BufferSource.Original =>
        originalReader.readInto(piece.bufferOffset, piece.length, destination)
      case BufferSource.Append =>
        readAppendBytes(piece.bufferOffset, piece.length, destination)
    }
  }

  /**
   * Reads raw bytes from the chunked append buffer into `destination`.
   *
   * @param virtualOffset start offset in append space
   * @param length total byte length to read
   * @param destination destination off-heap memory segment
   * @return total bytes transferred
   */
  def readAppendBytes(virtualOffset: Long, length: Long, destination: MemorySegment): Long = {
    require(destination.byteSize() >= length, s"Destination size (${destination.byteSize()}) smaller than length ($length)")
    var bytesCopied: Long = 0L
    var currentVirtualOffset: Long = virtualOffset
    var remainingBytes: Long = length

    while (remainingBytes > 0L) {
      val chunkIndex: Int = (currentVirtualOffset / chunkCapacity).toInt
      val offsetWithinChunk: Long = currentVirtualOffset % chunkCapacity

      if (chunkIndex < appendChunks.size()) {
        val chunkSegment: MemorySegment = appendChunks.get(chunkIndex)
        val availableInChunk: Long = chunkCapacity - offsetWithinChunk
        val toCopy: Long = math.min(remainingBytes, availableInChunk)

        MemorySegment.copy(chunkSegment, offsetWithinChunk, destination, bytesCopied, toCopy)
        bytesCopied += toCopy
        currentVirtualOffset += toCopy
        remainingBytes -= toCopy
      } else {
        remainingBytes = 0L
      }
    }

    bytesCopied
  }

  def close(): Unit = {
    arena.close()
    appendChunks.clear()
    piecesList.clear()
    virtualExpansionsOffset.clear()
    virtualExpansionsLength.clear()
    virtualExpansionsLineCount.clear()
  }
}
