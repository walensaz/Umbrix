package umbrix.index

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.ArrayList
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
      val startVirtualOffset: Long = totalAppendBytes
      var bytesRemaining: Int = utf8Bytes.length
      var sourceOffset: Int = 0

      while (bytesRemaining > 0) {
        if (appendChunks.isEmpty || currentChunkRemaining == 0L) {
          val newChunk: MemorySegment = arena.allocate(chunkCapacity, 64)
          appendChunks.add(newChunk)
          currentChunkOffset = 0L
          currentChunkRemaining = chunkCapacity
        }

        val toWrite: Int = math.min(bytesRemaining.toLong, currentChunkRemaining).toInt
        val currentChunk: MemorySegment = appendChunks.get(appendChunks.size() - 1)
        MemorySegment.copy(
          MemorySegment.ofArray(utf8Bytes),
          sourceOffset.toLong,
          currentChunk,
          currentChunkOffset,
          toWrite.toLong
        )

        currentChunkOffset += toWrite.toLong
        currentChunkRemaining -= toWrite.toLong
        sourceOffset += toWrite
        bytesRemaining -= toWrite
        totalAppendBytes += toWrite.toLong
      }

      piecesList.add(Piece(BufferSource.Append, startVirtualOffset, bytesToAdd, lineCount))
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
  }
}
