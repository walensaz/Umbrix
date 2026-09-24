package umbrix.ui.settings

import umbrix.ui.theme.FontSpec
import java.io.File

/**
 * Reactive data model containing all user-configurable workstation preferences.
 */
data class WorkstationSettings(
    val themeName: String = "Dark Carbon",
    val isMinimapEnabled: Boolean = true,
    val minimapWidthDp: Int = 48,
    val isSearchHighlightingEnabled: Boolean = true,
    val activeLanguageProfileId: String = "auto",
    val customErrorKeywords: String = "",
    val customWarnKeywords: String = "",
    val maxShapedCharsPerLine: Int = 4096,
    val enableSimdAcceleration: Boolean = true,
    val isLatencyGapHighlightingEnabled: Boolean = true,
    val latencyGapThresholdMs: Long = 1000L,
    val latencyGapSevereThresholdMs: Long = 5000L,

    // --- Phase 3: Font Configuration ---
    val editorFontFamily: String = "JetBrains Mono",
    val editorFontSizePt: Float = 13.0f,
    val lineHeightMultiplier: Float = 1.4f,
    val editorFontWeight: Int = 400,
    val uiFontFamily: String = "Inter",
    val uiFontSizePt: Float = 13.0f,
    val enableLigatures: Boolean = true,

    // --- Phase 4: Hamburger Menu View State ---
    val viewportMode: ViewportLayoutMode = ViewportLayoutMode.EXPANDABLE,
    val showLineNumbers: Boolean = true,
    val showWhitespaceChars: Boolean = false,
    val recentFiles: List<String> = emptyList()
) {
    /**
     * Returns a copy with [filePath] promoted to the head of [recentFiles], de-duplicated
     * (case-insensitively on Windows) and capped at [MAX_RECENT_FILES] entries.
     */
    fun withRecentFile(filePath: String): WorkstationSettings {
        val normalizedPath = filePath.trim()
        if (normalizedPath.isEmpty()) return this
        val isCaseInsensitiveFileSystem = File.separatorChar == '\\'
        val remainingPaths = recentFiles.filterNot { existingPath ->
            existingPath.equals(normalizedPath, ignoreCase = isCaseInsensitiveFileSystem)
        }
        return copy(recentFiles = (listOf(normalizedPath) + remainingPaths).take(MAX_RECENT_FILES))
    }

    /**
     * Converts configuration preferences into a strongly-typed [FontSpec].
     */
    fun toFontSpec(): FontSpec = FontSpec(
        editorFontFamily = editorFontFamily,
        editorFontSizePt = editorFontSizePt,
        lineHeightMultiplier = lineHeightMultiplier,
        editorFontWeight = editorFontWeight,
        uiFontFamily = uiFontFamily,
        uiFontSizePt = uiFontSizePt,
        enableLigatures = enableLigatures
    )

    companion object {
        /** Upper bound on persisted recent file entries shown in the Open Recent submenu. */
        const val MAX_RECENT_FILES: Int = 8
    }
}
