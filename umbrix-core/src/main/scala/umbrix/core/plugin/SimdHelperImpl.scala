package umbrix.core.plugin

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.{BooleanSupplier, DoubleConsumer, LongConsumer}
import jdk.incubator.vector.{ByteVector, VectorSpecies}
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.simd.{ParallelChunkScanner, SimdHelper, SimdScalarFallback, SimdVectorKernel}
import umbrix.simd.{VectorScanner, VectorSubstringScanner}

/**
 * Concrete hardware-accelerated SIMD helper implementation exposed to plugins.
 *
 * Wraps host VectorScanner and VectorSubstringScanner kernels, provides aligned
 * 3-stage ByteVector loops, and executes multi-core parallel chunk scans over document sessions.
 */
class SimdHelperImpl extends SimdHelper {

  def preferredSpecies: VectorSpecies[java.lang.Byte] = ByteVector.SPECIES_PREFERRED
  def vectorByteWidth: Int = VectorScanner.vectorByteWidth

  def findFirstByte(segment: MemorySegment, startOffset: Long, length: Long, targetByte: Byte): Long = {
    VectorScanner.findFirstByte(segment, startOffset, length, targetByte)
  }

  def countByte(segment: MemorySegment, startOffset: Long, length: Long, targetByte: Byte): Long = {
    VectorScanner.countByte(segment, startOffset, length, targetByte)
  }

  def scanByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte,
    matchConsumer: LongConsumer
  ): Long = {
    VectorScanner.scanByte(segment, startOffset, length, targetByte, matchConsumer)
  }

  def scanEitherByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    byteA: Byte,
    byteB: Byte,
    matchConsumer: LongConsumer
  ): Long = {
    if (length <= 0L) {
      0L
    } else {
      runAlignedVectorLoop(
        segment,
        startOffset,
        length,
        (vectorChunk: ByteVector, _: Long) => {
          val maskA = vectorChunk.eq(byteA)
          val maskB = vectorChunk.eq(byteB)
          maskA.or(maskB).toLong
        },
        (b: Byte, _: Long) => b == byteA || b == byteB,
        matchConsumer
      )
    }
  }

  def scanLiteral(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    needle: Array[Byte],
    matchConsumer: LongConsumer
  ): Long = {
    VectorSubstringScanner.scanLiteral(segment, startOffset, length, needle, matchConsumer)
  }

  def scanLiteralIgnoreCase(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    needle: Array[Byte],
    matchConsumer: LongConsumer
  ): Long = {
    VectorSubstringScanner.scanLiteralCaseInsensitive(segment, startOffset, length, needle, matchConsumer)
  }

  def runAlignedVectorLoop(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    vectorKernel: SimdVectorKernel,
    scalarFallback: SimdScalarFallback,
    matchConsumer: LongConsumer
  ): Long = {
    if (length <= 0L) {
      0L
    } else {
      var currentOffset = startOffset
      val endOffset = startOffset + length
      var totalMatches = 0L

      // 1. Prologue: scan unaligned head bytes until aligned to vectorByteWidth
      val rawAddress = if (segment.isNative) segment.address() + startOffset else startOffset
      val misalignment = (rawAddress & (vectorByteWidth - 1L)).toInt
      if (misalignment != 0) {
        val headBytes = math.min((vectorByteWidth - misalignment).toLong, length)
        val headEnd = startOffset + headBytes
        while (currentOffset < headEnd) {
          val b = segment.get(ValueLayout.JAVA_BYTE, currentOffset)
          if (scalarFallback.evaluateByte(b, currentOffset)) {
            matchConsumer.accept(currentOffset)
            totalMatches += 1L
          }
          currentOffset += 1L
        }
      }

      // 2. Aligned vector loop body
      val vectorRemainder = (endOffset - currentOffset) % vectorByteWidth.toLong
      val vectorBound = (endOffset - currentOffset) - vectorRemainder + currentOffset
      val nativeOrder = ByteOrder.nativeOrder()

      while (currentOffset < vectorBound) {
        val chunk = ByteVector.fromMemorySegment(preferredSpecies, segment, currentOffset, nativeOrder)
        var bitmask = vectorKernel.evaluateVector(chunk, currentOffset)
        while (bitmask != 0L) {
          val lane = java.lang.Long.numberOfTrailingZeros(bitmask)
          matchConsumer.accept(currentOffset + lane.toLong)
          totalMatches += 1L
          bitmask &= (bitmask - 1L)
        }
        currentOffset += vectorByteWidth.toLong
      }

      // 3. Epilogue: scan unaligned tail bytes
      while (currentOffset < endOffset) {
        val b = segment.get(ValueLayout.JAVA_BYTE, currentOffset)
        if (scalarFallback.evaluateByte(b, currentOffset)) {
          matchConsumer.accept(currentOffset)
          totalMatches += 1L
        }
        currentOffset += 1L
      }

      totalMatches
    }
  }

  def scanSessionParallel[R](
    session: PluginSessionView,
    chunkSizeBytes: Long,
    boundaryOverlapBytes: Int,
    isCancelled: BooleanSupplier,
    onProgress: DoubleConsumer,
    chunkScanner: ParallelChunkScanner[R]
  ): java.util.List[R] = {
    val fileSize = session.fileSizeBytes
    if (fileSize <= 0L) {
      Collections.emptyList[R]()
    } else {
      val effectiveChunkSize = math.max(65536L, chunkSizeBytes)
      val totalChunks = math.ceil(fileSize.toDouble / effectiveChunkSize.toDouble).toInt
      val results = new java.util.ArrayList[R](Collections.nCopies(totalChunks, null.asInstanceOf[R]))
      val completedChunks = new AtomicInteger(0)

      val tasks = (0 until totalChunks).map { chunkIndex =>
        CompletableFuture.supplyAsync(
          () => {
            if (isCancelled.getAsBoolean) {
              null.asInstanceOf[R]
            } else {
              val chunkStart = chunkIndex.toLong * effectiveChunkSize
              val validLen = math.min(effectiveChunkSize, fileSize - chunkStart)
              val sliceLen = math.min(validLen + boundaryOverlapBytes.toLong, fileSize - chunkStart)
              val slice = session.readSlice(chunkStart, sliceLen)
              val res = chunkScanner.scanChunk(chunkIndex, slice, chunkStart, validLen, isCancelled)
              val done = completedChunks.incrementAndGet()
              onProgress.accept(done.toDouble / totalChunks.toDouble)
              res
            }
          }
        )
      }

      CompletableFuture.allOf(tasks.toArray*).join()
      var i = 0
      while (i < totalChunks) {
        results.set(i, tasks(i).get())
        i += 1
      }
      results
    }
  }
}
