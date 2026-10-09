package umbrix.ui.palette

/**
 * Builds search, jump-to-line, anomaly navigation, and bookmark commands for the Command Palette.
 */
object NavigationCommands {

    fun build(ctx: CommandExecutionContext): List<PaletteCommand> {
        val commands = mutableListOf<PaletteCommand>()

        // Search & Anomaly Navigation
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

        // Bookmark Commands
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

        return commands
    }
}
