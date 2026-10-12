package umbrix.ui.menu

import umbrix.ui.settings.ViewportLayoutMode

/**
 * Strongly typed action contract dispatched by the Umbrix cascading menu system.
 * Handled by the root workstation controller ([umbrix.ui.screen.UmbrixMainScreen]) and routed to
 * active document sessions, dialog managers, or persistent settings storage.
 */
sealed interface MenuAction {

    // --- File Operations ---
    data object OpenFile : MenuAction
    data object NewScratchpad : MenuAction
    data object OpenScratchpadManager : MenuAction
    data class OpenRecentFile(val path: String) : MenuAction
    data object ClearRecentFiles : MenuAction
    data object ExportSubFile : MenuAction

    // --- Search & Navigation ---
    data object CommandPalette : MenuAction
    data object Find : MenuAction
    data object GoToLineTimestamp : MenuAction
    data object TemporalRange : MenuAction
    data object ToggleTimelineDrawer : MenuAction
    data object ManageBookmarks : MenuAction
    data object ToggleBookmarkFilter : MenuAction

    // --- View Configurations ---
    data class SetViewportMode(val mode: ViewportLayoutMode) : MenuAction
    data class ToggleView(val toggle: ViewToggle) : MenuAction

    // --- Appearance & Theming ---
    data class SetTheme(val themeName: String) : MenuAction

    // --- Modals & Diagnostics ---
    data object OpenSettings : MenuAction
    data object DiagnosticReport : MenuAction
    data object OpenLogsFolder : MenuAction
    data object KeyboardShortcuts : MenuAction
    data object About : MenuAction

    // --- Application Lifecycle ---
    data object Exit : MenuAction
}

/**
 * Boolean chrome overlays toggled from the View submenu. Typed (not stringly keyed) so an
 * unknown toggle is a compile error rather than a silently ignored menu click.
 */
enum class ViewToggle(val displayLabel: String) {
    MINIMAP("Show Minimap"),
    LINE_NUMBERS("Show Line Numbers"),
    LATENCY_GAPS("Show Latency Gaps"),
    WHITESPACE("Show Whitespace Characters")
}
