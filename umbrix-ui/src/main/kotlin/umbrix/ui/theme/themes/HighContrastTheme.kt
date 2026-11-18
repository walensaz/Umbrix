package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * High Contrast theme for Umbrix Workstation.
 * Modeled after WCAG 2.1 AAA accessibility standards and Windows High Contrast Black.
 * Pure black canvas with pure white text (>21:1 contrast) and fully saturated neon signals.
 */
object HighContrastTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "High Contrast"
    override val displayName: String = "High Contrast (AAA)"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF000000)
    override val surfacePanel: Color = Color(0xFF1A1A1A)
    override val surfaceElevated: Color = Color(0xFF262626)
    override val surfaceGutter: Color = Color(0xFF000000)
    override val surfaceScrollbarTrack: Color = Color(0xFF000000)
    override val surfaceInput: Color = Color(0xFF000000)
    override val surfaceHover: Color = Color(0xFF262626)
    override val surfaceDropdown: Color = Color(0xFF1A1A1A)
    override val surfaceTooltip: Color = Color(0xFF000000)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFFFFFFFF)
    override val textSecondary: Color = Color(0xFFE0E0E0)
    override val textMuted: Color = Color(0xFFD4D4D4)
    override val textSubtle: Color = Color(0xFFBFBFBF)
    override val textDimmed: Color = Color(0xFFA6A6A6)
    override val textFaint: Color = Color(0xFF8C8C8C)
    override val textEditor: Color = Color(0xFFFFFFFF)
    override val textLink: Color = Color(0xFF00FFFF)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFFA6A6A6)
    override val gutterLineSelected: Color = Color(0xFF00FFFF)
    override val gutterLineActiveMatch: Color = Color(0xFFFFFF00)
    override val gutterLineMatch: Color = Color(0xFF00FF00)
    override val gutterLineFiltered: Color = Color(0xFFFF00FF)
    override val gutterBorder: Color = Color(0xFFFFFFFF)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x4000FFFF)
    override val rowActiveMatch: Color = Color(0x33FFFF00)
    override val rowMatch: Color = Color(0x20FFFF00)
    override val rowFilteredMatch: Color = Color(0x20FF00FF)
    override val searchHighlightActive: Color = Color(0xFFFFFF00)
    override val searchHighlightInactive: Color = Color(0x66FFFF00)
    override val searchTextActive: Color = Color(0xFF000000)
    override val searchTextInactive: Color = Color(0xFFFFFFFF)
    override val selectionBackground: Color = Color(0xFF003366)

    // Forensic Severity
    override val severityError: Color = Color(0xFFFF0000)
    override val severityWarn: Color = Color(0xFFFFD700)
    override val severityInfo: Color = Color(0xFF00FFFF)
    override val severityDebug: Color = Color(0xFF00FF00)
    override val severityTrace: Color = Color(0xFFFF00FF)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF00FFFF)
    override val progressGradientEnd: Color = Color(0xFFFFFF00)
    override val badgeLinesBackground: Color = Color(0xFF002244)
    override val badgeLinesText: Color = Color(0xFF00FFFF)
    override val badgeSizeBackground: Color = Color(0xFF003300)
    override val badgeSizeText: Color = Color(0xFF00FF00)
    override val badgeSimdBackground: Color = Color(0xFF330033)
    override val badgeSimdText: Color = Color(0xFFFF00FF)
    override val badgeDurationBackground: Color = Color(0xFF333300)
    override val badgeDurationText: Color = Color(0xFFFFFF00)
    override val badgeErrorBackground: Color = Color(0xFF440000)
    override val badgeErrorText: Color = Color(0xFFFF0000)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF333333)
    override val scrubberFillStart: Color = Color(0xFF00FFFF)
    override val scrubberFillEnd: Color = Color(0xFFFFFF00)
    override val scrubberHandle: Color = Color(0xFFFFFF00)
    override val scrubberHandleBorder: Color = Color(0xFFFFFFFF)
    override val latencyGapAmber: Color = Color(0xFFFFD700)
    override val latencyGapRed: Color = Color(0xFFFF0000)
    override val temporalFocusBadgeBackground: Color = Color(0xFF002244)
    override val temporalFocusBadgeText: Color = Color(0xFF00FFFF)
    override val temporalFocusDimOverlay: Color = Color(0xCC000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x4400FFFF)
    override val minimapError: Color = Color(0xFFFF0000)
    override val minimapWarn: Color = Color(0xFFFFD700)
    override val minimapMatch: Color = Color(0xFFFFFF00)
    override val minimapViewportRect: Color = Color(0x44FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF000000)
    override val titleBarText: Color = Color(0xFFFFFFFF)
    override val windowControlHoverClose: Color = Color(0xFFFF0000)
    override val windowControlHover: Color = Color(0xFF333333)
    override val accentPrimary: Color = Color(0xFF00FFFF)
    override val accentSecondary: Color = Color(0xFFFFFF00)
    override val border: Color = Color(0xFFFFFFFF)
    override val borderFocused: Color = Color(0xFF00FFFF)

    // Menu System
    override val menuBackground: Color = Color(0xFF000000)
    override val menuBorder: Color = Color(0xFFFFFFFF)
    override val menuItemHover: Color = Color(0xFF262626)
    override val menuSeparator: Color = Color(0xFFFFFFFF)
    override val menuShortcutText: Color = Color(0xFFFFFF00)
    override val menuIconTint: Color = Color(0xFF00FFFF)
}
