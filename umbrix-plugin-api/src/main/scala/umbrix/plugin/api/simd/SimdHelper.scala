package umbrix.plugin.api.simd

import java.lang.foreign.MemorySegment
import java.util.function.{BooleanSupplier, DoubleConsumer, LongConsumer}
import jdk.incubator.vector.{ByteVector, VectorSpecies}
import umbrix.plugin.api.PluginSessionView

/**
 * Vectorized kernel evaluating a CPU SIMD vector register lane for byte pattern matches.
 * Returns a 64-bit bitmask representing matching lane indices.
 */
@FunctionalInterface
trait SimdVectorKernel {
  def evaluateVector(vectorChunk: ByteVector, currentByteOffset: Long): Long
}

/**
 * Scalar fallback handler processing unaligned pre-loop heads and post-loop tail bytes.
 */
@FunctionalInterface
trait SimdScalarFallback {
  def evaluateByte(byteValue: Byte, currentByteOffset: Long): Boolean
}

/**
 * Parallel chunk scanner callback invoked concurrently across memory-mapped chunk slices.
 */
@FunctionalInterface
trait ParallelChunkScanner[R] {
  def scanChunk(
    chunkIndex: Int,
    chunkSegment: MemorySegment,
    chunkFileStartOffset: Long,
    validChunkLength: Long,
    isCancelled: BooleanSupplier
  ): R
}

/**
 * Hardware SIMD acceleration helper exposed to plugins.
 *
 * Wraps AVX-512, AVX2, and ARM Neon vector kernels via the JDK Vector API, enabling
 * 15–25 GB/s literal searches, byte counting, and parallel chunk processing without heap allocations.
 */
trait SimdHelper {
  def preferredSpecies: VectorSpecies[java.lang.Byte]
  def vectorByteWidth: Int

  def findFirstByte(segment: MemorySegment, startOffset: Long, length: Long, targetByte: Byte): Long
  def countByte(segment: MemorySegment, startOffset: Long, length: Long, targetByte: Byte): Long
  def scanByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    targetByte: Byte,
    matchConsumer: LongConsumer
  ): Long

  def scanEitherByte(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    byteA: Byte,
    byteB: Byte,
    matchConsumer: LongConsumer
  ): Long

  def scanLiteral(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    needle: Array[Byte],
    matchConsumer: LongConsumer
  ): Long

  def scanLiteralIgnoreCase(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    needle: Array[Byte],
    matchConsumer: LongConsumer
  ): Long

  def runAlignedVectorLoop(
    segment: MemorySegment,
    startOffset: Long,
    length: Long,
    vectorKernel: SimdVectorKernel,
    scalarFallback: SimdScalarFallback,
    matchConsumer: LongConsumer
  ): Long

  def scanSessionParallel[R](
    session: PluginSessionView,
    chunkSizeBytes: Long,
    boundaryOverlapBytes: Int,
    isCancelled: BooleanSupplier,
    onProgress: DoubleConsumer,
    chunkScanner: ParallelChunkScanner[R]
  ): java.util.List[R]
}
