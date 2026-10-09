package umbrix.ui.palette

import umbrix.ui.settings.WorkstationSettings
import java.io.File

/**
 * Builds file operations, tab switching, and recent file commands for the Command Palette.
 */
object FileCommands {

    fun build(ctx: CommandExecutionContext, settings: WorkstationSettings): List<PaletteCommand> {
        val commands = mutableListOf<PaletteCommand>()

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

        // Dynamic Open Tabs
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

        // Dynamic Recent Files
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

        return commands
    }
}
