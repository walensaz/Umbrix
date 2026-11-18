package umbrix.ui.theme

import androidx.compose.ui.text.font.FontWeight

/**
 * Immutable configuration specification governing text rendering in both the
 * off-heap log editor viewport and the IDE chrome/UI surfaces.
 *
 * @property editorFontFamily Preferred font family for monospace log viewport rendering.
 * @property editorFontSizePt Base font size in typographic points (pt) for log rows (8.0pt to 32.0pt).
 * @property lineHeightMultiplier Multiplier applied to [editorFontSizePt] to compute row height (1.0x to 2.0x).
 * @property editorFontWeight Monospace glyph stroke weight (300=Light, 400=Regular, 500=Medium, 700=Bold).
 * @property uiFontFamily Preferred font family for IDE chrome, menus, tabs, and dialogs.
 * @property uiFontSizePt Base font size in typographic points (pt) for application UI elements.
 * @property enableLigatures When true, enables OpenType contextual ligatures ('calt', 'liga').
 */
data class FontSpec(
    val editorFontFamily: String = "JetBrains Mono",
    val editorFontSizePt: Float = 13.0f,
    val lineHeightMultiplier: Float = 1.4f,
    val editorFontWeight: Int = 400,
    val uiFontFamily: String = "Inter",
    val uiFontSizePt: Float = 13.0f,
    val enableLigatures: Boolean = true
) {
    init {
        require(editorFontSizePt in MIN_FONT_SIZE..MAX_FONT_SIZE) {
            "editorFontSizePt ($editorFontSizePt) must be between $MIN_FONT_SIZE and $MAX_FONT_SIZE"
        }
        require(lineHeightMultiplier in MIN_LINE_HEIGHT_MULT..MAX_LINE_HEIGHT_MULT) {
            "lineHeightMultiplier ($lineHeightMultiplier) must be between $MIN_LINE_HEIGHT_MULT and $MAX_LINE_HEIGHT_MULT"
        }
        require(uiFontSizePt in MIN_UI_FONT_SIZE..MAX_UI_FONT_SIZE) {
            "uiFontSizePt ($uiFontSizePt) must be between $MIN_UI_FONT_SIZE and $MAX_UI_FONT_SIZE"
        }
    }

    /**
     * Resolves the Compose [FontWeight] representation of [editorFontWeight].
     */
    val resolvedEditorWeight: FontWeight
        get() = when (editorFontWeight) {
            300 -> FontWeight.Light
            400 -> FontWeight.Normal
            500 -> FontWeight.Medium
            600 -> FontWeight.SemiBold
            700 -> FontWeight.Bold
            else -> FontWeight.W400
        }

    companion object {
        const val MIN_FONT_SIZE: Float = 8.0f
        const val MAX_FONT_SIZE: Float = 32.0f
        const val DEFAULT_FONT_SIZE: Float = 13.0f

        const val MIN_LINE_HEIGHT_MULT: Float = 1.0f
        const val MAX_LINE_HEIGHT_MULT: Float = 2.0f
        const val DEFAULT_LINE_HEIGHT_MULT: Float = 1.4f

        const val MIN_UI_FONT_SIZE: Float = 10.0f
        const val MAX_UI_FONT_SIZE: Float = 20.0f
        const val DEFAULT_UI_FONT_SIZE: Float = 13.0f

        val SUPPORTED_WEIGHTS: List<Int> = listOf(300, 400, 500, 700)
    }
}
