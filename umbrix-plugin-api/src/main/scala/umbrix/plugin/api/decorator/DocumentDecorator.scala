package umbrix.plugin.api.decorator

import java.lang.foreign.MemorySegment
import umbrix.plugin.api.PluginSessionView

/**
 * Visual badge and tooltip rendered in the line-number gutter for a record.
 */
final case class GutterDecoration(
  badgeText: String,
  colorArgb: Int,
  tooltipSummary: String
)

/**
 * High-performance inline tag or badge rendered alongside text in the viewport.
 */
final case class InlineChipDecoration(
  charOffset: Int,
  label: String,
  textColorArgb: Int,
  backgroundColorArgb: Int
)

/**
 * Rich tooltip popup rendered when hovering over tokens or glyphs in the viewport.
 */
final case class HoverCardDecoration(
  title: String,
  subtitle: String,
  keyValuePairs: Array[(String, String)],
  monospacePayload: String = ""
)

/**
 * Pluggable document decorator extending the 64-bit viewport presentation.
 *
 * Provides gutter badges, inline chip decorations, hover cards, and virtual multi-line
 * expansion payloads for the visible records without altering underlying log bytes.
 */
trait DocumentDecorator {
  def id: String
  def displayName: String

  def resolveGutterDecoration(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): GutterDecoration = null

  def resolveInlineChips(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    shapedRecordText: String,
    targetList: java.util.List[InlineChipDecoration]
  ): Unit = ()

  def resolveHoverCard(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    shapedRecordText: String,
    charColumn: Int
  ): HoverCardDecoration = null

  def canExpandRecord(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Boolean = false

  def populateVirtualExpansion(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Unit = ()
}
