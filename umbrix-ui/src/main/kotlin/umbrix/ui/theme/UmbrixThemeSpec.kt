package umbrix.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Contract specifying all semantic color tokens required by the Umbrix Workstation UI.
 *
 * Implementations provide concrete palettes for dark, light, and high-contrast environments.
 * Jewel controls inherit base styling via [isDark], while custom Skia canvas renderers
 * and Compose workstation components consume these tokens directly.
 */
@Immutable
interface UmbrixThemeSpec {

    // =========================================================================
    // 1. Identity & Jewel Driver (3 tokens)
    // =========================================================================

    /** Machine-readable unique identifier (e.g., "dark-carbon", "dracula", "github-light"). */
    val name: String

    /** Human-readable display label rendered in Settings and Dropdowns. */
    val displayName: String

    /** Determines whether JetBrains Jewel renders in dark or light IntUi mode. */
    val isDark: Boolean

    // =========================================================================
    // 2. Surfaces & Backgrounds (9 tokens)
    // =========================================================================

    /** Main application window canvas background. */
    val surface: Color

    /** Top control bar, search toolbar, and panel container fill. */
    val surfacePanel: Color

    /** Active tabs, floating cards, dialog bodies, and elevated popups. */
    val surfaceElevated: Color

    /** Gutter background, vertical separator lanes, and bottom status bar. */
    val surfaceGutter: Color

    /** Background channel track for vertical 64-bit scrollbars. */
    val surfaceScrollbarTrack: Color

    /** Input text field background fill in resting state. */
    val surfaceInput: Color

    /** Subtle hover highlight for clickable surface tiles and rows. */
    val surfaceHover: Color

    /** Background fill for dropdown popups, context menus, and select panels. */
    val surfaceDropdown: Color

    /** Background fill for floating hover cards and metric tooltips. */
    val surfaceTooltip: Color

    // =========================================================================
    // 3. Text Hierarchy (8 tokens)
    // =========================================================================

    /** Primary text: active document titles, prominent headers, and high-contrast labels. */
    val textPrimary: Color

    /** Secondary text: toolbar descriptors, input values, and normal body copy. */
    val textSecondary: Color

    /** Muted text: inactive tabs, resting icon buttons, and secondary captions. */
    val textMuted: Color

    /** Subtle text: close icons, collapsed indicators, and scrollbar thumb hover cues. */
    val textSubtle: Color

    /** Dimmed text: unselected gutter line numbers and hardware telemetry stats. */
    val textDimmed: Color

    /** Faint text: empty state guidance text and placeholder instructions. */
    val textFaint: Color

    /** Monospaced log stream body text rendered inside the 64-bit viewport. */
    val textEditor: Color

    /** Hyperlink blue/cyan for interactive links, file paths, and documentation pointers. */
    val textLink: Color

    // =========================================================================
    // 4. Gutter (6 tokens)
    // =========================================================================

    /** Line number digits in resting state. */
    val gutterLineNormal: Color

    /** Line number digits for the currently selected row. */
    val gutterLineSelected: Color

    /** Line number digits for the active search match needle row. */
    val gutterLineActiveMatch: Color

    /** Line number digits for secondary search matches. */
    val gutterLineMatch: Color

    /** Line number digits displayed while operating in filtered view mode. */
    val gutterLineFiltered: Color

    /** 1dp vertical separator border dividing gutter from viewport text. */
    val gutterBorder: Color

    // =========================================================================
    // 5. Row Highlights & Search Needles (9 tokens)
    // =========================================================================

    /** Full row strip background for selected log line. */
    val rowSelected: Color

    /** Full row strip background for active search match line. */
    val rowActiveMatch: Color

    /** Full row strip background for secondary search match lines. */
    val rowMatch: Color

    /** Full row strip background for filtered match lines. */
    val rowFilteredMatch: Color

    /** Highlight block behind the active search match needle string. */
    val searchHighlightActive: Color

    /** Highlight block behind inactive search match needle occurrences. */
    val searchHighlightInactive: Color

    /** Foreground text color inside the active search match highlight block. */
    val searchTextActive: Color

    /** Foreground text color inside inactive search match highlight blocks. */
    val searchTextInactive: Color

    /** Background overlay for interactive mouse-drag character selection. */
    val selectionBackground: Color

    // =========================================================================
    // 6. Forensic Severity Indicators (5 tokens)
    // =========================================================================

    /** Critical exceptions, severe errors, and fatal crash highlights. */
    val severityError: Color

    /** Warnings, moderate latency anomalies, and non-fatal alerts. */
    val severityWarn: Color

    /** Standard information indicators, matched terms, and neutral telemetry. */
    val severityInfo: Color

    /** Detailed debugging telemetry and SIMD execution badges. */
    val severityDebug: Color

    /** Fine-grained execution traces and low-priority diagnostic telemetry. */
    val severityTrace: Color

    // =========================================================================
    // 7. Progress & Badges (12 tokens)
    // =========================================================================

    /** Start gradient color for progressive indexing bar. */
    val progressGradientStart: Color

    /** End gradient color for progressive indexing bar. */
    val progressGradientEnd: Color

    /** Background pill fill for total line count badges. */
    val badgeLinesBackground: Color

    /** Text color inside total line count badges. */
    val badgeLinesText: Color

