package umbrix.ui.settings

/**
 * Reactive data model containing all user-configurable workstation preferences.
 */
data class WorkstationSettings(
    val isMinimapEnabled: Boolean = true,
    val minimapWidthDp: Int = 48,
    val isSearchHighlightingEnabled: Boolean = true,
    val activeLanguageProfileId: String = "auto",
    val customErrorKeywords: String = "",
    val customWarnKeywords: String = "",
    val maxShapedCharsPerLine: Int = 4096,
    val enableSimdAcceleration: Boolean = true
)
