package umbrix.ui.palette

/**
 * Builds timeline actions, AI assistant drawer, settings dialog, help, and diagnostic commands for the Command Palette.
 */
object ToolCommands {

    fun build(ctx: CommandExecutionContext): List<PaletteCommand> {
        val commands = mutableListOf<PaletteCommand>()

        // Timeline Actions
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

        // AI Assistant Action
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

        // Preferences & Help Actions
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
}
