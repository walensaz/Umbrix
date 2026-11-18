package umbrix.plugin.api.action

import java.lang.foreign.MemorySegment
import umbrix.plugin.api.{PluginSessionView, PluginWorkspaceController}

/**
 * UI presentation locations where a [[PluginAction]] can be surfaced.
 */
enum PluginActionLocation extends java.lang.Enum[PluginActionLocation] {
  case VIEWPORT_CONTEXT_MENU
  case COMMAND_PALETTE
  case HAMBURGER_MENU
  case BOTH
  case ALL
}

/**
 * Context passed to a `PluginAction` when invoked from the right-click line menu, Command Palette, or Hamburger Menu.
 */
final case class PluginActionInvocation(
  session: PluginSessionView,
  workspace: PluginWorkspaceController,
  recordIndex: Long = -1L,
  rawRecordSlice: MemorySegment = MemorySegment.NULL,
  recordText: String = "",
  selectedText: String = ""
) {
  def hasSession: Boolean = session != null
}

/**
 * Pluggable command or context action invokable by users from the UI.
 *
 * Can appear in the viewport right-click menu, Command Palette, or main application menu.
 */
trait PluginAction {
  def actionId: String
  def label: String
  def category: String = "Actions"
  def iconGlyph: String = "⚡"
  def shortcutHint: String = ""
  def location: PluginActionLocation = PluginActionLocation.BOTH

  /**
   * Controls whether this action is shown in the right-click menu for the given line/selection.
   */
  def isApplicable(
    session: PluginSessionView,
    recordIndex: Long,
    recordText: String,
    selectedText: String
  ): Boolean = true

  def invoke(invocation: PluginActionInvocation): Unit
}
