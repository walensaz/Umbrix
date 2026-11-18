package umbrix.core.session

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.file.Path
import umbrix.core.types.{ByteOffset, LineIndex}
import umbrix.index.PieceTable
import umbrix.plugin.api.PluginSessionView
import umbrix.simd.VectorScanner

/**
 * Concrete implementation of `PluginSessionView` exposing a high-performance,
 * zero-copy view of an active workstation document session to third-party plugins.
 *
 * Provides both raw off-heap `MemorySegment` slices and ergonomic heap helpers,
 * backed directly by `LogSession` and its off-heap `PieceTable`.
 */
class PluginSessionViewImpl(
  val session: LogSession,
  val pieceTable: PieceTable
) extends PluginSessionView {

  def sessionId: String = session.path.toString

  def filePath: Path = session.path

  def fileName: String = {
    val fn = session.path.getFileName
    if (fn != null) fn.toString else "unknown"
  }

  override def activeFormatId: String = session.activeFormatProviderId.getOrElse("")

  def fileSizeBytes: Long = session.storageReader.sizeBytes

  def totalRecords: Long = session.indexer.totalLines.value

  def simdVectorByteWidth: Int = VectorScanner.vectorByteWidth

  def readSlice(offset: Long, length: Long): MemorySegment = {
    session.storageReader.readSlice(offset, length)
  }

  def getRecordSlice(recordIndex: Long): MemorySegment = {
    session.indexer.getLineSlice(LineIndex(recordIndex))
  }

  def readRecordBytes(recordIndex: Long, maxBytes: Int = 65536): Array[Byte] = {
    val slice = getRecordSlice(recordIndex)
    if (slice == MemorySegment.NULL || slice.byteSize() == 0L) {
      new Array[Byte](0)
    } else {
      val len = math.min(slice.byteSize(), maxBytes.toLong).toInt
      val arr = new Array[Byte](len)
      MemorySegment.copy(slice, ValueLayout.JAVA_BYTE, 0L, arr, 0, len)
      arr
    }
  }

  def byteOffsetForRecord(recordIndex: Long): Long = {
    session.indexer.byteOffsetForLine(LineIndex(recordIndex)).value
  }

  def recordIndexForByteOffset(byteOffset: Long): Long = {
    session.indexer.lineIndexForByteOffset(ByteOffset(byteOffset)).value
  }

  def getClampedRecordText(recordIndex: Long, maxChars: Int = 4096): String = {
    session.indexer.getLineText(LineIndex(recordIndex), maxChars)
  }

  def putVirtualExpansion(recordIndex: Long, utf8Payload: MemorySegment, lineCount: Int): Unit = {
    pieceTable.putVirtualExpansion(recordIndex, utf8Payload, lineCount)
  }

  def putVirtualExpansionText(recordIndex: Long, multiLineText: String): Unit = {
    pieceTable.putVirtualExpansionText(recordIndex, multiLineText)
  }

  def clearVirtualExpansion(recordIndex: Long): Unit = {
    pieceTable.clearVirtualExpansion(recordIndex)
  }

  def hasVirtualExpansion(recordIndex: Long): Boolean = {
    pieceTable.hasVirtualExpansion(recordIndex)
  }
}
