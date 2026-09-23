package umbrix.core.minimap

import java.lang.foreign.{Arena, MemorySegment}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.{CompletableFuture, Executor, ForkJoinPool}
import umbrix.core.profile.LogLanguageProfile
import umbrix.index.PhysicalLineIndexer
import umbrix.simd.VectorScanner
import umbrix.vfs.LogStorageReader
import umbrix.vfs.platform.PlatformMemoryAdvisor

/**
 * High-performance background scanner that constructs a MinimapDensityTable across 25GB–100GB+ logs.
 *
 * Runs asynchronously without delaying initial screen render, scanning line prefixes with SIMD vector kernels
 * and zero GC heap ingestion.
 */
object MinimapScanner {

  def build(
    indexer: PhysicalLineIndexer,
    profile: LogLanguageProfile,
    progressConsumer: java.util.function.DoubleConsumer = _ => ()
  ): MinimapDensityTable = {
    val storageReader: LogStorageReader = indexer.storageReader
    if (storageReader.path != null && Files.exists(storageReader.path)) {
      buildStreaming(indexer, storageReader.path, profile, progressConsumer = progressConsumer)
    } else {
      buildFromReader(indexer, profile, progressConsumer = progressConsumer)
    }
  }

  def buildStreaming(
    indexer: PhysicalLineIndexer,
    filePath: Path,
    profile: LogLanguageProfile,
    bufferCapacity: Long = PlatformMemoryAdvisor.MinimumStreamingBufferBytes,
    progressConsumer: java.util.function.DoubleConsumer = _ => ()
  ): MinimapDensityTable = {
    val table: MinimapDensityTable = new MinimapDensityTable()
    val totalDocumentLines: Long = indexer.totalLines.value
    val fileChannel: FileChannel = FileChannel.open(filePath, StandardOpenOption.READ)

    try {
      val documentSizeBytes: Long = fileChannel.size()
      if (documentSizeBytes <= 0L) {
        progressConsumer.accept(1.0)
        table
      } else {
        val clampedCapacity: Long = bufferCapacity.max(1024L * 1024L).min(documentSizeBytes.max(1024L * 1024L))
        val arena: Arena = Arena.ofConfined()
        try {
          val directSegment: MemorySegment = arena.allocate(clampedCapacity, 64)
          val directByteBuffer: ByteBuffer = directSegment.asByteBuffer()

          var chunkStartOffset: Long = 0L
          var currentLineIndexCounter: Long = 0L

          // Evaluate line 0 at file start
          val initialSeverity = profile.classifyLineSeverity(directSegment, 0L, math.min(256L, documentSizeBytes))
          table.increment(0, initialSeverity)

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
            val currentChunkLength: Long = bytesTransferred.toLong
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

                  val severity = if (remainingBytesInChunk >= 64L) {
                    profile.classifyLineSeverity(chunkMemorySlice, offsetWithinChunk, remainingBytesInChunk)
                  } else {
                    val remainingBytesInFile: Long = documentSizeBytes - nextLineStartOffset
                    if (remainingBytesInFile <= 0L) {
                      profile.classifyLineSeverity(MemorySegment.NULL, 0L, 0L)
                    } else {
                      val edgeSlice: MemorySegment = indexer.storageReader.readSlice(
                        nextLineStartOffset,
                        remainingBytesInFile.min(256L)
                      )
                      profile.classifyLineSeverity(edgeSlice, 0L, edgeSlice.byteSize())
                    }
                  }

                  val effectiveTotalLines = if (totalDocumentLines > 0L) totalDocumentLines else currentLineIndexCounter + 1000L
                  val binIndex = table.binForLine(currentLineIndexCounter, effectiveTotalLines)
                  table.increment(binIndex, severity)
                }
              }
            )

            chunkStartOffset += bytesTransferred.toLong
            progressConsumer.accept(chunkStartOffset.toDouble / documentSizeBytes.toDouble)
          }
          progressConsumer.accept(1.0)
          table
        } finally {
          arena.close()
        }
      }
    } finally {
      fileChannel.close()
    }
  }

  private def buildFromReader(
    indexer: PhysicalLineIndexer,
    profile: LogLanguageProfile,
    progressConsumer: java.util.function.DoubleConsumer = _ => ()
  ): MinimapDensityTable = {
    val table: MinimapDensityTable = new MinimapDensityTable()
    val storageReader: LogStorageReader = indexer.storageReader
    val totalDocumentLines: Long = indexer.totalLines.value
    val documentSizeBytes: Long = storageReader.sizeBytes

    if (documentSizeBytes <= 0L) {
      progressConsumer.accept(1.0)
      table
    } else {
      val scanChunkSizeBytes: Long = 16L * 1024L * 1024L
      var chunkStartOffset: Long = 0L
      var currentLineIndexCounter: Long = 0L

      while (chunkStartOffset < documentSizeBytes && !Thread.currentThread().isInterrupted) {
        val currentChunkLength: Long = scanChunkSizeBytes.min(documentSizeBytes - chunkStartOffset)
        val chunkSegment: MemorySegment = storageReader.readSlice(chunkStartOffset, currentChunkLength)
        val localChunkBaseOffset: Long = chunkStartOffset

        VectorScanner.scanNewlines(
          chunkSegment,
          0L,
          currentChunkLength,
          (relativeNewlineOffset: Long) => {
            val absoluteNewlineOffset: Long = localChunkBaseOffset + relativeNewlineOffset
            val nextLineStartOffset: Long = absoluteNewlineOffset + 1L

            if (nextLineStartOffset < documentSizeBytes) {
              currentLineIndexCounter += 1L
              val offsetWithinChunk: Long = relativeNewlineOffset + 1L
              val remainingBytesInChunk: Long = currentChunkLength - offsetWithinChunk

              val severity = if (remainingBytesInChunk >= 64L) {
                profile.classifyLineSeverity(chunkSegment, offsetWithinChunk, remainingBytesInChunk)
              } else {
                val remainingBytesInFile: Long = documentSizeBytes - nextLineStartOffset
                if (remainingBytesInFile <= 0L) {
                  profile.classifyLineSeverity(MemorySegment.NULL, 0L, 0L)
                } else {
                  val edgeSegment: MemorySegment = storageReader.readSlice(
                    nextLineStartOffset,
                    remainingBytesInFile.min(256L)
                  )
                  profile.classifyLineSeverity(edgeSegment, 0L, edgeSegment.byteSize())
                }
              }

              val effectiveTotalLines = if (totalDocumentLines > 0L) totalDocumentLines else currentLineIndexCounter + 1000L
              val binIndex = table.binForLine(currentLineIndexCounter, effectiveTotalLines)
              table.increment(binIndex, severity)
            }
          }
        )

        chunkStartOffset += currentChunkLength
        progressConsumer.accept(chunkStartOffset.toDouble / documentSizeBytes.toDouble)
      }
      progressConsumer.accept(1.0)
      table
    }
  }

  def buildAsync(
    indexer: PhysicalLineIndexer,
    profile: LogLanguageProfile,
    progressConsumer: java.util.function.DoubleConsumer = _ => (),
    executor: Executor = ForkJoinPool.commonPool()
  ): CompletableFuture[MinimapDensityTable] = {
    CompletableFuture.supplyAsync(() => build(indexer, profile, progressConsumer), executor)
  }
}
