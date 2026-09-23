package umbrix.core.classifier

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.{CompletableFuture, Executor, ForkJoinPool}
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.types.LineIndex
import umbrix.index.PhysicalLineIndexer
import umbrix.simd.VectorScanner
import umbrix.vfs.LogStorageReader
import umbrix.vfs.platform.PlatformMemoryAdvisor

/**
 * Two-Tier Logical Event Boundary Classifier.
 *
 * Classifies physical lines into logical multi-line events (grouping Java/Python/Rust
 * stack traces, multi-line JSON payloads, and continuation frames under their parent event header).
 *
 * Populates a compressed Roaring64NavigableMap marking the 0-based physical line index of each event header,
 * enabling O(1) fold/unfold operations, stack trace collapsing, and event-based navigation.
 *
 * Evaluates line prefixes via high-throughput off-heap SIMD streaming without JVM heap allocations.
 */
class EventBoundaryClassifier(
  val indexer: PhysicalLineIndexer,
  val eventBoundaryBitmap: Roaring64NavigableMap
) {

  /** Total count of distinct logical events identified in the document. */
  def totalEvents: Long = {
    eventBoundaryBitmap.getLongCardinality
  }

  /** True if physical line `lineIndex` represents the beginning of a logical event. */
  def isEventHeader(lineIndex: LineIndex): Boolean = {
    if (lineIndex < LineIndex.Zero || lineIndex >= indexer.totalLines) {
      false
    } else {
      eventBoundaryBitmap.contains(lineIndex.value)
    }
  }

  /**
   * Resolves the parent event header line index for any arbitrary line.
   *
   * Executes in sub-microsecond time via Roaring64NavigableMap branchless rank-select.
   *
   * @param lineIndex 0-based physical line index
   * @return 0-based physical line index of the parent event header as LineIndex
   */
  def parentEventForLine(lineIndex: LineIndex): LineIndex = {
    if (lineIndex <= LineIndex.Zero || eventBoundaryBitmap.isEmpty) {
      LineIndex.Zero
    } else {
      if (eventBoundaryBitmap.contains(lineIndex.value)) {
        lineIndex
      } else {
        val bitmapRank: Long = eventBoundaryBitmap.rankLong(lineIndex.value)
        val parent = if (bitmapRank > 0L) {
          eventBoundaryBitmap.select(bitmapRank - 1L)
        } else {
          0L
        }
        LineIndex(parent)
      }
    }
  }

  /**
   * Computes the number of physical lines spanned by the event beginning at `eventHeaderLine`.
   */
  def eventLineSpan(eventHeaderLine: LineIndex): Long = {
    if (!isEventHeader(eventHeaderLine)) {
      1L
    } else {
      val eventRank: Long = eventBoundaryBitmap.rankLong(eventHeaderLine.value)
      if (eventRank < eventBoundaryBitmap.getLongCardinality) {
        val nextHeaderLine: Long = eventBoundaryBitmap.select(eventRank)
        nextHeaderLine - eventHeaderLine.value
      } else {
        indexer.totalLines.value - eventHeaderLine.value
      }
    }
  }
}

object EventBoundaryClassifier {

  private val CausedByPrefixBytes: Array[Byte] = "Caused by:".getBytes(StandardCharsets.US_ASCII)
  private val SuppressedPrefixBytes: Array[Byte] = "Suppressed:".getBytes(StandardCharsets.US_ASCII)

  /**
   * Constructs an unclassified fallback instance where every line is treated as an independent event header.
   * Employed during progressive background indexing to allow immediate viewport interaction without blocking.
   */
  def unclassified(lineIndexer: PhysicalLineIndexer): EventBoundaryClassifier = {
    new EventBoundaryClassifier(lineIndexer, new Roaring64NavigableMap()) {
      override def isEventHeader(lineIndex: LineIndex): Boolean = {
        lineIndex >= LineIndex.Zero && lineIndex < lineIndexer.totalLines
      }
      override def parentEventForLine(lineIndex: LineIndex): LineIndex = {
        val maxValid = if (lineIndexer.totalLines > LineIndex.Zero) lineIndexer.totalLines - 1L else LineIndex.Zero
        lineIndex.coerceIn(LineIndex.Zero, maxValid)
      }
      override def totalEvents: Long = {
        lineIndexer.totalLines.value
      }
      override def eventLineSpan(eventHeaderLine: LineIndex): Long = {
        1L
      }
    }
  }

  /**
   * Builds an EventBoundaryClassifier using anti-thrashing streaming channel I/O when possible,
   * avoiding page-cache pollution on 25GB-100GB files.
   */
  def build(
    indexer: PhysicalLineIndexer,
    progressConsumer: java.util.function.DoubleConsumer = _ => ()
  ): EventBoundaryClassifier = {
    val storageReader: LogStorageReader = indexer.storageReader
    if (storageReader.path != null && Files.exists(storageReader.path)) {
      buildStreaming(indexer, storageReader.path, progressConsumer = progressConsumer)
    } else {
      buildFromReader(indexer, progressConsumer = progressConsumer)
    }
  }