    /** Background pill fill for file size telemetry badges. */
    val badgeSizeBackground: Color

    /** Text color inside file size telemetry badges. */
    val badgeSizeText: Color

    /** Background pill fill for SIMD acceleration vector width badges. */
    val badgeSimdBackground: Color

    /** Text color inside SIMD acceleration vector width badges. */
    val badgeSimdText: Color

    /** Background pill fill for indexing elapsed duration badges. */
    val badgeDurationBackground: Color

    /** Text color inside indexing elapsed duration badges. */
    val badgeDurationText: Color

    /** Background banner fill for forensic error notifications. */
    val badgeErrorBackground: Color

    /** Text and icon color inside forensic error notifications. */
    val badgeErrorText: Color

    // =========================================================================
    // 8. Temporal Navigation & Latency Gaps (10 tokens)
    // =========================================================================

    /** Background groove track of the dual-handle range scrubber slider. */
    val scrubberTrack: Color

    /** Start gradient color of the bounded temporal window fill strip. */
    val scrubberFillStart: Color

    /** End gradient color of the bounded temporal window fill strip. */
    val scrubberFillEnd: Color

    /** Knob core fill for left and right temporal scrubber handles. */
    val scrubberHandle: Color

    /** Knob outer border ring for temporal scrubber handles. */
    val scrubberHandleBorder: Color

    /** Moderate latency gap indicator (>1s delay between consecutive records). */
    val latencyGapAmber: Color

    /** Severe latency gap indicator (>5s delay between consecutive records). */
    val latencyGapRed: Color

    /** Background fill for active Temporal Focus Mode status badges. */
    val temporalFocusBadgeBackground: Color

    /** Text color inside active Temporal Focus Mode status badges. */
    val temporalFocusBadgeText: Color

    /** Darkening scrim applied to minimap regions falling outside temporal focus bounds. */
    val temporalFocusDimOverlay: Color

    // =========================================================================
    // 9. GPU Seismograph Minimap (5 tokens)
    // =========================================================================

    /** Default histogram density bar tint for unflagged log volume. */
    val minimapDensity: Color

    /** Vibrant spike bar tint for log bins containing error entries. */
    val minimapError: Color

    /** Warning bar tint for log bins containing warning entries. */
    val minimapWarn: Color

    /** Glow indicator tick for search match needle occurrences along the track. */
    val minimapMatch: Color

    /** Semi-transparent window rectangle tracking visible viewport lines. */
    val minimapViewportRect: Color

    // =========================================================================
    // 10. Window Chrome & Accents (8 tokens)
    // =========================================================================

    /** Integrated title bar background strip fill. */
    val titleBarBackground: Color

    /** Centered document title copy in window header. */
    val titleBarText: Color

    /** Background fill when hovering the title bar close (✕) button. */
    val windowControlHoverClose: Color

    /** Background fill when hovering minimize and maximize title bar buttons. */
    val windowControlHover: Color

    /** Primary brand accent color used for focused controls and key highlights. */
    val accentPrimary: Color

    /** Secondary brand accent color used for gradient terminals and active glows. */
    val accentSecondary: Color

    /** Subtle 1dp structural border bounding cards, panels, and split panes. */
    val border: Color

    /** High-contrast border bounding actively focused input controls. */
    val borderFocused: Color

    // =========================================================================
    // 11. Menu System (6 tokens)
    // =========================================================================

    /** Background fill for dropdown cascading popup menus. */
    val menuBackground: Color

    /** 1dp perimeter stroke bounding menu popup windows. */
    val menuBorder: Color

    /** Background fill for hovered menu items. */
    val menuItemHover: Color

    /** Horizontal hairline divider separating menu sections. */
    val menuSeparator: Color

    /** Muted accelerator shortcut text (e.g., "Ctrl+O", "Alt+F4"). */
    val menuShortcutText: Color

    /** Tint color for action icons leading menu labels. */
    val menuIconTint: Color

    // =========================================================================
    // 12. Convenience & Aliased Defaults
    // =========================================================================

    val badgeSuccessText: Color get() = severityDebug
    val badgeSuccessBackground: Color get() = badgeSizeBackground
    val badgeWarningText: Color get() = severityWarn
    val badgeWarningBackground: Color get() = badgeDurationBackground
    val progressText: Color get() = textLink
    val scrollbarThumbDragging: Color get() = textMuted
    val scrollbarThumbHovered: Color get() = textSubtle
    val scrollbarThumbResting: Color get() = textFaint
    val scrollbarHorizontalUnhovered: Color get() = if (isDark) Color(0x40808080) else Color(0x30000000)
    val scrollbarHorizontalHovered: Color get() = if (isDark) Color(0x90808080) else Color(0x60000000)
    val errorIconColor: Color get() = badgeErrorText
    val errorClassNameColor: Color get() = severityError
    val errorThreadColor: Color get() = textSecondary
    val errorMessageColor: Color get() = textPrimary
    val errorFilePathColor: Color get() = textMuted
    val scrubberTimestampLabel: Color get() = textSecondary
    val scrubberTickGap: Color get() = latencyGapAmber
    val scrubberTickSevereGap: Color get() = latencyGapRed
    val textEmptyStateTitle: Color get() = textSubtle
    val textEmptyStateSubtitle: Color get() = textFaint
}
