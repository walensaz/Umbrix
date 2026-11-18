package umbrix.ui.palette

import umbrix.ui.settings.SettingsManager

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
    val onToggleAiAssistant: (() -> Unit)? = null,
    val onShowRawRecords: (() -> Unit)? = null
)

/**
 * Registry maintaining built-in workstation actions and dynamic navigation targets.
 */
object CommandRegistry {

    /**
     * Builds and returns the comprehensive list of commands for the active session.
     */
    fun getAvailableCommands(ctx: CommandExecutionContext): List<PaletteCommand> {
        val settings = SettingsManager.currentSettings
        val commands = ArrayList<PaletteCommand>()
        commands.addAll(FileCommands.build(ctx, settings))
        commands.addAll(NavigationCommands.build(ctx))
        commands.addAll(ViewCommands.build(ctx, settings))
        commands.addAll(ToolCommands.build(ctx))

        try {
            val sessionView = umbrix.core.plugin.PluginManager.instance().getWorkspaceController().activeSession()
            val pluginActions = umbrix.core.plugin.PluginManager.instance().commandPaletteActions()
            for (action in pluginActions) {
                commands.add(
                    PaletteCommand(
                        id = "plugin.action.${action.actionId()}",
                        title = action.label(),
                        category = CommandCategory.ACTIONS,
                        keywords = listOf("plugin", action.actionId()),
                        shortcutHint = action.shortcutHint().ifEmpty { null },
                        action = {
                            val invocation = umbrix.plugin.api.action.PluginActionInvocation(
                                sessionView,
                                umbrix.core.plugin.PluginManager.instance().getWorkspaceController(),
                                0L,
                                java.lang.foreign.MemorySegment.NULL,
                                "",
                                ""
                            )
                            try {
                                action.invoke(invocation)
                            } catch (_: Throwable) {}
                        }
                    )
                )
            }
            val dockFactories = umbrix.core.plugin.PluginManager.instance().allDockViewFactories()
            for (factory in dockFactories) {
                commands.add(
                    PaletteCommand(
                        id = "plugin.dockview.${factory.viewId()}",
                        title = "Open View: ${factory.displayName()}",
                        category = CommandCategory.VIEW,
                        keywords = listOf("view", "dock", "plugin", factory.viewId()),
                        action = {
                            umbrix.core.plugin.PluginManager.instance().getWorkspaceController().openDockView(factory.viewId(), "RIGHT")
                        }
                    )
                )
            }
        } catch (_: Throwable) {}

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

        // If query is pure numbers, add an instant line jump action at the very top
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