  /**
   * Builds the classifier by streaming sequentially through FileChannel into a single reusable direct buffer.
   */
  def buildStreaming(
    indexer: PhysicalLineIndexer,
    filePath: Path,
    bufferCapacity: Long = PlatformMemoryAdvisor.MinimumStreamingBufferBytes,
    progressConsumer: java.util.function.DoubleConsumer = _ => ()
  ): EventBoundaryClassifier = {
    val totalDocumentLines: Long = indexer.totalLines.value
    val boundaryBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()

    if (totalDocumentLines > 0L) {
      boundaryBitmap.addLong(0L)
    }

    if (totalDocumentLines > 1L) {
      val fileChannel: FileChannel = FileChannel.open(filePath, StandardOpenOption.READ)
      try {
        val documentSizeBytes: Long = fileChannel.size()
        val clampedCapacity: Long = bufferCapacity.max(1024L * 1024L).min(documentSizeBytes.max(1024L * 1024L))
        val arena: Arena = Arena.ofConfined()
        try {
          val directSegment: MemorySegment = arena.allocate(clampedCapacity, 64)
          val directByteBuffer: ByteBuffer = directSegment.asByteBuffer()

          var chunkStartOffset: Long = 0L
          var currentLineIndexCounter: Long = 0L

          while (chunkStartOffset < documentSizeBytes && !Thread.currentThread().isInterrupted) {
            val bytesToRead: Long = clampedCapacity.min(documentSizeBytes - chunkStartOffset)
            directByteBuffer.clear()
            directByteBuffer.limit(bytesToRead.toInt)

            var bytesTransferred: Int = 0
            while (bytesTransferred < bytesToRead.toInt) {
              val readResult: Int = fileChannel.read(directByteBuffer, chunkStartOffset + bytesTransferred.toLong)
              if (readResult < 0) {
                bytesTransferred = bytesToRead.toInt
              } else {
                bytesTransferred += readResult
              }
            }

            val chunkMemorySlice: MemorySegment = directSegment.asSlice(0L, bytesTransferred.toLong)
            val localChunkBaseOffset: Long = chunkStartOffset
            val currentChunkLength: Long = bytesTransferred.toLong

            VectorScanner.scanNewlines(
              chunkMemorySlice,
              0L,
              currentChunkLength,
              (relativeNewlineOffset: Long) => {
                val absoluteNewlineOffset: Long = localChunkBaseOffset + relativeNewlineOffset
                val nextLineStartOffset: Long = absoluteNewlineOffset + 1L

                if (nextLineStartOffset < documentSizeBytes) {
                  currentLineIndexCounter += 1L
                  val offsetWithinChunk: Long = relativeNewlineOffset + 1L
                  val remainingBytesInChunk: Long = currentChunkLength - offsetWithinChunk

                  val isLogicalHeader: Boolean = {
                    if (remainingBytesInChunk >= 12L) {
                      isEventHeaderPrefix(chunkMemorySlice, offsetWithinChunk, remainingBytesInChunk)
                    } else {
                      val remainingBytesInFile: Long = documentSizeBytes - nextLineStartOffset
                      if (remainingBytesInFile <= 0L) {
                        false
                      } else {
                        val edgeSlice: MemorySegment = indexer.storageReader.readSlice(
                          nextLineStartOffset,
                          remainingBytesInFile.min(12L)
                        )
                        isEventHeaderPrefix(edgeSlice, 0L, edgeSlice.byteSize())
                      }
                    }
                  }

                  if (isLogicalHeader) {
                    boundaryBitmap.addLong(currentLineIndexCounter)
                  }
                }
              }
            )

            chunkStartOffset += bytesTransferred.toLong
            progressConsumer.accept(chunkStartOffset.toDouble / documentSizeBytes.toDouble)
          }
          progressConsumer.accept(1.0)
        } finally {
          arena.close()
        }
      } finally {
        fileChannel.close()
      }
    }

    new EventBoundaryClassifier(indexer, boundaryBitmap)
  }

