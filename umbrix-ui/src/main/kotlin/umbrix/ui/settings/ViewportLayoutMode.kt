package umbrix.ui.settings

/**
 * Tri-Mode Viewport layout topology selectable from the hamburger View submenu.
 *
 * @property id Stable machine-readable identifier persisted in settings.json.
 * @property displayLabel Human-readable label rendered in menus and settings.
 */
enum class ViewportLayoutMode(val id: String, val displayLabel: String) {
    COMPACT("compact", "Compact Mode"),
    EXPANDABLE("expandable", "Expandable Mode"),
    FULL_WRAP("full_wrap", "Full Wrap Mode");

    companion object {
        /** Resolves a persisted identifier, or null when unknown (e.g. hand-edited settings). */
        fun fromId(id: String): ViewportLayoutMode? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}
