package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Light Ivory theme for Umbrix Workstation.
 * Inspired by IntelliJ Light and JetBrains Fleet Light.
 * Soft off-white ivory panels reducing glare with crisp slate typography.
 */
object LightIvoryTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Light Ivory"
    override val displayName: String = "Light Ivory"
    override val isDark: Boolean = false

    // Surfaces
    override val surface: Color = Color(0xFFFAFAFA)
    override val surfacePanel: Color = Color(0xFFF0F0F0)
    override val surfaceElevated: Color = Color(0xFFFFFFFF)
    override val surfaceGutter: Color = Color(0xFFF2F2F2)
    override val surfaceScrollbarTrack: Color = Color(0xFFFAFAFA)
    override val surfaceInput: Color = Color(0xFFFFFFFF)
    override val surfaceHover: Color = Color(0xFFEAEAEA)
    override val surfaceDropdown: Color = Color(0xFFFFFFFF)
    override val surfaceTooltip: Color = Color(0xFF323232)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFF1E1E1E)
    override val textSecondary: Color = Color(0xFF4A4A4A)
    override val textMuted: Color = Color(0xFF808080)
    override val textSubtle: Color = Color(0xFF9E9E9E)
    override val textDimmed: Color = Color(0xFFA0A0A0)
    override val textFaint: Color = Color(0xFFC0C0C0)
    override val textEditor: Color = Color(0xFF2D2D2D)
    override val textLink: Color = Color(0xFF0288D1)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF808080)
    override val gutterLineSelected: Color = Color(0xFF1976D2)
    override val gutterLineActiveMatch: Color = Color(0xFF0288D1)
    override val gutterLineMatch: Color = Color(0xFF0097A7)
    override val gutterLineFiltered: Color = Color(0xFFED6C02)
    override val gutterBorder: Color = Color(0xFFE0E0E0)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x202196F3)
    override val rowActiveMatch: Color = Color(0x280288D1)
    override val rowMatch: Color = Color(0x100288D1)
    override val rowFilteredMatch: Color = Color(0x0C00BCD4)
    override val searchHighlightActive: Color = Color(0xFFFFE082)
    override val searchHighlightInactive: Color = Color(0x80FFF59D)
    override val searchTextActive: Color = Color(0xFF1E1E1E)
    override val searchTextInactive: Color = Color(0xFF1E1E1E)
    override val selectionBackground: Color = Color(0xFFB3D7FF)

    // Forensic Severity
    override val severityError: Color = Color(0xFFD32F2F)
    override val severityWarn: Color = Color(0xFFED6C02)
    override val severityInfo: Color = Color(0xFF0288D1)
    override val severityDebug: Color = Color(0xFF2E7D32)
    override val severityTrace: Color = Color(0xFF7B1FA2)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF2196F3)
    override val progressGradientEnd: Color = Color(0xFF00BCD4)
    override val badgeLinesBackground: Color = Color(0xFFE3F2FD)
    override val badgeLinesText: Color = Color(0xFF1565C0)
    override val badgeSizeBackground: Color = Color(0xFFE8F5E9)
    override val badgeSizeText: Color = Color(0xFF2E7D32)
    override val badgeSimdBackground: Color = Color(0xFFF3E5F5)
    override val badgeSimdText: Color = Color(0xFF7B1FA2)
    override val badgeDurationBackground: Color = Color(0xFFFFF3E0)
    override val badgeDurationText: Color = Color(0xFFE65100)
    override val badgeErrorBackground: Color = Color(0xFFFFEBEE)
    override val badgeErrorText: Color = Color(0xFFC62828)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFFE0E0E0)
    override val scrubberFillStart: Color = Color(0xFF2196F3)
    override val scrubberFillEnd: Color = Color(0xFF00BCD4)
    override val scrubberHandle: Color = Color(0xFF1976D2)
    override val scrubberHandleBorder: Color = Color(0xFF0D47A1)
    override val latencyGapAmber: Color = Color(0xFFED6C02)
    override val latencyGapRed: Color = Color(0xFFD32F2F)
    override val temporalFocusBadgeBackground: Color = Color(0xFFE3F2FD)
    override val temporalFocusBadgeText: Color = Color(0xFF1565C0)
    override val temporalFocusDimOverlay: Color = Color(0x40000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x330288D1)
    override val minimapError: Color = Color(0xFFD32F2F)
    override val minimapWarn: Color = Color(0xFFED6C02)
    override val minimapMatch: Color = Color(0xFFFFA000)
    override val minimapViewportRect: Color = Color(0x22000000)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFFF0F0F0)
    override val titleBarText: Color = Color(0xFF1E1E1E)
    override val windowControlHoverClose: Color = Color(0xFFE53935)
    override val windowControlHover: Color = Color(0xFFE0E0E0)
    override val accentPrimary: Color = Color(0xFF2196F3)
    override val accentSecondary: Color = Color(0xFF00BCD4)
    override val border: Color = Color(0xFFD0D0D0)
    override val borderFocused: Color = Color(0xFF2196F3)

    // Menu System
    override val menuBackground: Color = Color(0xFFFFFFFF)
    override val menuBorder: Color = Color(0xFFD0D0D0)
    override val menuItemHover: Color = Color(0xFFF0F0F0)
    override val menuSeparator: Color = Color(0xFFE0E0E0)
    override val menuShortcutText: Color = Color(0xFF808080)
    override val menuIconTint: Color = Color(0xFF4A4A4A)
}
