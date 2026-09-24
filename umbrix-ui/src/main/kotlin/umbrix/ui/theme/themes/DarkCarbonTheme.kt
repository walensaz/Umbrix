package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Default Dark Carbon theme for Umbrix Workstation.
 * Modeled after the JetBrains IntelliJ "New UI" dark carbon palette.
 */
object DarkCarbonTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Dark Carbon"
    override val displayName: String = "Dark Carbon (Default)"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF1E1F22)
    override val surfacePanel: Color = Color(0xFF26282E)
    override val surfaceElevated: Color = Color(0xFF2B2D30)
    override val surfaceGutter: Color = Color(0xFF141416)
    override val surfaceScrollbarTrack: Color = Color(0xFF18181A)
    override val surfaceInput: Color = Color(0xFF1E1F22)
    override val surfaceHover: Color = Color(0xFF2E3138)
    override val surfaceDropdown: Color = Color(0xFF26282E)
    override val surfaceTooltip: Color = Color(0xFF1E1F22)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFFDFE1E5)
    override val textSecondary: Color = Color(0xFFBCBEC4)
    override val textMuted: Color = Color(0xFF8C909E)
    override val textSubtle: Color = Color(0xFF6C707E)
    override val textDimmed: Color = Color(0xFF5A5D6B)
    override val textFaint: Color = Color(0xFF4E5157)
    override val textEditor: Color = Color(0xFFD4D7E1)
    override val textLink: Color = Color(0xFF4FC3F7)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF5A5D6B)
    override val gutterLineSelected: Color = Color(0xFF90CAF9)
    override val gutterLineActiveMatch: Color = Color(0xFF81D4FA)
    override val gutterLineMatch: Color = Color(0xFF4FC3F7)
    override val gutterLineFiltered: Color = Color(0xFFE5C07B)
    override val gutterBorder: Color = Color(0xFF2B2D30)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x332675D9)
    // Raised from 0x28 → 0x55 (33%) so the active match row is a clearly visible beacon,
    // giving the highlight a strong perceptual "margin" around the match needle.
    override val rowActiveMatch: Color = Color(0x554FC3F7)
    // Raised from 0x10 → 0x22 for secondary matches to be readable without drowning the active.
    override val rowMatch: Color = Color(0x224FC3F7)
    override val rowFilteredMatch: Color = Color(0x0C4FC3F7)
    override val searchHighlightActive: Color = Color(0xFFFF9800)
    override val searchHighlightInactive: Color = Color(0x66FFD54F)
    override val searchTextActive: Color = Color(0xFF1E1F22)
    override val searchTextInactive: Color = Color(0xFFFFFFFF)
    override val selectionBackground: Color = Color(0xFF214283)

    // Forensic Severity
    override val severityError: Color = Color(0xFFFF5252)
    override val severityWarn: Color = Color(0xFFFFB74D)
    override val severityInfo: Color = Color(0xFF4FC3F7)
    override val severityDebug: Color = Color(0xFF81C784)
    override val severityTrace: Color = Color(0xFF8C909E)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF2196F3)
    override val progressGradientEnd: Color = Color(0xFF00E5FF)
    override val badgeLinesBackground: Color = Color(0xFF1E3A5F)
    override val badgeLinesText: Color = Color(0xFF64B5F6)
    override val badgeSizeBackground: Color = Color(0xFF1E3F2E)
    override val badgeSizeText: Color = Color(0xFF81C784)
    override val badgeSimdBackground: Color = Color(0xFF381E48)
    override val badgeSimdText: Color = Color(0xFFBA68C8)
    override val badgeDurationBackground: Color = Color(0xFF422E1A)
    override val badgeDurationText: Color = Color(0xFFFFB74D)
    override val badgeErrorBackground: Color = Color(0xFF381E1E)
    override val badgeErrorText: Color = Color(0xFFEF5350)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF232529)
    override val scrubberFillStart: Color = Color(0xFF2196F3)
    override val scrubberFillEnd: Color = Color(0xFF00E5FF)
    override val scrubberHandle: Color = Color(0xFF64B5F6)
    override val scrubberHandleBorder: Color = Color(0xFF90CAF9)
    override val latencyGapAmber: Color = Color(0xFFFFB74D)
    override val latencyGapRed: Color = Color(0xFFFF5252)
    override val temporalFocusBadgeBackground: Color = Color(0xFF1A3A5C)
    override val temporalFocusBadgeText: Color = Color(0xFF4FC3F7)
    override val temporalFocusDimOverlay: Color = Color(0x99000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x334FC3F7)
    override val minimapError: Color = Color(0xFFFF5252)
    override val minimapWarn: Color = Color(0xFFFFB74D)
    override val minimapMatch: Color = Color(0xFFFFD54F)
    override val minimapViewportRect: Color = Color(0x22FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF26282E)
    override val titleBarText: Color = Color(0xFFBCBEC4)
    override val windowControlHoverClose: Color = Color(0xFFE81123)
    override val windowControlHover: Color = Color(0xFF2B2D30)
    override val accentPrimary: Color = Color(0xFF2196F3)
    override val accentSecondary: Color = Color(0xFF00E5FF)
    override val border: Color = Color(0xFF2B2D30)
    override val borderFocused: Color = Color(0xFF3574F0)

    // Menu System
    override val menuBackground: Color = Color(0xFF26282E)
    override val menuBorder: Color = Color(0xFF393B40)
    override val menuItemHover: Color = Color(0xFF2E3138)
    override val menuSeparator: Color = Color(0xFF393B40)
    override val menuShortcutText: Color = Color(0xFF8C909E)
    override val menuIconTint: Color = Color(0xFFBCBEC4)
}
