package umbrix.ui.theme.themes

import androidx.compose.ui.graphics.Color
import umbrix.ui.theme.UmbrixThemeSpec

/**
 * Dracula theme for Umbrix Workstation.
 * Modeled after the official Dracula specification by Zeno Rocha.
 * Refined purple-tinted dark surfaces with pastel neon accents.
 */
object DraculaTheme : UmbrixThemeSpec {

    // Identity
    override val name: String = "Dracula"
    override val displayName: String = "Dracula"
    override val isDark: Boolean = true

    // Surfaces
    override val surface: Color = Color(0xFF282A36)
    override val surfacePanel: Color = Color(0xFF2D2F3D)
    override val surfaceElevated: Color = Color(0xFF44475A)
    override val surfaceGutter: Color = Color(0xFF21222C)
    override val surfaceScrollbarTrack: Color = Color(0xFF1E1F29)
    override val surfaceInput: Color = Color(0xFF21222C)
    override val surfaceHover: Color = Color(0xFF44475A)
    override val surfaceDropdown: Color = Color(0xFF21222C)
    override val surfaceTooltip: Color = Color(0xFF282A36)

    // Text Hierarchy
    override val textPrimary: Color = Color(0xFFF8F8F2)
    override val textSecondary: Color = Color(0xFFE2E2DC)
    override val textMuted: Color = Color(0xFF6272A4)
    override val textSubtle: Color = Color(0xFF4E5A80)
    override val textDimmed: Color = Color(0xFF44475A)
    override val textFaint: Color = Color(0xFF343746)
    override val textEditor: Color = Color(0xFFF8F8F2)
    override val textLink: Color = Color(0xFF8BE9FD)

    // Gutter Line Numbers
    override val gutterLineNormal: Color = Color(0xFF6272A4)
    override val gutterLineSelected: Color = Color(0xFFBD93F9)
    override val gutterLineActiveMatch: Color = Color(0xFF8BE9FD)
    override val gutterLineMatch: Color = Color(0xFFFF79C6)
    override val gutterLineFiltered: Color = Color(0xFFF1FA8C)
    override val gutterBorder: Color = Color(0xFF44475A)

    // Row Selection & Search
    override val rowSelected: Color = Color(0x3344475A)
    override val rowActiveMatch: Color = Color(0x288BE9FD)
    override val rowMatch: Color = Color(0x108BE9FD)
    override val rowFilteredMatch: Color = Color(0x0CFF79C6)
    override val searchHighlightActive: Color = Color(0xFFFFB86C)
    override val searchHighlightInactive: Color = Color(0x66F1FA8C)
    override val searchTextActive: Color = Color(0xFF282A36)
    override val searchTextInactive: Color = Color(0xFFF8F8F2)
    override val selectionBackground: Color = Color(0xFF44475A)

    // Forensic Severity
    override val severityError: Color = Color(0xFFFF5555)
    override val severityWarn: Color = Color(0xFFF1FA8C)
    override val severityInfo: Color = Color(0xFF8BE9FD)
    override val severityDebug: Color = Color(0xFF50FA7B)
    override val severityTrace: Color = Color(0xFFFF79C6)

    // Progress & Badges
    override val progressGradientStart: Color = Color(0xFFBD93F9)
    override val progressGradientEnd: Color = Color(0xFFFF79C6)
    override val badgeLinesBackground: Color = Color(0xFF303850)
    override val badgeLinesText: Color = Color(0xFF8BE9FD)
    override val badgeSizeBackground: Color = Color(0xFF243C2E)
    override val badgeSizeText: Color = Color(0xFF50FA7B)
    override val badgeSimdBackground: Color = Color(0xFF3C2848)
    override val badgeSimdText: Color = Color(0xFFBD93F9)
    override val badgeDurationBackground: Color = Color(0xFF423E20)
    override val badgeDurationText: Color = Color(0xFFF1FA8C)
    override val badgeErrorBackground: Color = Color(0xFF422024)
    override val badgeErrorText: Color = Color(0xFFFF5555)

    // Temporal & Latency
    override val scrubberTrack: Color = Color(0xFF21222C)
    override val scrubberFillStart: Color = Color(0xFFBD93F9)
    override val scrubberFillEnd: Color = Color(0xFFFF79C6)
    override val scrubberHandle: Color = Color(0xFF8BE9FD)
    override val scrubberHandleBorder: Color = Color(0xFFBD93F9)
    override val latencyGapAmber: Color = Color(0xFFF1FA8C)
    override val latencyGapRed: Color = Color(0xFFFF5555)
    override val temporalFocusBadgeBackground: Color = Color(0xFF3C2848)
    override val temporalFocusBadgeText: Color = Color(0xFFBD93F9)
    override val temporalFocusDimOverlay: Color = Color(0x99000000)

    // GPU Seismograph Minimap
    override val minimapDensity: Color = Color(0x338BE9FD)
    override val minimapError: Color = Color(0xFFFF5555)
    override val minimapWarn: Color = Color(0xFFF1FA8C)
    override val minimapMatch: Color = Color(0xFFFFB86C)
    override val minimapViewportRect: Color = Color(0x22FFFFFF)

    // Window Chrome & Accents
    override val titleBarBackground: Color = Color(0xFF21222C)
    override val titleBarText: Color = Color(0xFFF8F8F2)
    override val windowControlHoverClose: Color = Color(0xFFFF5555)
    override val windowControlHover: Color = Color(0xFF44475A)
    override val accentPrimary: Color = Color(0xFFBD93F9)
    override val accentSecondary: Color = Color(0xFFFF79C6)
    override val border: Color = Color(0xFF44475A)
    override val borderFocused: Color = Color(0xFFBD93F9)

    // Menu System
    override val menuBackground: Color = Color(0xFF21222C)
    override val menuBorder: Color = Color(0xFF44475A)
    override val menuItemHover: Color = Color(0xFF44475A)
    override val menuSeparator: Color = Color(0xFF44475A)
    override val menuShortcutText: Color = Color(0xFF6272A4)
    override val menuIconTint: Color = Color(0xFFE2E2DC)
}
