package umbrix.ui.palette

/**
 * High-level categorization for command palette items and filter tabs.
 */
enum class CommandCategory(val label: String, val iconGlyph: String) {
    ACTIONS("Actions", "⚡"),
    VIEW("View", "👁"),
    NAVIGATION("Navigation", "⏩"),
    SETTINGS("Settings", "⚙"),
    THEMES("Themes", "🎨"),
    FILES("Files & Tabs", "📁");

    companion object {
        fun fromPillLabel(label: String): CommandCategory? {
            return entries.firstOrNull {
                it.label.equals(label, ignoreCase = true) ||
                it.name.equals(label, ignoreCase = true) ||
                it.label.startsWith(label, ignoreCase = true)
            }
        }

        fun fromPrefixChar(char: Char): CommandCategory? = when (char) {
            '>' -> ACTIONS
            '#' -> NAVIGATION
            '@' -> FILES
            '?' -> ACTIONS
            else -> null
        }
    }
}

/**
 * An executable action exposed in the Command Palette.
 *
 * @param id Unique identifier (e.g. "view.toggle_minimap")
 * @param title Human-readable label displayed in the palette
 * @param category Category classification for grouping and IntelliJ tab filtering
 * @param shortcutHint Optional keybinding hint displayed on the right edge (e.g. "Ctrl+Shift+P")
 * @param keywords Auxiliary search keywords to boost fuzzy match discovery
 * @param isEnabled Lambda determining whether the command is active in current state
 * @param action Lambda executed when selected by the investigator
 */
data class PaletteCommand(
    val id: String,
    val title: String,
    val category: CommandCategory,
    val shortcutHint: String? = null,
    val keywords: List<String> = emptyList(),
    val isEnabled: () -> Boolean = { true },
    val action: () -> Unit
)

/**
 * A scored command match pairing a [command] with its computed fuzzy score.
 */
data class ScoredCommand(
    val command: PaletteCommand,
    val score: Int
)
