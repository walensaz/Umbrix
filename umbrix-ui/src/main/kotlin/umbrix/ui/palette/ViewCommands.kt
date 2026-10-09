package umbrix.ui.palette

import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.ThemeRegistry

/**
 * Builds layout modes, whitespace toggles, minimap controls, themes, and zoom commands for the Command Palette.
 */
object ViewCommands {

    fun build(ctx: CommandExecutionContext, settings: WorkstationSettings): List<PaletteCommand> {
        val commands = mutableListOf<PaletteCommand>()

        // View & Viewport Controls
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

        // Zoom Actions
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

        // Theme Presets
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

        return commands
    }
}
