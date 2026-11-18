package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Solarized Dark theme for Umbrix Workstation.
 * Modeled after Ethan Schoonover's Solarized color space.
 * Calibrated in CIELAB color science with dark teal surfaces and uniform accent luminance.
 */
object SolarizedDarkTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Solarized Dark"
    override val displayName: String = "Solarized Dark"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF002B36) // base03
    override val surfacePanel: Color = Color(0xFF073642) // base02
    override val surfaceElevated: Color = Color(0xFF0A4250)
    override val surfaceGutter: Color = Color(0xFF00212B)
    override val surfaceScrollbarTrack: Color = Color(0xFF002B36)
    override val surfaceInput: Color = Color(0xFF00212B)
    override val surfaceHover: Color = Color(0xFF073642)
    override val surfaceDropdown: Color = Color(0xFF073642)
    override val surfaceTooltip: Color = Color(0xFF002B36)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFF839496) // base0
    override val textSecondary: Color = Color(0xFF93A1A1) // base1
    override val textMuted: Color = Color(0xFF586E75) // base01
    override val textSubtle: Color = Color(0xFF073642) // base02
    override val textDimmed: Color = Color(0xFF073642)
    override val textFaint: Color = Color(0xFF00212B)
    override val textEditor: Color = Color(0xFF93A1A1)
    override val textLink: Color = Color(0xFF268BD2) // blue

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF586E75)
    override val gutterLineSelected: Color = Color(0xFF268BD2)
    override val gutterLineActiveMatch: Color = Color(0xFF2AA198)
    override val gutterLineMatch: Color = Color(0xFF6C71C4)
    override val gutterLineFiltered: Color = Color(0xFFB58900)
    override val gutterBorder: Color = Color(0xFF073642)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x33073642)
    override val rowActiveMatch: Color = Color(0x28268BD2)
    override val rowMatch: Color = Color(0x10268BD2)
    override val rowFilteredMatch: Color = Color(0x0C2AA198)
    override val searchHighlightActive: Color = Color(0xFFB58900)
    override val searchHighlightInactive: Color = Color(0x66B58900)
    override val searchTextActive: Color = Color(0xFF002B36)
    override val searchTextInactive: Color = Color(0xFFFDF6E3)
    override val selectionBackground: Color = Color(0xFF073642)

    // Forensic Severity
    override val severityError: Color = Color(0xFFDC322F) // red
    override val severityWarn: Color = Color(0xFFB58900) // yellow
    override val severityInfo: Color = Color(0xFF268BD2) // blue
    override val severityDebug: Color = Color(0xFF859900) // green
    override val severityTrace: Color = Color(0xFF6C71C4) // violet

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF268BD2)
    override val progressGradientEnd: Color = Color(0xFF2AA198)
    override val badgeLinesBackground: Color = Color(0xFF0A3C4A)
    override val badgeLinesText: Color = Color(0xFF2AA198)
    override val badgeSizeBackground: Color = Color(0xFF143820)
    override val badgeSizeText: Color = Color(0xFF859900)
    override val badgeSimdBackground: Color = Color(0xFF202844)
    override val badgeSimdText: Color = Color(0xFF6C71C4)
    override val badgeDurationBackground: Color = Color(0xFF342E10)
    override val badgeDurationText: Color = Color(0xFFB58900)
    override val badgeErrorBackground: Color = Color(0xFF3C1A1E)
    override val badgeErrorText: Color = Color(0xFFDC322F)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF00212B)
    override val scrubberFillStart: Color = Color(0xFF268BD2)
    override val scrubberFillEnd: Color = Color(0xFF2AA198)
    override val scrubberHandle: Color = Color(0xFF268BD2)
    override val scrubberHandleBorder: Color = Color(0xFF839496)
    override val latencyGapAmber: Color = Color(0xFFB58900)
    override val latencyGapRed: Color = Color(0xFFDC322F)
    override val temporalFocusBadgeBackground: Color = Color(0xFF0A3C4A)
    override val temporalFocusBadgeText: Color = Color(0xFF268BD2)
    override val temporalFocusDimOverlay: Color = Color(0x99000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x33268BD2)
    override val minimapError: Color = Color(0xFFDC322F)
    override val minimapWarn: Color = Color(0xFFB58900)
    override val minimapMatch: Color = Color(0xFFB58900)
    override val minimapViewportRect: Color = Color(0x22FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF00212B)
    override val titleBarText: Color = Color(0xFF839496)
    override val windowControlHoverClose: Color = Color(0xFFDC322F)
    override val windowControlHover: Color = Color(0xFF073642)
    override val accentPrimary: Color = Color(0xFF268BD2)
    override val accentSecondary: Color = Color(0xFF2AA198)
    override val border: Color = Color(0xFF073642)
    override val borderFocused: Color = Color(0xFF268BD2)

    // Menu System
    override val menuBackground: Color = Color(0xFF002B36)
    override val menuBorder: Color = Color(0xFF073642)
    override val menuItemHover: Color = Color(0xFF073642)
    override val menuSeparator: Color = Color(0xFF073642)
    override val menuShortcutText: Color = Color(0xFF586E75)
    override val menuIconTint: Color = Color(0xFF93A1A1)
}