  /**
   * Fallback in-memory builder scanning through `indexer.storageReader`.
   */
  private def buildFromReader(
    indexer: PhysicalLineIndexer,
    progressConsumer: java.util.function.DoubleConsumer = _ => ()
  ): EventBoundaryClassifier = {
    val storageReader: LogStorageReader = indexer.storageReader
    val totalDocumentLines: Long = indexer.totalLines.value
    val documentSizeBytes: Long = storageReader.sizeBytes
    val boundaryBitmap: Roaring64NavigableMap = new Roaring64NavigableMap()

    if (totalDocumentLines > 0L) {
      boundaryBitmap.addLong(0L)
    }

    if (totalDocumentLines > 1L && documentSizeBytes > 0L) {
      val scanChunkSizeBytes: Long = 16L * 1024L * 1024L
      var chunkStartOffset: Long = 0L
      var currentLineIndexCounter: Long = 0L

      while (chunkStartOffset < documentSizeBytes && !Thread.currentThread().isInterrupted) {
        val currentChunkLength: Long = scanChunkSizeBytes.min(documentSizeBytes - chunkStartOffset)
        val chunkMemorySlice: MemorySegment = storageReader.readSlice(chunkStartOffset, currentChunkLength)
        val localChunkBaseOffset: Long = chunkStartOffset

        VectorScanner.scanNewlines(
          chunkMemorySlice,
          0L,
          currentChunkLength,
          (relativeNewlineOffset: Long) => {
            val absoluteNewlineOffset: Long = localChunkBaseOffset + relativeNewlineOffset
            val nextLineStartOffset: Long = absoluteNewlineOffset + 1L

            if (nextLineStartOffset < documentSizeBytes) {
              currentLineIndexCounter += 1L
              val offsetWithinChunk: Long = relativeNewlineOffset + 1L
              val remainingBytesInChunk: Long = currentChunkLength - offsetWithinChunk

              val isLogicalHeader: Boolean = {
                if (remainingBytesInChunk >= 12L) {
                  isEventHeaderPrefix(chunkMemorySlice, offsetWithinChunk, remainingBytesInChunk)
                } else {
                  val remainingBytesInFile: Long = documentSizeBytes - nextLineStartOffset
                  if (remainingBytesInFile <= 0L) {
                    false
                  } else {
                    val edgeMemorySlice: MemorySegment = storageReader.readSlice(
                      nextLineStartOffset,
                      remainingBytesInFile.min(12L)
                    )
                    isEventHeaderPrefix(edgeMemorySlice, 0L, edgeMemorySlice.byteSize())
                  }
                }
              }

              if (isLogicalHeader) {
                boundaryBitmap.addLong(currentLineIndexCounter)
              }
            }
          }
        )
        chunkStartOffset += currentChunkLength
        progressConsumer.accept(chunkStartOffset.toDouble / documentSizeBytes.toDouble)
      }
      progressConsumer.accept(1.0)
    }

    new EventBoundaryClassifier(indexer, boundaryBitmap)
  }

  /**
   * Asynchronously builds an EventBoundaryClassifier on a background executor / virtual thread.
   */
  def buildAsync(
    indexer: PhysicalLineIndexer,
    progressConsumer: java.util.function.DoubleConsumer,
    executor: Executor
  ): CompletableFuture[EventBoundaryClassifier] = {
    CompletableFuture.supplyAsync(() => build(indexer, progressConsumer), executor)
  }

  def buildAsync(
    indexer: PhysicalLineIndexer,
    progressConsumer: java.util.function.DoubleConsumer
  ): CompletableFuture[EventBoundaryClassifier] = {
    buildAsync(indexer, progressConsumer, ForkJoinPool.commonPool())
  }

  def buildAsync(
    indexer: PhysicalLineIndexer,
    executor: Executor
  ): CompletableFuture[EventBoundaryClassifier] = {
    buildAsync(indexer, _ => (), executor)
  }

  def buildAsync(
    indexer: PhysicalLineIndexer
  ): CompletableFuture[EventBoundaryClassifier] = {
    buildAsync(indexer, _ => (), ForkJoinPool.commonPool())
  }

  /**
   * Detects whether leading off-heap bytes represent a new logical event header
   * rather than an indented continuation line, exception cause, or stack frame.
   */
  private def isEventHeaderPrefix(
    segment: MemorySegment,
    offset: Long,
    availableLength: Long
  ): Boolean = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      false
    } else {
      val firstByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
      if (firstByte == '\n'.toByte || firstByte == '\r'.toByte) {
        false
      } else if (firstByte == '\t'.toByte) {
        false
      } else if (firstByte == ' '.toByte && availableLength > 1L && segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == ' '.toByte) {
        false
      } else if (firstByte == 'a'.toByte && availableLength >= 3L &&
              segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == 't'.toByte &&
              segment.get(ValueLayout.JAVA_BYTE, offset + 2L) == ' '.toByte) {
        false
      } else if (firstByte == '.'.toByte && availableLength >= 4L &&
              segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == '.'.toByte &&
              segment.get(ValueLayout.JAVA_BYTE, offset + 2L) == '.'.toByte &&
              segment.get(ValueLayout.JAVA_BYTE, offset + 3L) == ' '.toByte) {
        false
      } else if (firstByte == 'C'.toByte && availableLength >= CausedByPrefixBytes.length.toLong && matchesPrefix(segment, offset, CausedByPrefixBytes)) {
        false
      } else if (firstByte == 'S'.toByte && availableLength >= SuppressedPrefixBytes.length.toLong && matchesPrefix(segment, offset, SuppressedPrefixBytes)) {
        false
      } else {
        true
      }
    }
  }

  private def matchesPrefix(segment: MemorySegment, offset: Long, prefixBytes: Array[Byte]): Boolean = {
    var byteIndex: Int = 0
    var prefixMatches: Boolean = true
    while (byteIndex < prefixBytes.length && prefixMatches) {
      if (segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex.toLong) != prefixBytes(byteIndex)) {
        prefixMatches = false
      }
      byteIndex += 1
    }
    prefixMatches
  }
}
