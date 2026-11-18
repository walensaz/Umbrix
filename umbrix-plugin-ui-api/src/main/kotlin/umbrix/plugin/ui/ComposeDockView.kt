package umbrix.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import umbrix.plugin.api.config.PluginConfigStore
import umbrix.plugin.api.ui.CustomDockView
import umbrix.plugin.api.ui.DockViewContext

/**
 * Full JetBrains Compose Desktop dockable view for Kotlin plugin authors.
 */
interface ComposeDockView : CustomDockView {
    @Composable
    fun Content(context: DockViewContext)
}

/**
 * Optional custom `@Composable` settings UI panel rendered inside `PluginsSettingsTab`
 * when a plugin requires richer configuration than declarative fields alone.
 */
interface ComposePluginSettingsPanel {
    val pluginId: String

    @Composable
    fun SettingsContent(configStore: PluginConfigStore)
}

/**
 * Read-only snapshot of Umbrix's active theme palette exposed to `ComposeDockView` implementations.
 */
data class PluginThemePalette(
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentPrimary: Color,
    val statusError: Color,
    val statusWarn: Color
)
