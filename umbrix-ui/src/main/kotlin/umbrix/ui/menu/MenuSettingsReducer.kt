package umbrix.ui.menu

import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.ThemeRegistry

/**
 * Pure reducer translating settings-scoped [MenuAction]s into a new [WorkstationSettings] snapshot.
 *
 * Kept free of Compose and I/O so theme/view/recent-file semantics are unit-testable; the caller
 * persists the returned snapshot via [umbrix.ui.settings.SettingsManager.updateSettings].
 */
object MenuSettingsReducer {

    /**
     * @return the updated settings, or null when [action] is not settings-scoped (dialogs, navigation,
     * file loading, lifecycle) or would not change anything (e.g. unknown theme name).
     */
    fun reduce(action: MenuAction, currentSettings: WorkstationSettings): WorkstationSettings? {
        return when (action) {
            is MenuAction.SetTheme -> {
                ThemeRegistry.findByName(action.themeName)
                    ?.takeIf { resolvedTheme -> resolvedTheme.name != currentSettings.themeName }
                    ?.let { resolvedTheme -> currentSettings.copy(themeName = resolvedTheme.name) }
            }
            is MenuAction.SetViewportMode -> {
                if (currentSettings.viewportMode == action.mode) null else currentSettings.copy(viewportMode = action.mode)
            }
            is MenuAction.ToggleView -> toggle(action.toggle, currentSettings)
            is MenuAction.ClearRecentFiles -> {
                if (currentSettings.recentFiles.isEmpty()) null else currentSettings.copy(recentFiles = emptyList())
            }
            else -> null
        }
    }

    /** Current checkmark state for a View submenu toggle. */
    fun isToggleEnabled(toggle: ViewToggle, settings: WorkstationSettings): Boolean = when (toggle) {
        ViewToggle.MINIMAP -> settings.isMinimapEnabled
        ViewToggle.LINE_NUMBERS -> settings.showLineNumbers
        ViewToggle.LATENCY_GAPS -> settings.isLatencyGapHighlightingEnabled
        ViewToggle.WHITESPACE -> settings.showWhitespaceChars
    }

    private fun toggle(toggle: ViewToggle, settings: WorkstationSettings): WorkstationSettings = when (toggle) {
        ViewToggle.MINIMAP -> settings.copy(isMinimapEnabled = !settings.isMinimapEnabled)
        ViewToggle.LINE_NUMBERS -> settings.copy(showLineNumbers = !settings.showLineNumbers)
        ViewToggle.LATENCY_GAPS -> settings.copy(isLatencyGapHighlightingEnabled = !settings.isLatencyGapHighlightingEnabled)
        ViewToggle.WHITESPACE -> settings.copy(showWhitespaceChars = !settings.showWhitespaceChars)
    }
}
