package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Midnight Blue theme for Umbrix Workstation.
 * Inspired by GitHub Dark, VS Code Dark+, and Zed Andromeda.
 * Deep maritime navy tones with electric blue and cornflower accents.
 */
object MidnightBlueTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Midnight Blue"
    override val displayName: String = "Midnight Blue"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF0D1117)
    override val surfacePanel: Color = Color(0xFF161B22)
    override val surfaceElevated: Color = Color(0xFF21262D)
    override val surfaceGutter: Color = Color(0xFF090D13)
    override val surfaceScrollbarTrack: Color = Color(0xFF0D1117)
    override val surfaceInput: Color = Color(0xFF0D1117)
    override val surfaceHover: Color = Color(0xFF21262D)
    override val surfaceDropdown: Color = Color(0xFF161B22)
    override val surfaceTooltip: Color = Color(0xFF161B22)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFFC9D1D9)
    override val textSecondary: Color = Color(0xFF8B949E)
    override val textMuted: Color = Color(0xFF6E7681)
    override val textSubtle: Color = Color(0xFF484F58)
    override val textDimmed: Color = Color(0xFF30363D)
    override val textFaint: Color = Color(0xFF21262D)
    override val textEditor: Color = Color(0xFFE6EDF3)
    override val textLink: Color = Color(0xFF58A6FF)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF484F58)
    override val gutterLineSelected: Color = Color(0xFF58A6FF)
    override val gutterLineActiveMatch: Color = Color(0xFF79C0FF)
    override val gutterLineMatch: Color = Color(0xFF388BFD)
    override val gutterLineFiltered: Color = Color(0xFFD29922)
    override val gutterBorder: Color = Color(0xFF21262D)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x33388BFD)
    override val rowActiveMatch: Color = Color(0x2858A6FF)
    override val rowMatch: Color = Color(0x1058A6FF)
    override val rowFilteredMatch: Color = Color(0x0C58A6FF)
    override val searchHighlightActive: Color = Color(0xFFF2CC60)
    override val searchHighlightInactive: Color = Color(0x66BB8009)
    override val searchTextActive: Color = Color(0xFF0D1117)
    override val searchTextInactive: Color = Color(0xFFFFFFFF)
    override val selectionBackground: Color = Color(0xFF1F4273)

    // Forensic Severity
    override val severityError: Color = Color(0xFFF85149)
    override val severityWarn: Color = Color(0xFFD29922)
    override val severityInfo: Color = Color(0xFF58A6FF)
    override val severityDebug: Color = Color(0xFF3FB950)
    override val severityTrace: Color = Color(0xFFBC8CFF)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFF1F6FEB)
    override val progressGradientEnd: Color = Color(0xFF58A6FF)
    override val badgeLinesBackground: Color = Color(0xFF112240)
    override val badgeLinesText: Color = Color(0xFF58A6FF)
    override val badgeSizeBackground: Color = Color(0xFF122C1A)
    override val badgeSizeText: Color = Color(0xFF3FB950)
    override val badgeSimdBackground: Color = Color(0xFF281A3C)
    override val badgeSimdText: Color = Color(0xFFBC8CFF)
    override val badgeDurationBackground: Color = Color(0xFF342410)
    override val badgeDurationText: Color = Color(0xFFD29922)
    override val badgeErrorBackground: Color = Color(0xFF3C1A1A)
    override val badgeErrorText: Color = Color(0xFFF85149)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF161B22)
    override val scrubberFillStart: Color = Color(0xFF1F6FEB)
    override val scrubberFillEnd: Color = Color(0xFF58A6FF)
    override val scrubberHandle: Color = Color(0xFF58A6FF)
    override val scrubberHandleBorder: Color = Color(0xFF79C0FF)
    override val latencyGapAmber: Color = Color(0xFFD29922)
    override val latencyGapRed: Color = Color(0xFFF85149)
    override val temporalFocusBadgeBackground: Color = Color(0xFF1F385C)
    override val temporalFocusBadgeText: Color = Color(0xFF58A6FF)
    override val temporalFocusDimOverlay: Color = Color(0x99000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x3358A6FF)
    override val minimapError: Color = Color(0xFFF85149)
    override val minimapWarn: Color = Color(0xFFD29922)
    override val minimapMatch: Color = Color(0xFFF2CC60)
    override val minimapViewportRect: Color = Color(0x22FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF161B22)
    override val titleBarText: Color = Color(0xFFC9D1D9)
    override val windowControlHoverClose: Color = Color(0xFFDA3633)
    override val windowControlHover: Color = Color(0xFF21262D)
    override val accentPrimary: Color = Color(0xFF58A6FF)
    override val accentSecondary: Color = Color(0xFF1F6FEB)
    override val border: Color = Color(0xFF30363D)
    override val borderFocused: Color = Color(0xFF58A6FF)

    // Menu System
    override val menuBackground: Color = Color(0xFF161B22)
    override val menuBorder: Color = Color(0xFF30363D)
    override val menuItemHover: Color = Color(0xFF21262D)
    override val menuSeparator: Color = Color(0xFF30363D)
    override val menuShortcutText: Color = Color(0xFF6E7681)
    override val menuIconTint: Color = Color(0xFF8B949E)
}
