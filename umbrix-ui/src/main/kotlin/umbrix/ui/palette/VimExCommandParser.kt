package umbrix.ui.palette

import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.theme.ThemeRegistry

/**
 * Result returned by the Vim Ex-Command parser.
 */
sealed class VimCommandResult {
    data class Execute(val description: String, val action: () -> Unit) : VimCommandResult()
    data class Error(val message: String) : VimCommandResult()
    object NotVimCommand : VimCommandResult()
}

/**
 * Evaluates modal Vim ex-commands prefixed with ':'.
 */
object VimExCommandParser {

    /**
     * Parses an input string [input]. If it starts with ':', evaluates the command.
     * Context callbacks allow decoupling execution from the UI host.
     */
    fun parse(
        input: String,
        onJumpToLine: (Long) -> Unit = {},
        onCloseCurrentTab: () -> Unit = {},
        onCloseAllTabs: () -> Unit = {},
        onNextTab: () -> Unit = {},
        onPrevTab: () -> Unit = {},
        onOpenExportDialog: () -> Unit = {},
        onOpenSettingsDialog: () -> Unit = {},
        onOpenShortcutsDialog: () -> Unit = {},
        onTriggerSearch: (String) -> Unit = {}
    ): VimCommandResult {
        val trimmed = input.trim()
        if (!trimmed.startsWith(":")) {
            return VimCommandResult.NotVimCommand
        }

        val cmdBody = trimmed.substring(1).trim()
        if (cmdBody.isEmpty()) {
            return VimCommandResult.Error("Enter a Vim command (e.g. :1234, :w, :q, :theme <name>, :set minimap)")
        }

        // 1. Line jump: :<digits>
        val lineNum = cmdBody.toLongOrNull()
        if (lineNum != null) {
            if (lineNum <= 0L) {
                return VimCommandResult.Error("Line number must be >= 1")
            }
            val targetLine = lineNum - 1L // Vim is 1-indexed
            return VimCommandResult.Execute("Jump to line $lineNum") {
                onJumpToLine(targetLine)
            }
        }

        val parts = cmdBody.split(Regex("\\s+"), limit = 2)
        val verb = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        return when (verb) {
            "w", "write" -> {
                VimCommandResult.Execute("Open Export Sub-File dialog") {
                    onOpenExportDialog()
                }
            }
            "q", "quit" -> {
                VimCommandResult.Execute("Close current tab") {
                    onCloseCurrentTab()
                }
            }
            "q!" -> {
                VimCommandResult.Execute("Force close current tab") {
                    onCloseCurrentTab()
                }
            }
            "qa", "qall" -> {
                VimCommandResult.Execute("Close all tabs") {
                    onCloseAllTabs()
                }
            }
            "tabn", "gt" -> {
                VimCommandResult.Execute("Next tab") {
                    onNextTab()
                }
            }
            "tabp", "tabprev", "gt" -> {
                VimCommandResult.Execute("Previous tab") {
                    onPrevTab()
                }
            }
            "tabc", "tabclose" -> {
                VimCommandResult.Execute("Close current tab") {
                    onCloseCurrentTab()
                }
            }
            "theme", "colorscheme", "colo" -> {
                if (arg.isEmpty()) {
                    VimCommandResult.Error("Current theme: ${SettingsManager.currentSettings.themeName}. Use :theme <name>")
                } else {
                    val matchingTheme = ThemeRegistry.allThemes().firstOrNull {
                        it.name.contains(arg, ignoreCase = true)
                    }
                    if (matchingTheme != null) {
                        VimCommandResult.Execute("Switch theme to ${matchingTheme.name}") {
                            SettingsManager.updateSettings(
                                SettingsManager.currentSettings.copy(themeName = matchingTheme.name)
                            )
                        }
                    } else {
                        VimCommandResult.Error("Unknown theme '$arg'. Available: ${ThemeRegistry.allThemes().joinToString { it.name }}")
                    }
                }
            }
            "set" -> {
                parseSetCommand(arg)
            }
            "find", "search" -> {
                if (arg.isEmpty()) {
                    VimCommandResult.Error("Usage: :find <query>")
                } else {
                    VimCommandResult.Execute("Search for '$arg'") {
                        onTriggerSearch(arg)
                    }
                }
            }
            "settings", "prefs", "options" -> {
                VimCommandResult.Execute("Open Settings dialog") {
                    onOpenSettingsDialog()
                }
            }
            "help", "h" -> {
                VimCommandResult.Execute("Open Keyboard Shortcuts cheatsheet") {
                    onOpenShortcutsDialog()
                }
            }
            else -> {
                VimCommandResult.Error("Not an editor command: :$verb (try :help, :set, :w, :q, :theme)")
            }
        }
    }

