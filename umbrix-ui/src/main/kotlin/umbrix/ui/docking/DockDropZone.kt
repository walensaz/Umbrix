package umbrix.ui.docking

/**
 * Drop target zones computed during a drag-to-dock gesture across the workspace.
 */
enum class DockDropZone(val displayLabel: String, val glyph: String) {
    LEFT("Split Left", "◨"),
    RIGHT("Split Right", "◧"),
    TOP("Split Top", "⬒"),
    BOTTOM("Split Bottom", "⬓"),
    CENTER_TAB("Add to Tab Group", "📁")
}
