package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Sublime Monokai theme for Umbrix Workstation.
 * Modeled after the classic Monokai palette by Wimer Hazenberg from Sublime Text.
 * Warm, earthy dark charcoal with punchy neon green, magenta, cyan, and amber accents.
 */
object SublimeMonokaiTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Sublime Monokai"
    override val displayName: String = "Sublime Monokai"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF272822)
    override val surfacePanel: Color = Color(0xFF1E1F1C)
    override val surfaceElevated: Color = Color(0xFF3E3D32)
    override val surfaceGutter: Color = Color(0xFF1D1E19)
    override val surfaceScrollbarTrack: Color = Color(0xFF1E1F1C)
    override val surfaceInput: Color = Color(0xFF1E1F1C)
    override val surfaceHover: Color = Color(0xFF3E3D32)
    override val surfaceDropdown: Color = Color(0xFF1E1F1C)
    override val surfaceTooltip: Color = Color(0xFF272822)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFFF8F8F2)
    override val textSecondary: Color = Color(0xFFCFD0C2)
    override val textMuted: Color = Color(0xFF75715E)
    override val textSubtle: Color = Color(0xFF595548)
    override val textDimmed: Color = Color(0xFF49483E)
    override val textFaint: Color = Color(0xFF383730)
    override val textEditor: Color = Color(0xFFF8F8F2)
    override val textLink: Color = Color(0xFF66D9EF)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF75715E)
    override val gutterLineSelected: Color = Color(0xFFA6E22E)
    override val gutterLineActiveMatch: Color = Color(0xFF66D9EF)
    override val gutterLineMatch: Color = Color(0xFFE6DB74)
    override val gutterLineFiltered: Color = Color(0xFFAE81FF)
    override val gutterBorder: Color = Color(0xFF3E3D32)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x3349483E)
    override val rowActiveMatch: Color = Color(0x2866D9EF)
    override val rowMatch: Color = Color(0x1066D9EF)
    override val rowFilteredMatch: Color = Color(0x0CAE81FF)
    override val searchHighlightActive: Color = Color(0xFFFFE792)
    override val searchHighlightInactive: Color = Color(0x66E6DB74)
    override val searchTextActive: Color = Color(0xFF272822)
    override val searchTextInactive: Color = Color(0xFFF8F8F2)
    override val selectionBackground: Color = Color(0xFF49483E)

    // Forensic Severity
    override val severityError: Color = Color(0xFFF92672)
    override val severityWarn: Color = Color(0xFFE6DB74)
    override val severityInfo: Color = Color(0xFF66D9EF)
    override val severityDebug: Color = Color(0xFFA6E22E)
    override val severityTrace: Color = Color(0xFFAE81FF)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFFA6E22E)
    override val progressGradientEnd: Color = Color(0xFF66D9EF)
    override val badgeLinesBackground: Color = Color(0xFF2A3A40)
    override val badgeLinesText: Color = Color(0xFF66D9EF)
    override val badgeSizeBackground: Color = Color(0xFF283820)
    override val badgeSizeText: Color = Color(0xFFA6E22E)
    override val badgeSimdBackground: Color = Color(0xFF382038)
    override val badgeSimdText: Color = Color(0xFFAE81FF)
    override val badgeDurationBackground: Color = Color(0xFF38341E)
    override val badgeDurationText: Color = Color(0xFFE6DB74)
    override val badgeErrorBackground: Color = Color(0xFF401E28)
    override val badgeErrorText: Color = Color(0xFFF92672)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF1E1F1C)
    override val scrubberFillStart: Color = Color(0xFFA6E22E)
    override val scrubberFillEnd: Color = Color(0xFF66D9EF)
    override val scrubberHandle: Color = Color(0xFF66D9EF)
    override val scrubberHandleBorder: Color = Color(0xFFA6E22E)
    override val latencyGapAmber: Color = Color(0xFFE6DB74)
    override val latencyGapRed: Color = Color(0xFFF92672)
    override val temporalFocusBadgeBackground: Color = Color(0xFF2A3A40)
    override val temporalFocusBadgeText: Color = Color(0xFF66D9EF)
    override val temporalFocusDimOverlay: Color = Color(0x99000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x3366D9EF)
    override val minimapError: Color = Color(0xFFF92672)
    override val minimapWarn: Color = Color(0xFFE6DB74)
    override val minimapMatch: Color = Color(0xFFFFE792)
    override val minimapViewportRect: Color = Color(0x22FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF1E1F1C)
    override val titleBarText: Color = Color(0xFFF8F8F2)
    override val windowControlHoverClose: Color = Color(0xFFF92672)
    override val windowControlHover: Color = Color(0xFF3E3D32)
    override val accentPrimary: Color = Color(0xFFA6E22E)
    override val accentSecondary: Color = Color(0xFFF92672)
    override val border: Color = Color(0xFF3E3D32)
    override val borderFocused: Color = Color(0xFFA6E22E)

    // Menu System
    override val menuBackground: Color = Color(0xFF1E1F1C)
    override val menuBorder: Color = Color(0xFF3E3D32)
    override val menuItemHover: Color = Color(0xFF3E3D32)
    override val menuSeparator: Color = Color(0xFF3E3D32)
    override val menuShortcutText: Color = Color(0xFF75715E)
    override val menuIconTint: Color = Color(0xFFCFD0C2)
}
