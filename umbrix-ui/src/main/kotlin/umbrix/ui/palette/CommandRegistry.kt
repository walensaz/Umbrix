package umbrix.ui.palette

import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.theme.ThemeRegistry
import java.io.File

/**
 * Context callbacks provided by the UI host to execute workstation actions.
 */
data class CommandExecutionContext(
    val onOpenFile: () -> Unit,
    val onOpenRecentFile: (String) -> Unit,
    val onExportSubFile: () -> Unit,
    val onGoToLine: () -> Unit,
    val onJumpToDirectLine: (Long) -> Unit,
    val onGoToTimestamp: () -> Unit,
    val onOpenSearch: () -> Unit,
    val onClearSearch: () -> Unit,
    val onNavigateSearch: (Boolean) -> Unit,
    val onNavigateAnomaly: (Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenShortcuts: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onCaptureDiagnosticReport: () -> Unit,
    val onCloseCurrentTab: () -> Unit,
    val onCloseAllTabs: () -> Unit,
    val onNextTab: () -> Unit,
    val onPrevTab: () -> Unit,
    val onSwitchToTab: (Int) -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onResetZoom: () -> Unit,
    val openTabTitles: List<String> = emptyList(),
    val activeTabIndex: Int = -1,
    val onToggleTimelineDrawer: (() -> Unit)? = null,
    val onSortTimelineChronologically: (() -> Unit)? = null,
    val onExportTimelineReport: (() -> Unit)? = null,
    val onToggleBookmark: (() -> Unit)? = null,
    val onNextBookmark: (() -> Unit)? = null,
    val onPrevBookmark: (() -> Unit)? = null,
    val onOpenBookmarkManager: (() -> Unit)? = null,
    val onNewScratchpad: (() -> Unit)? = null,
    val onOpenScratchpadManager: (() -> Unit)? = null,
    val onToggleBookmarkFilter: (() -> Unit)? = null,
    val onExpandAllEvents: (() -> Unit)? = null,
    val onCollapseAllEvents: (() -> Unit)? = null,
    val onToggleAiAssistant: (() -> Unit)? = null
)

/**
 * Registry maintaining built-in workstation actions and dynamic navigation targets.
 */
object CommandRegistry {

    private val staticCommands = mutableListOf<PaletteCommand>()

    /**
     * Builds and returns the comprehensive list of commands for the active session.
     */
    fun getAvailableCommands(ctx: CommandExecutionContext): List<PaletteCommand> {
        val commands = mutableListOf<PaletteCommand>()
        val settings = SettingsManager.currentSettings

        // --- 1. File Actions ---
        commands.add(
            PaletteCommand(
                id = "file.open",
                title = "File: Open Log File...",
                category = CommandCategory.FILES,
                shortcutHint = "Ctrl+O",
                keywords = listOf("load", "open", "read", "document"),
                action = ctx.onOpenFile
            )
        )
        if (ctx.onNewScratchpad != null) {
            commands.add(
                PaletteCommand(
                    id = "file.new_scratchpad",
                    title = "File: New Scratchpad Buffer",
                    category = CommandCategory.FILES,
                    shortcutHint = "Ctrl+N",
                    keywords = listOf("scratchpad", "notes", "buffer", "editor", "new"),
                    action = ctx.onNewScratchpad
                )
            )
        }
        if (ctx.onOpenScratchpadManager != null) {
            commands.add(
                PaletteCommand(
                    id = "file.manage_scratchpads",
                    title = "File: Manage Scratchpads & Extracted Slices...",
                    category = CommandCategory.FILES,
                    shortcutHint = "Ctrl+Alt+S",
                    keywords = listOf("scratchpad", "notes", "slices", "reopen", "delete", "snippets", "manage"),
                    action = ctx.onOpenScratchpadManager
                )
            )
        }
        commands.add(
            PaletteCommand(
                id = "file.export_slice",
                title = "File: Export Sub-File Slice...",
                category = CommandCategory.FILES,
                shortcutHint = "Ctrl+Shift+S",
                keywords = listOf("save", "export", "slice", "trim", "dma"),
                action = ctx.onExportSubFile
            )
        )
        commands.add(
            PaletteCommand(
                id = "tab.close",
                title = "Tab: Close Current Tab",
                category = CommandCategory.FILES,
                shortcutHint = "Ctrl+W",
                keywords = listOf("close", "exit tab", "quit tab"),
                action = ctx.onCloseCurrentTab
            )
        )
        commands.add(
            PaletteCommand(
                id = "tab.close_all",
                title = "Tab: Close All Tabs",
                category = CommandCategory.FILES,
                keywords = listOf("close all", "clear tabs"),
                action = ctx.onCloseAllTabs
            )
        )
        commands.add(
            PaletteCommand(
                id = "tab.next",
                title = "Tab: Next Workspace Tab",
                category = CommandCategory.FILES,
                shortcutHint = "Ctrl+Tab",
                keywords = listOf("switch tab", "next tab"),
                action = ctx.onNextTab
            )
        )
        commands.add(
            PaletteCommand(
                id = "tab.prev",
                title = "Tab: Previous Workspace Tab",
                category = CommandCategory.FILES,
                shortcutHint = "Ctrl+Shift+Tab",
                keywords = listOf("prev tab", "previous tab"),
                action = ctx.onPrevTab
            )
        )

        // --- 2. Dynamic Open Tabs ---
        ctx.openTabTitles.forEachIndexed { index, title ->
            val isActive = index == ctx.activeTabIndex
            commands.add(
                PaletteCommand(
                    id = "tab.switch.$index",
                    title = "Tab: Switch to '$title'${if (isActive) " (Active)" else ""}",
                    category = CommandCategory.FILES,
                    keywords = listOf("tab", title),
                    action = { ctx.onSwitchToTab(index) }
                )
            )
        }

        // --- 3. Dynamic Recent Files ---
        settings.recentFiles.forEach { path ->
            val file = File(path)
            commands.add(
                PaletteCommand(
                    id = "recent.open_${file.name}",
                    title = "Recent: Open ${file.name}",
                    category = CommandCategory.FILES,
                    keywords = listOf("recent", file.name, path),
                    action = { ctx.onOpenRecentFile(path) }
                )
            )
        }

        // --- 4. Search & Navigation Actions ---
        commands.add(
            PaletteCommand(
                id = "search.open",
                title = "Search: Open Search Bar",
                category = CommandCategory.ACTIONS,
                shortcutHint = "Ctrl+F",
                keywords = listOf("find", "query", "simd", "filter"),
                action = ctx.onOpenSearch
            )
        )
        commands.add(
            PaletteCommand(
                id = "search.clear",
                title = "Search: Clear Query & Results",
                category = CommandCategory.ACTIONS,
                shortcutHint = "Esc",
                keywords = listOf("reset search", "clear search"),
                action = ctx.onClearSearch
            )
        )
        commands.add(
            PaletteCommand(
                id = "search.next_match",
                title = "Search: Next Match",
                category = CommandCategory.NAVIGATION,
                shortcutHint = "F3",
                keywords = listOf("forward match", "next hit"),
                action = { ctx.onNavigateSearch(true) }
            )
        )
        commands.add(
            PaletteCommand(
                id = "search.prev_match",
                title = "Search: Previous Match",
                category = CommandCategory.NAVIGATION,
                shortcutHint = "Shift+F3",
                keywords = listOf("backward match", "prev hit"),
                action = { ctx.onNavigateSearch(false) }
            )
        )
        commands.add(
            PaletteCommand(
                id = "anomaly.next",
                title = "Anomaly: Next Error / Warning",
                category = CommandCategory.NAVIGATION,
                shortcutHint = "F4",
                keywords = listOf("next error", "next warning", "anomaly"),
                action = { ctx.onNavigateAnomaly(true) }
            )
        )
        commands.add(
            PaletteCommand(
                id = "anomaly.prev",
                title = "Anomaly: Previous Error / Warning",
                category = CommandCategory.NAVIGATION,
                shortcutHint = "Shift+F4",
                keywords = listOf("prev error", "prev warning", "anomaly"),
                action = { ctx.onNavigateAnomaly(false) }
            )
        )
        commands.add(
            PaletteCommand(
                id = "nav.goto_line",
                title = "Navigation: Go to Line...",
                category = CommandCategory.NAVIGATION,
                shortcutHint = "Ctrl+G",
                keywords = listOf("jump line", "line number", "seek"),
                action = ctx.onGoToLine
            )
        )
        commands.add(
            PaletteCommand(
                id = "nav.goto_timestamp",
                title = "Navigation: Go to Timestamp / Scrubber...",
                category = CommandCategory.NAVIGATION,
                shortcutHint = "Ctrl+T",
                keywords = listOf("time", "scrubber", "timestamp", "clock"),
                action = ctx.onGoToTimestamp
            )
        )

        // --- Bookmark Commands ---
        if (ctx.onNextBookmark != null) {
            commands.add(
                PaletteCommand(
                    id = "bookmark.next",
                    title = "Bookmark: Jump to Next Bookmark",
                    category = CommandCategory.NAVIGATION,
                    shortcutHint = "F2",
                    keywords = listOf("bookmark", "pin", "next", "mark"),
                    action = ctx.onNextBookmark
                )
            )
        }
        if (ctx.onPrevBookmark != null) {
            commands.add(
                PaletteCommand(
                    id = "bookmark.prev",
                    title = "Bookmark: Jump to Previous Bookmark",
                    category = CommandCategory.NAVIGATION,
                    shortcutHint = "Shift+F2",
                    keywords = listOf("bookmark", "pin", "prev", "mark"),
                    action = ctx.onPrevBookmark
                )
            )
        }
        if (ctx.onToggleBookmark != null) {
            commands.add(
                PaletteCommand(
                    id = "bookmark.toggle",
                    title = "Bookmark: Toggle Bookmark on Active Line",
                    category = CommandCategory.ACTIONS,
                    shortcutHint = "Ctrl+B",
                    keywords = listOf("bookmark", "pin", "toggle", "mark"),
                    action = ctx.onToggleBookmark
                )
            )
        }
        if (ctx.onOpenBookmarkManager != null) {
            commands.add(
                PaletteCommand(
                    id = "bookmark.manage",
                    title = "Bookmark: Manage Document Bookmarks...",
                    category = CommandCategory.ACTIONS,
                    keywords = listOf("bookmarks", "pins", "list", "notes"),
                    action = ctx.onOpenBookmarkManager
                )
            )
        }
        if (ctx.onToggleBookmarkFilter != null) {
            commands.add(
                PaletteCommand(
                    id = "bookmark.toggle_filter",
                    title = "Bookmark: Filter View to Bookmarks / Restore All",
                    category = CommandCategory.VIEW,
                    shortcutHint = "Ctrl+Alt+B",
                    keywords = listOf("bookmark", "filter", "isolate", "only bookmarks", "pinned"),
                    action = ctx.onToggleBookmarkFilter
                )
            )
        }

        // --- Timeline Commands ---
        if (ctx.onToggleTimelineDrawer != null) {
            commands.add(
                PaletteCommand(
                    id = "timeline.toggle",
                    title = "Timeline: Toggle Incident Timeline Drawer",
                    category = CommandCategory.VIEW,
                    shortcutHint = "Ctrl+Shift+T",
                    keywords = listOf("timeline", "incident", "events", "reconstruction", "drawer"),
                    action = ctx.onToggleTimelineDrawer
                )
            )
        }
        if (ctx.onSortTimelineChronologically != null) {
            commands.add(
                PaletteCommand(
                    id = "timeline.sort",
                    title = "Timeline: Sort Events Chronologically",
                    category = CommandCategory.ACTIONS,
                    keywords = listOf("timeline", "sort", "chronological", "timestamp", "order"),
                    action = ctx.onSortTimelineChronologically
                )
            )
        }
        if (ctx.onExportTimelineReport != null) {
            commands.add(
                PaletteCommand(
                    id = "timeline.export",
                    title = "Timeline: Export Incident Report...",
                    category = CommandCategory.ACTIONS,
                    keywords = listOf("timeline", "export", "markdown", "report", "incident"),
                    action = ctx.onExportTimelineReport
                )
            )
        }
        if (ctx.onToggleAiAssistant != null) {
            commands.add(
                PaletteCommand(
                    id = "view.toggle_ai_assistant",
                    title = "AI: Toggle Forensic AI Assistant Drawer",
                    category = CommandCategory.ACTIONS,
                    shortcutHint = "Ctrl+Shift+I",
                    keywords = listOf("ai", "assistant", "chat", "forensics", "ollama", "mcp"),
                    action = ctx.onToggleAiAssistant
                )
            )
        }

        // --- 5. View & Viewport Controls ---
        commands.add(
            PaletteCommand(
                id = "view.toggle_minimap",
                title = "View: ${if (settings.isMinimapEnabled) "Hide" else "Show"} GPU Seismograph Minimap",
                category = CommandCategory.VIEW,
                keywords = listOf("minimap", "seismograph", "density", "gutter"),
                action = {
                    SettingsManager.updateSettings(settings.copy(isMinimapEnabled = !settings.isMinimapEnabled))
                }
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.toggle_line_numbers",
                title = "View: ${if (settings.showLineNumbers) "Hide" else "Show"} Line Numbers",
                category = CommandCategory.VIEW,
                keywords = listOf("line numbers", "gutter", "numbers"),
                action = {
                    SettingsManager.updateSettings(settings.copy(showLineNumbers = !settings.showLineNumbers))
                }
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.toggle_whitespace",
                title = "View: ${if (settings.showWhitespaceChars) "Hide" else "Show"} Whitespace Characters",
                category = CommandCategory.VIEW,
                keywords = listOf("whitespace", "spaces", "tabs"),
                action = {
                    SettingsManager.updateSettings(settings.copy(showWhitespaceChars = !settings.showWhitespaceChars))
                }
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.mode_compact",
                title = "View: Set Compact Mode (Fixed 20px, Max FPS)",
                category = CommandCategory.VIEW,
                keywords = listOf("compact mode", "performance", "fixed height"),
                action = {
                    SettingsManager.updateSettings(settings.copy(viewportMode = ViewportLayoutMode.COMPACT))
                }
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.mode_expandable",
                title = "View: Set Expandable Mode (Default, Inline Stack Traces)",
                category = CommandCategory.VIEW,
                keywords = listOf("expandable mode", "stack traces", "chevrons"),
                action = {
                    SettingsManager.updateSettings(settings.copy(viewportMode = ViewportLayoutMode.EXPANDABLE))
                }
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.mode_wrap",
                title = "View: Set Full Wrap Mode (Soft Wrap)",
                category = CommandCategory.VIEW,
                keywords = listOf("wrap mode", "line wrap", "soft wrap"),
                action = {
                    SettingsManager.updateSettings(settings.copy(viewportMode = ViewportLayoutMode.FULL_WRAP))
                }
            )
        )
        if (ctx.onExpandAllEvents != null) {
            commands.add(
                PaletteCommand(
                    id = "view.expand_all_events",
                    title = "View: Expand All Multi-Line Events in Active Tab",
                    category = CommandCategory.VIEW,
                    keywords = listOf("expand all", "unfold all", "stack traces"),
                    action = ctx.onExpandAllEvents
                )
            )
        }
        if (ctx.onCollapseAllEvents != null) {
            commands.add(
                PaletteCommand(
                    id = "view.collapse_all_events",
                    title = "View: Collapse All Multi-Line Events in Active Tab",
                    category = CommandCategory.VIEW,
                    keywords = listOf("collapse all", "fold all", "stack traces"),
                    action = ctx.onCollapseAllEvents
                )
            )
        }

        // --- 6. Zoom Actions ---
        commands.add(
            PaletteCommand(
                id = "view.zoom_in",
                title = "View: Zoom In (Increase Font Size)",
                category = CommandCategory.VIEW,
                shortcutHint = "Ctrl++",
                keywords = listOf("zoom in", "larger font", "scale up"),
                action = ctx.onZoomIn
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.zoom_out",
                title = "View: Zoom Out (Decrease Font Size)",
                category = CommandCategory.VIEW,
                shortcutHint = "Ctrl+-",
                keywords = listOf("zoom out", "smaller font", "scale down"),
                action = ctx.onZoomOut
            )
        )
        commands.add(
            PaletteCommand(
                id = "view.zoom_reset",
                title = "View: Reset Zoom",
                category = CommandCategory.VIEW,
                shortcutHint = "Ctrl+0",
                keywords = listOf("reset zoom", "default font"),
                action = ctx.onResetZoom
            )
        )

        // --- 7. Theme Presets ---
        ThemeRegistry.allThemes().forEach { theme ->
            val isCurrent = theme.name.equals(settings.themeName, ignoreCase = true)
            commands.add(
                PaletteCommand(
                    id = "theme.${theme.name.lowercase().replace(" ", "_")}",
                    title = "Preferences: Switch Theme to ${theme.name}${if (isCurrent) " (Active)" else ""}",
                    category = CommandCategory.THEMES,
                    keywords = listOf("theme", "color", "colorscheme", theme.name),
                    action = {
                        SettingsManager.updateSettings(settings.copy(themeName = theme.name))
                    }
                )
            )
        }

        // --- 8. Preferences & Dialogs ---
        commands.add(
            PaletteCommand(
                id = "settings.open",
                title = "Preferences: Open Settings Dialog...",
                category = CommandCategory.SETTINGS,
                shortcutHint = "Ctrl+,",
                keywords = listOf("settings", "preferences", "config", "options"),
                action = ctx.onOpenSettings
            )
        )
        commands.add(
            PaletteCommand(
                id = "help.shortcuts",
                title = "Help: Keyboard Shortcuts Cheatsheet...",
                category = CommandCategory.ACTIONS,
                shortcutHint = "F1",
                keywords = listOf("shortcuts", "keymap", "hotkeys", "help"),
                action = ctx.onOpenShortcuts
            )
        )
        commands.add(
            PaletteCommand(
                id = "help.about",
                title = "Help: About Umbrix...",
                category = CommandCategory.ACTIONS,
                keywords = listOf("about", "version", "telemetry", "license"),
                action = ctx.onOpenAbout
            )
        )
        commands.add(
            PaletteCommand(
                id = "help.diagnostic",
                title = "Help: Capture Forensic Diagnostic Report",
                category = CommandCategory.ACTIONS,
                keywords = listOf("diagnostic", "error report", "snapshot", "crash dump"),
                action = ctx.onCaptureDiagnosticReport
            )
        )

        return commands
    }

    /**
     * Filters and scores available commands against [rawQuery].
     */
    fun match(
        rawQuery: String,
        ctx: CommandExecutionContext,
        activeCategory: CommandCategory? = null,
        mruWeights: Map<String, Int> = emptyMap()
    ): List<ScoredCommand> {
        val trimmed = rawQuery.trim()
        val cleanQuery = if (trimmed.startsWith(">")) trimmed.substring(1).trim() else trimmed
        val all = getAvailableCommands(ctx).filter { cmd ->
            cmd.isEnabled() && (activeCategory == null || cmd.category == activeCategory)
        }

        // If query is empty, return items prioritized by MRU weights, with Files & Actions first
        if (cleanQuery.isEmpty()) {
            return all.map { cmd ->
                val basePriority = when (cmd.category) {
                    CommandCategory.FILES -> 30
                    CommandCategory.ACTIONS -> 20
                    CommandCategory.NAVIGATION -> 15
                    CommandCategory.VIEW -> 10
                    CommandCategory.SETTINGS -> 5
                    CommandCategory.THEMES -> 0
                }
                ScoredCommand(cmd, basePriority + mruWeights.getOrDefault(cmd.id, 0))
            }.sortedByDescending { it.score }
        }

        // If query is pure numbers, add an instant line jump action at the very top!
        val digitJump = cleanQuery.removePrefix("#").removePrefix(":").trim().toLongOrNull()
        val resultList = mutableListOf<ScoredCommand>()

        if (digitJump != null && digitJump > 0) {
            val jumpCmd = PaletteCommand(
                id = "nav.dynamic_jump_$digitJump",
                title = "Jump to Line $digitJump",
                category = CommandCategory.NAVIGATION,
                keywords = listOf("line", digitJump.toString()),
                action = { ctx.onJumpToDirectLine(maxOf(0L, digitJump - 1L)) }
            )
            resultList.add(ScoredCommand(jumpCmd, 1000))
        }

        // Fuzzy match against commands
        all.forEach { cmd ->
            val score = FuzzyMatcher.score(
                query = cleanQuery,
                target = cmd.title,
                keywords = cmd.keywords,
                mruBonus = mruWeights.getOrDefault(cmd.id, 0)
            )
            if (score > 0) {
                resultList.add(ScoredCommand(cmd, score))
            }
        }

        return resultList.sortedByDescending { it.score }
    }
}
