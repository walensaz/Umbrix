package umbrix.plugin.api.format

import java.lang.foreign.MemorySegment
import java.nio.file.Path
import java.util.function.LongConsumer
import umbrix.index.{RecordBoundaryFramer, RecordTextFormatter}

/**
 * Display layout strategy chosen when opening files recognized by a [[FileFormatProvider]].
 */
enum FormatOpenMode extends java.lang.Enum[FormatOpenMode] {
  case VIRTUAL_VIEWPORT
  case CUSTOM_DOCK_VIEW
  case SPLIT_VIEWPORT_AND_DOCK_VIEW
}

/**
 * Result returned by [[FileFormatProvider.probe]] indicating format detection match,
 * priority weight, display name, and preferred viewport mode.
 */
final case class FormatProbeResult(
  matched: Boolean,
  priority: Int,
  formatDisplayName: String,
  openMode: FormatOpenMode = FormatOpenMode.VIRTUAL_VIEWPORT,
  companionDockViewId: String = "",
  requiresConfirmation: Boolean = false
)

/**
 * Companion object for [[FormatProbeResult]] providing canonical unmatched sentinel.
 */
object FormatProbeResult {
  val NotMatched: FormatProbeResult = FormatProbeResult(false, -1, "")
}

/**
 * Framing kernel that identifies record boundaries in binary or custom-delimited files.
 */
trait RecordFramer extends RecordBoundaryFramer

/**
 * Formats raw record bytes into display text for the ~50 visible rows in the 64-bit viewport.
 */
trait RecordFormatter extends RecordTextFormatter

/**
 * SPI provider for custom and binary file formats (e.g. PCAP, HPROF, FIX, binary protobuf).
 *
 * Plugs custom boundary framers and on-demand viewport text formatters into Umbrix's
 * two-level sparse index without loading the file onto the JVM heap.
 */
trait FileFormatProvider {
  def id: String
  def displayName: String
  def supportedExtensions: Array[String]

  def probe(path: Path, headerSlice: MemorySegment, fileSizeBytes: Long): FormatProbeResult
  def createRecordFramer(path: Path, headerSlice: MemorySegment): RecordFramer
  def createRecordFormatter(path: Path, headerSlice: MemorySegment): RecordFormatter
}
