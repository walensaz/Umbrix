package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Solarized Light theme for Umbrix Workstation.
 * Modeled after Ethan Schoonover's Solarized Light specification.
 * Warm cream and parchment surfaces engineered to minimize glare and visual fatigue.
 */
object SolarizedLightTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Solarized Light"
    override val displayName: String = "Solarized Light"
    override val isDark: Boolean = false

    // Surfaces
    override val surface: Color = Color(0xFFFDF6E3) // base3
    override val surfacePanel: Color = Color(0xFFEEE8D5) // base2
    override val surfaceElevated: Color = Color(0xFFFFFDF7)
    override val surfaceGutter: Color = Color(0xFFF4EDE0)
    override val surfaceScrollbarTrack: Color = Color(0xFFFDF6E3)
    override val surfaceInput: Color = Color(0xFFFFFDF7)
    override val surfaceHover: Color = Color(0xFFEEE8D5)
    override val surfaceDropdown: Color = Color(0xFFFFFDF7)
    override val surfaceTooltip: Color = Color(0xFF073642)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFF657B83) // base00
    override val textSecondary: Color = Color(0xFF586E75) // base01
    override val textMuted: Color = Color(0xFF93A1A1) // base1
    override val textSubtle: Color = Color(0xFF839496) // base0
    override val textDimmed: Color = Color(0xFFA5B3B3)
    override val textFaint: Color = Color(0xFFD33682)
    override val textEditor: Color = Color(0xFF586E75)
    override val textLink: Color = Color(0xFF268BD2)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF93A1A1)
    override val gutterLineSelected: Color = Color(0xFF268BD2)
    override val gutterLineActiveMatch: Color = Color(0xFF2AA198)
    override val gutterLineMatch: Color = Color(0xFF6C71C4)
    override val gutterLineFiltered: Color = Color(0xFFB58900)
    override val gutterBorder: Color = Color(0xFFEEE8D5)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x20EEE8D5)
    override val rowActiveMatch: Color = Color(0x28268BD2)
    override val rowMatch: Color = Color(0x10268BD2)
    override val rowFilteredMatch: Color = Color(0x0C2AA198)
    override val searchHighlightActive: Color = Color(0xFFFDF0A6)
    override val searchHighlightInactive: Color = Color(0x80FFF9D2)
    override val searchTextActive: Color = Color(0xFF073642)
    override val searchTextInactive: Color = Color(0xFF586E75)
    override val selectionBackground: Color = Color(0xFFE4DFCC)

    // Forensic Severity
    override val severityError: Color = Color(0xFFDC322F)
    override val severityWarn: Color = Color(0xFFB58900)
    override val severityInfo: Color = Color(0xFF268BD2)
    override val severityDebug: Color = Color(0xFF859900)
    override val severityTrace: Color = Color(0xFF6C71C4)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF268BD2)
    override val progressGradientEnd: Color = Color(0xFF2AA198)
    override val badgeLinesBackground: Color = Color(0xFFE4EEF2)
    override val badgeLinesText: Color = Color(0xFF268BD2)
    override val badgeSizeBackground: Color = Color(0xFFE8EED8)
    override val badgeSizeText: Color = Color(0xFF859900)
    override val badgeSimdBackground: Color = Color(0xFFEAE4F0)
    override val badgeSimdText: Color = Color(0xFF6C71C4)
    override val badgeDurationBackground: Color = Color(0xFFEFEAC8)
    override val badgeDurationText: Color = Color(0xFFB58900)
    override val badgeErrorBackground: Color = Color(0xFFF6DEE0)
    override val badgeErrorText: Color = Color(0xFFDC322F)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFFEEE8D5)
    override val scrubberFillStart: Color = Color(0xFF268BD2)
    override val scrubberFillEnd: Color = Color(0xFF2AA198)
    override val scrubberHandle: Color = Color(0xFF268BD2)
    override val scrubberHandleBorder: Color = Color(0xFF073642)
    override val latencyGapAmber: Color = Color(0xFFB58900)
    override val latencyGapRed: Color = Color(0xFFDC322F)
    override val temporalFocusBadgeBackground: Color = Color(0xFFE4EEF2)
    override val temporalFocusBadgeText: Color = Color(0xFF268BD2)
    override val temporalFocusDimOverlay: Color = Color(0x40000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x33268BD2)
    override val minimapError: Color = Color(0xFFDC322F)
    override val minimapWarn: Color = Color(0xFFB58900)
    override val minimapMatch: Color = Color(0xFFB58900)
    override val minimapViewportRect: Color = Color(0x22000000)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFFEEE8D5)
    override val titleBarText: Color = Color(0xFF657B83)
    override val windowControlHoverClose: Color = Color(0xFFDC322F)
    override val windowControlHover: Color = Color(0xFFE0D9C4)
    override val accentPrimary: Color = Color(0xFF268BD2)
    override val accentSecondary: Color = Color(0xFF2AA198)
    override val border: Color = Color(0xFFDDD6C1)
    override val borderFocused: Color = Color(0xFF268BD2)

    // Menu System
    override val menuBackground: Color = Color(0xFFFFFDF7)
    override val menuBorder: Color = Color(0xFFDDD6C1)
    override val menuItemHover: Color = Color(0xFFEEE8D5)
    override val menuSeparator: Color = Color(0xFFDDD6C1)
    override val menuShortcutText: Color = Color(0xFF93A1A1)
    override val menuIconTint: Color = Color(0xFF586E75)
}
