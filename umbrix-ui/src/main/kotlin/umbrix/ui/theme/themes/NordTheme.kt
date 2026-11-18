package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Nord theme for Umbrix Workstation.
 * Modeled after the Arctic Ice Studio Nord color system.
 * Polar Night dark bases, Snow Storm typography, and icy Frost cyan/blue accents.
 */
object NordTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Nord"
    override val displayName: String = "Nord"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF2E3440) // nord0
    override val surfacePanel: Color = Color(0xFF3B4252) // nord1
    override val surfaceElevated: Color = Color(0xFF434C5E) // nord2
    override val surfaceGutter: Color = Color(0xFF242933)
    override val surfaceScrollbarTrack: Color = Color(0xFF2E3440)
    override val surfaceInput: Color = Color(0xFF242933)
    override val surfaceHover: Color = Color(0xFF434C5E)
    override val surfaceDropdown: Color = Color(0xFF3B4252)
    override val surfaceTooltip: Color = Color(0xFF2E3440)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFFECEFF4) // nord6
    override val textSecondary: Color = Color(0xFFE5E9F0) // nord5
    override val textMuted: Color = Color(0xFFD8DEE9) // nord4
    override val textSubtle: Color = Color(0xFF4C566A) // nord3
    override val textDimmed: Color = Color(0xFF434C5E) // nord2
    override val textFaint: Color = Color(0xFF3B4252) // nord1
    override val textEditor: Color = Color(0xFFE5E9F0)
    override val textLink: Color = Color(0xFF88C0D0) // nord8

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF4C566A)
    override val gutterLineSelected: Color = Color(0xFF88C0D0)
    override val gutterLineActiveMatch: Color = Color(0xFF81A1C1)
    override val gutterLineMatch: Color = Color(0xFF5E81AC)
    override val gutterLineFiltered: Color = Color(0xFFEBCB8B)
    override val gutterBorder: Color = Color(0xFF3B4252)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x33434C5E)
    override val rowActiveMatch: Color = Color(0x2888C0D0)
    override val rowMatch: Color = Color(0x1088C0D0)
    override val rowFilteredMatch: Color = Color(0x0C81A1C1)
    override val searchHighlightActive: Color = Color(0xFFEBCB8B)
    override val searchHighlightInactive: Color = Color(0x66EBCB8B)
    override val searchTextActive: Color = Color(0xFF2E3440)
    override val searchTextInactive: Color = Color(0xFFECEFF4)
    override val selectionBackground: Color = Color(0xFF434C5E)

    // Forensic Severity
    override val severityError: Color = Color(0xFFBF616A) // nord11
    override val severityWarn: Color = Color(0xFFEBCB8B) // nord13
    override val severityInfo: Color = Color(0xFF88C0D0) // nord8
    override val severityDebug: Color = Color(0xFFA3BE8C) // nord14
    override val severityTrace: Color = Color(0xFFB48EAD) // nord15

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF88C0D0)
    override val progressGradientEnd: Color = Color(0xFF81A1C1)
    override val badgeLinesBackground: Color = Color(0xFF2A3844)
    override val badgeLinesText: Color = Color(0xFF88C0D0)
    override val badgeSizeBackground: Color = Color(0xFF283C30)
    override val badgeSizeText: Color = Color(0xFFA3BE8C)
    override val badgeSimdBackground: Color = Color(0xFF3C2E3C)
    override val badgeSimdText: Color = Color(0xFFB48EAD)
    override val badgeDurationBackground: Color = Color(0xFF403828)
    override val badgeDurationText: Color = Color(0xFFEBCB8B)
    override val badgeErrorBackground: Color = Color(0xFF402428)
    override val badgeErrorText: Color = Color(0xFFBF616A)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF242933)
    override val scrubberFillStart: Color = Color(0xFF88C0D0)
    override val scrubberFillEnd: Color = Color(0xFF81A1C1)
    override val scrubberHandle: Color = Color(0xFF88C0D0)
    override val scrubberHandleBorder: Color = Color(0xFFECEFF4)
    override val latencyGapAmber: Color = Color(0xFFEBCB8B)
    override val latencyGapRed: Color = Color(0xFFBF616A)
    override val temporalFocusBadgeBackground: Color = Color(0xFF2A3844)
    override val temporalFocusBadgeText: Color = Color(0xFF88C0D0)
    override val temporalFocusDimOverlay: Color = Color(0x99000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x3388C0D0)
    override val minimapError: Color = Color(0xFFBF616A)
    override val minimapWarn: Color = Color(0xFFEBCB8B)
    override val minimapMatch: Color = Color(0xFFEBCB8B)
    override val minimapViewportRect: Color = Color(0x22FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF242933)
    override val titleBarText: Color = Color(0xFFECEFF4)
    override val windowControlHoverClose: Color = Color(0xFFBF616A)
    override val windowControlHover: Color = Color(0xFF434C5E)
    override val accentPrimary: Color = Color(0xFF88C0D0)
    override val accentSecondary: Color = Color(0xFF81A1C1)
    override val border: Color = Color(0xFF3B4252)
    override val borderFocused: Color = Color(0xFF88C0D0)

    // Menu System
    override val menuBackground: Color = Color(0xFF2E3440)
    override val menuBorder: Color = Color(0xFF3B4252)
    override val menuItemHover: Color = Color(0xFF434C5E)
    override val menuSeparator: Color = Color(0xFF3B4252)
    override val menuShortcutText: Color = Color(0xFF4C566A)
    override val menuIconTint: Color = Color(0xFFD8DEE9)
}