    private fun parseSetCommand(arg: String): VimCommandResult {
        if (arg.isEmpty()) {
            return VimCommandResult.Error("Usage: :set <option> (e.g. :set minimap, :set nominimap, :set numbers, :set mode=compact)")
        }

        val s = SettingsManager.currentSettings
        val lowerArg = arg.lowercase()

        return when {
            lowerArg == "minimap" || lowerArg == "minimap=1" || lowerArg == "minimap=true" -> {
                VimCommandResult.Execute("Enable Minimap") {
                    SettingsManager.updateSettings(s.copy(isMinimapEnabled = true))
                }
            }
            lowerArg == "minimap!" || lowerArg == "invminimap" -> {
                VimCommandResult.Execute("${if (s.isMinimapEnabled) "Disable" else "Enable"} Minimap") {
                    SettingsManager.updateSettings(s.copy(isMinimapEnabled = !s.isMinimapEnabled))
                }
            }
            lowerArg == "nominimap" || lowerArg == "minimap=0" || lowerArg == "minimap=false" -> {
                VimCommandResult.Execute("Disable Minimap") {
                    SettingsManager.updateSettings(s.copy(isMinimapEnabled = false))
                }
            }
            lowerArg == "numbers" || lowerArg == "nu" || lowerArg == "numbers=1" -> {
                VimCommandResult.Execute("Show line numbers") {
                    SettingsManager.updateSettings(s.copy(showLineNumbers = true))
                }
            }
            lowerArg == "nonumbers" || lowerArg == "nonu" || lowerArg == "numbers=0" -> {
                VimCommandResult.Execute("Hide line numbers") {
                    SettingsManager.updateSettings(s.copy(showLineNumbers = false))
                }
            }
            lowerArg == "whitespace" || lowerArg == "whitespace=1" -> {
                VimCommandResult.Execute("Show whitespace glyphs") {
                    SettingsManager.updateSettings(s.copy(showWhitespaceChars = true))
                }
            }
            lowerArg == "nowhitespace" || lowerArg == "whitespace=0" -> {
                VimCommandResult.Execute("Hide whitespace glyphs") {
                    SettingsManager.updateSettings(s.copy(showWhitespaceChars = false))
                }
            }
            lowerArg.startsWith("mode=") -> {
                val modeVal = lowerArg.substring(5).trim()
                val mode = when (modeVal) {
                    "compact" -> ViewportLayoutMode.COMPACT
                    "expand", "expandable" -> ViewportLayoutMode.EXPANDABLE
                    "wrap", "fullwrap" -> ViewportLayoutMode.FULL_WRAP
                    else -> null
                }
                if (mode != null) {
                    VimCommandResult.Execute("Set viewport mode to ${mode.displayLabel}") {
                        SettingsManager.updateSettings(s.copy(viewportMode = mode))
                    }
                } else {
                    VimCommandResult.Error("Unknown mode '$modeVal'. Valid: compact, expand, wrap")
                }
            }
            else -> {
                VimCommandResult.Error("Unknown option: :set $arg")
            }
        }
    }
}
