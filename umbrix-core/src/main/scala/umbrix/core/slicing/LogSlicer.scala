package umbrix.core.slicing

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.roaringbitmap.longlong.Roaring64NavigableMap
import org.roaringbitmap.longlong.LongIterator
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.index.PhysicalLineIndexer
import umbrix.vfs.LogStorageReader

/**
 * Result metrics from a zero-copy log export / slicing operation.
 *
 * @param destinationPath path to the generated sub-file
 * @param bytesWritten total bytes written to the sub-file
 * @param linesExported total lines contained in the sub-file
 * @param durationNanos execution time in nanoseconds
 */
case class SliceResult(
  destinationPath: Path,
  bytesWritten: Long,
  linesExported: Long,
  durationNanos: Long
)

/**
 * Industrial-grade zero-copy log slicing and sub-file generation engine.
 *
 * Exports trimmed incident windows and filtered search subsets to standalone `.log` sub-files
 * using direct off-heap memory transfers without ingesting any log data into the JVM GC heap.
 */
class LogSlicer(
  val storageReader: LogStorageReader,
  val indexer: PhysicalLineIndexer
) {

  private final val streamingChunkSizeBytes: Long = 16L * 1024L * 1024L // 16MB DMA write chunk
  private val newlineByte: Byte = 0x0A.toByte

  /**
   * Exports a contiguous line range [startLine, endLine] to a standalone sub-file.
   *
   * Executes via direct off-heap streaming, moving gigabytes to disk in milliseconds.
   *
   * @param startLine 0-based starting line index (inclusive)
   * @param endLine 0-based ending line index (inclusive)
   * @param destinationPath destination filesystem path
   * @return SliceResult with write metrics
   */
  def exportContiguousRange(startLine: LineIndex, endLine: LineIndex, destinationPath: Path): SliceResult = {
    val startNanos: Long = System.nanoTime()
    val maxValidLine = if (indexer.totalLines > LineIndex.Zero) indexer.totalLines - 1L else LineIndex.Zero
    val boundedStartLine: LineIndex = startLine.max(LineIndex.Zero).min(maxValidLine)
    val boundedEndLine: LineIndex = endLine.max(boundedStartLine).min(maxValidLine)

    val startByteOffset: ByteOffset = indexer.byteOffsetForLine(boundedStartLine)
    val endByteOffset: ByteOffset = {
      if (boundedEndLine + 1L < indexer.totalLines) {
        indexer.byteOffsetForLine(boundedEndLine + 1L)
      } else {
        ByteOffset(storageReader.sizeBytes)
      }
    }

    val totalBytesToTransfer: Long = (endByteOffset - startByteOffset).max(0L)
    val totalLinesCount: Long = (boundedEndLine - boundedStartLine) + 1L

    val targetChannel: FileChannel = FileChannel.open(
      destinationPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    )

    var currentOffset: Long = startByteOffset.value
    val transferEndOffset: Long = startByteOffset.value + totalBytesToTransfer

    try {
      while (currentOffset < transferEndOffset) {
        val chunkSize: Long = streamingChunkSizeBytes.min(transferEndOffset - currentOffset)
        val sliceSegment: MemorySegment = storageReader.readSlice(currentOffset, chunkSize)
        val byteBuffer: ByteBuffer = sliceSegment.asByteBuffer()
        while (byteBuffer.hasRemaining) {
          targetChannel.write(byteBuffer)
        }
        currentOffset += chunkSize
      }
    } finally {
      targetChannel.close()
    }

    val durationNanos: Long = System.nanoTime() - startNanos
    SliceResult(destinationPath, totalBytesToTransfer, totalLinesCount, durationNanos)
  }

  /**
   * Exports a discontiguous set of filtered lines to a standalone sub-file.
   *
   * Iterates through the RoaringBitmap coordinates, streaming each line's off-heap slice
   * directly to the destination FileChannel with 0 heap String allocations.
   *
   * @param matchingLines compressed RoaringBitmap containing target physical line indices
   * @param destinationPath destination filesystem path
   * @return SliceResult with write metrics
   */
  def exportFilteredLines(matchingLines: Roaring64NavigableMap, destinationPath: Path): SliceResult = {
    val startNanos: Long = System.nanoTime()
    val targetChannel: FileChannel = FileChannel.open(
      destinationPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    )

    var bytesWrittenAccumulator: Long = 0L
    val linesCount: Long = matchingLines.getLongCardinality
    val newlineBuffer: ByteBuffer = ByteBuffer.wrap(Array(newlineByte))

    try {
      val iterator: LongIterator = matchingLines.getLongIterator()
      while (iterator.hasNext) {
        val physicalLineIndex: LineIndex = LineIndex(iterator.next())
        val lineSlice: MemorySegment = indexer.getLineSlice(physicalLineIndex)

        if (lineSlice != MemorySegment.NULL && lineSlice.byteSize() > 0L) {
          val byteBuffer: ByteBuffer = lineSlice.asByteBuffer()
          val lineLength: Long = lineSlice.byteSize()
          while (byteBuffer.hasRemaining) {
            targetChannel.write(byteBuffer)
          }
          bytesWrittenAccumulator += lineLength

          // Ensure trailing newline
          val lastByte: Byte = lineSlice.get(ValueLayout.JAVA_BYTE, lineLength - 1L)
          if (lastByte != newlineByte) {
            newlineBuffer.rewind()
            targetChannel.write(newlineBuffer)
            bytesWrittenAccumulator += 1L
          }
        }
      }
    } finally {
      targetChannel.close()
    }

    val durationNanos: Long = System.nanoTime() - startNanos
    SliceResult(destinationPath, bytesWrittenAccumulator, linesCount, durationNanos)
  }
}
