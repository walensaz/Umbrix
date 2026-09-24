package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * GitHub Light theme for Umbrix Workstation.
 * Modeled after GitHub's Primer Design System and code view.
 * Crisp white surfaces, light grayish-blue frames, and iconic royal blue controls.
 */
object GitHubLightTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "GitHub Light"
    override val displayName: String = "GitHub Light"
    override val isDark: Boolean = false

    // Surfaces
    override val surface: Color = Color(0xFFFFFFFF)
    override val surfacePanel: Color = Color(0xFFF6F8FA)
    override val surfaceElevated: Color = Color(0xFFFFFFFF)
    override val surfaceGutter: Color = Color(0xFFF6F8FA)
    override val surfaceScrollbarTrack: Color = Color(0xFFFFFFFF)
    override val surfaceInput: Color = Color(0xFFFFFFFF)
    override val surfaceHover: Color = Color(0xFFF3F4F6)
    override val surfaceDropdown: Color = Color(0xFFFFFFFF)
    override val surfaceTooltip: Color = Color(0xFF24292F)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFF24292F)
    override val textSecondary: Color = Color(0xFF57606A)
    override val textMuted: Color = Color(0xFF6E7781)
    override val textSubtle: Color = Color(0xFF8C959F)
    override val textDimmed: Color = Color(0xFFAFB8C1)
    override val textFaint: Color = Color(0xFFD0D7DE)
    override val textEditor: Color = Color(0xFF1F2328)
    override val textLink: Color = Color(0xFF0969DA)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF8C959F)
    override val gutterLineSelected: Color = Color(0xFF0969DA)
    override val gutterLineActiveMatch: Color = Color(0xFF0550AE)
    override val gutterLineMatch: Color = Color(0xFF218BFF)
    override val gutterLineFiltered: Color = Color(0xFF9A6700)
    override val gutterBorder: Color = Color(0xFFD0D7DE)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x200969DA)
    override val rowActiveMatch: Color = Color(0x280969DA)
    override val rowMatch: Color = Color(0x100969DA)
    override val rowFilteredMatch: Color = Color(0x0C218BFF)
    override val searchHighlightActive: Color = Color(0xFFFFDF5D)
    override val searchHighlightInactive: Color = Color(0x80FFF8C5)
    override val searchTextActive: Color = Color(0xFF24292F)
    override val searchTextInactive: Color = Color(0xFF24292F)
    override val selectionBackground: Color = Color(0xFFB6E3FF)

    // Forensic Severity
    override val severityError: Color = Color(0xFFCF222E)
    override val severityWarn: Color = Color(0xFF9A6700)
    override val severityInfo: Color = Color(0xFF0969DA)
    override val severityDebug: Color = Color(0xFF1A7F37)
    override val severityTrace: Color = Color(0xFF8250DF)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF0969DA)
    override val progressGradientEnd: Color = Color(0xFF2DA44E)
    override val badgeLinesBackground: Color = Color(0xFFDDF4FF)
    override val badgeLinesText: Color = Color(0xFF0969DA)
    override val badgeSizeBackground: Color = Color(0xFFDAFBE1)
    override val badgeSizeText: Color = Color(0xFF1A7F37)
    override val badgeSimdBackground: Color = Color(0xFFFBEFFF)
    override val badgeSimdText: Color = Color(0xFF8250DF)
    override val badgeDurationBackground: Color = Color(0xFFFFF8C5)
    override val badgeDurationText: Color = Color(0xFF9A6700)
    override val badgeErrorBackground: Color = Color(0xFFFFEBE9)
    override val badgeErrorText: Color = Color(0xFFCF222E)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFFD0D7DE)
    override val scrubberFillStart: Color = Color(0xFF0969DA)
    override val scrubberFillEnd: Color = Color(0xFF2DA44E)
    override val scrubberHandle: Color = Color(0xFF0969DA)
    override val scrubberHandleBorder: Color = Color(0xFF0550AE)
    override val latencyGapAmber: Color = Color(0xFF9A6700)
    override val latencyGapRed: Color = Color(0xFFCF222E)
    override val temporalFocusBadgeBackground: Color = Color(0xFFDDF4FF)
    override val temporalFocusBadgeText: Color = Color(0xFF0969DA)
    override val temporalFocusDimOverlay: Color = Color(0x40000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x330969DA)
    override val minimapError: Color = Color(0xFFCF222E)
    override val minimapWarn: Color = Color(0xFF9A6700)
    override val minimapMatch: Color = Color(0xFFD4A72C)
    override val minimapViewportRect: Color = Color(0x22000000)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFFF6F8FA)
    override val titleBarText: Color = Color(0xFF24292F)
    override val windowControlHoverClose: Color = Color(0xFFCF222E)
    override val windowControlHover: Color = Color(0xFFEAEEF2)
    override val accentPrimary: Color = Color(0xFF0969DA)
    override val accentSecondary: Color = Color(0xFF0550AE)
    override val border: Color = Color(0xFFD0D7DE)
    override val borderFocused: Color = Color(0xFF0969DA)

    // Menu System
    override val menuBackground: Color = Color(0xFFFFFFFF)
    override val menuBorder: Color = Color(0xFFD0D7DE)
    override val menuItemHover: Color = Color(0xFFF6F8FA)
    override val menuSeparator: Color = Color(0xFFD0D7DE)
    override val menuShortcutText: Color = Color(0xFF6E7781)
    override val menuIconTint: Color = Color(0xFF57606A)
}
