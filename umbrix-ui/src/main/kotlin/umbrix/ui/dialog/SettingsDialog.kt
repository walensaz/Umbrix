package umbrix.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.dialog.settings.AiMcpSettingsTab
import umbrix.ui.dialog.settings.AppearanceSettingsTab
import umbrix.ui.dialog.settings.FontsSettingsTab
import umbrix.ui.dialog.settings.GeneralSettingsTab
import umbrix.ui.dialog.settings.LanguagesSettingsTab
import umbrix.ui.dialog.settings.PerformanceSettingsTab
import umbrix.ui.dialog.settings.PluginsSettingsTab
import umbrix.ui.dialog.settings.ViewSettingsTab
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.UmbrixTheme

private enum class SettingsCategory(val displayName: String, val icon: String) {
    GENERAL("General & Workspace", "⚙️"),
    APPEARANCE("Appearance & Minimap", "🗺"),
    FONTS("Fonts & Typography", "🔤"),
    VIEW("View & Viewport", "👁"),
    LANGUAGES("Log Languages & Profiles", "🌐"),
    PERFORMANCE("Performance & SIMD", "⚡"),
    AI_MCP("AI & MCP Connections", "🤖"),
    PLUGINS("Plugins & Analyzers", "🧩")
}

/**
 * Authentic IntelliJ-style Settings modal dialog with two-pane category navigation.
 */
@Composable
fun SettingsDialog(
    initialSettings: WorkstationSettings,
    onSaveSettings: (WorkstationSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var activeCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }

    var themeName by remember { mutableStateOf(initialSettings.themeName) }
    var isMinimapEnabled by remember { mutableStateOf(initialSettings.isMinimapEnabled) }
    var minimapWidthDp by remember { mutableStateOf(initialSettings.minimapWidthDp) }
    var isSearchHighlightingEnabled by remember { mutableStateOf(initialSettings.isSearchHighlightingEnabled) }
    var activeLanguageProfileId by remember { mutableStateOf(initialSettings.activeLanguageProfileId) }
    var customErrorKeywords by remember { mutableStateOf(initialSettings.customErrorKeywords) }
    var customWarnKeywords by remember { mutableStateOf(initialSettings.customWarnKeywords) }
    var maxShapedCharsPerLine by remember { mutableStateOf(initialSettings.maxShapedCharsPerLine) }
    var enableSimdAcceleration by remember { mutableStateOf(initialSettings.enableSimdAcceleration) }
    var isLatencyGapHighlightingEnabled by remember { mutableStateOf(initialSettings.isLatencyGapHighlightingEnabled) }
    var latencyGapThresholdMs by remember { mutableStateOf(initialSettings.latencyGapThresholdMs) }
    var latencyGapSevereThresholdMs by remember { mutableStateOf(initialSettings.latencyGapSevereThresholdMs) }

    var editorFontFamily by remember { mutableStateOf(initialSettings.editorFontFamily) }
    var editorFontSizePt by remember { mutableStateOf(initialSettings.editorFontSizePt) }
    var lineHeightMultiplier by remember { mutableStateOf(initialSettings.lineHeightMultiplier) }
    var editorFontWeight by remember { mutableStateOf(initialSettings.editorFontWeight) }
    var uiFontFamily by remember { mutableStateOf(initialSettings.uiFontFamily) }
    var uiFontSizePt by remember { mutableStateOf(initialSettings.uiFontSizePt) }
    var enableLigatures by remember { mutableStateOf(initialSettings.enableLigatures) }

    var viewportMode by remember { mutableStateOf(initialSettings.viewportMode) }
    var showLineNumbers by remember { mutableStateOf(initialSettings.showLineNumbers) }
    var showWhitespaceChars by remember { mutableStateOf(initialSettings.showWhitespaceChars) }
    var statusBarShowPosition by remember { mutableStateOf(initialSettings.statusBarShowPosition) }
    var statusBarShowDocumentStats by remember { mutableStateOf(initialSettings.statusBarShowDocumentStats) }
    var statusBarShowMode by remember { mutableStateOf(initialSettings.statusBarShowMode) }
    var statusBarShowIndexingDetails by remember { mutableStateOf(initialSettings.statusBarShowIndexingDetails) }
    var statusBarShowMemory by remember { mutableStateOf(initialSettings.statusBarShowMemory) }

    var commandPaletteStyle by remember { mutableStateOf(initialSettings.commandPaletteStyle) }
    var commandPaletteShowShortcuts by remember { mutableStateOf(initialSettings.commandPaletteShowShortcuts) }
    var enableVimColonTrigger by remember { mutableStateOf(initialSettings.enableVimColonTrigger) }
    var enableDoubleShiftTrigger by remember { mutableStateOf(initialSettings.enableDoubleShiftTrigger) }

    var isMcpServerEnabled by remember { mutableStateOf(initialSettings.isMcpServerEnabled) }
    var mcpServerPort by remember { mutableStateOf(initialSettings.mcpServerPort.toString()) }
    var aiProvider by remember { mutableStateOf(initialSettings.aiProvider) }
    var aiModelName by remember { mutableStateOf(initialSettings.aiModelName) }
    var aiEndpointUrl by remember { mutableStateOf(initialSettings.aiEndpointUrl) }
    var aiApiKey by remember { mutableStateOf(initialSettings.aiApiKey) }
    var restoreSessionOnStartup by remember { mutableStateOf(initialSettings.restoreSessionOnStartup) }

    // copy() preserves menu-driven state (recent files) not edited here
    fun buildCurrent(): WorkstationSettings = initialSettings.copy(
        themeName = themeName,
        isMinimapEnabled = isMinimapEnabled,
        minimapWidthDp = minimapWidthDp,
        isSearchHighlightingEnabled = isSearchHighlightingEnabled,
        activeLanguageProfileId = activeLanguageProfileId,
        customErrorKeywords = customErrorKeywords,
        customWarnKeywords = customWarnKeywords,
        maxShapedCharsPerLine = maxShapedCharsPerLine,
        enableSimdAcceleration = enableSimdAcceleration,
        isLatencyGapHighlightingEnabled = isLatencyGapHighlightingEnabled,
        latencyGapThresholdMs = latencyGapThresholdMs,
        latencyGapSevereThresholdMs = latencyGapSevereThresholdMs,
        editorFontFamily = editorFontFamily,
        editorFontSizePt = editorFontSizePt,
        lineHeightMultiplier = lineHeightMultiplier,
        editorFontWeight = editorFontWeight,
        uiFontFamily = uiFontFamily,
        uiFontSizePt = uiFontSizePt,
        enableLigatures = enableLigatures,
        viewportMode = viewportMode,
        showLineNumbers = showLineNumbers,
        showWhitespaceChars = showWhitespaceChars,
        statusBarShowPosition = statusBarShowPosition,
        statusBarShowDocumentStats = statusBarShowDocumentStats,
        statusBarShowMode = statusBarShowMode,
        statusBarShowIndexingDetails = statusBarShowIndexingDetails,
        statusBarShowMemory = statusBarShowMemory,
        commandPaletteStyle = commandPaletteStyle,
        commandPaletteShowShortcuts = commandPaletteShowShortcuts,
        enableVimColonTrigger = enableVimColonTrigger,
        enableDoubleShiftTrigger = enableDoubleShiftTrigger,
        isMcpServerEnabled = isMcpServerEnabled,
        mcpServerPort = mcpServerPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 4242,
        aiProvider = aiProvider,
        aiModelName = aiModelName,
        aiEndpointUrl = aiEndpointUrl,
        aiApiKey = aiApiKey,
        restoreSessionOnStartup = restoreSessionOnStartup
    )

    fun resetToDefaults() {
        val defaults = WorkstationSettings()
        editorFontFamily = defaults.editorFontFamily
        editorFontSizePt = defaults.editorFontSizePt
        lineHeightMultiplier = defaults.lineHeightMultiplier
        editorFontWeight = defaults.editorFontWeight
        uiFontFamily = defaults.uiFontFamily
        uiFontSizePt = defaults.uiFontSizePt
        enableLigatures = defaults.enableLigatures
        commandPaletteStyle = defaults.commandPaletteStyle
        commandPaletteShowShortcuts = defaults.commandPaletteShowShortcuts
        enableVimColonTrigger = defaults.enableVimColonTrigger
        enableDoubleShiftTrigger = defaults.enableDoubleShiftTrigger
    }

    UmbrixModalCard(
        title = "Settings",
        onDismiss = onDismiss,
        width = 820.dp,
        showCloseButton = true,
        isScrollable = false,
        actions = {
            if (activeCategory == SettingsCategory.FONTS) {
                OutlinedButton(onClick = { resetToDefaults() }) {
                    Text("Reset to Defaults")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val current = buildCurrent()
                SettingsManager.updateSettings(current)
                onSaveSettings(current)
            }) {
                Text("Apply")
            }
            Spacer(modifier = Modifier.width(8.dp))
            DefaultButton(onClick = {
                val current = buildCurrent()
                SettingsManager.updateSettings(current)
                onSaveSettings(current)
                onDismiss()
            }) {
                Text("OK")
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
        ) {
            // Left Sidebar Navigation
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(UmbrixTheme.colors.surfaceGutter)
                    .padding(vertical = 8.dp)
            ) {
                for (category in SettingsCategory.values()) {
                    val isSelected = activeCategory == category
                    val itemBg = if (isSelected) UmbrixTheme.colors.surfaceElevated else Color.Transparent
                    val itemTextColor = if (isSelected) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { activeCategory = category }
                            .background(itemBg)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${category.icon} ",
                            fontSize = 13.sp
                        )
                        Text(
                            text = category.displayName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = itemTextColor
                        )
                    }
                }
            }

            Divider(Orientation.Vertical)

            // Right Content Pane
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(20.dp)
            ) {
                when (activeCategory) {
                    SettingsCategory.GENERAL -> GeneralSettingsTab(
                        restoreSessionOnStartup = restoreSessionOnStartup,
                        onRestoreSessionOnStartupChange = { restoreSessionOnStartup = it }
                    )
                    SettingsCategory.APPEARANCE -> AppearanceSettingsTab(
                        themeName = themeName,
                        onThemeNameChange = { themeName = it },
                        isMinimapEnabled = isMinimapEnabled,
                        onMinimapEnabledChange = { isMinimapEnabled = it },
                        minimapWidthDp = minimapWidthDp,
                        onMinimapWidthDpChange = { minimapWidthDp = it },
                        isSearchHighlightingEnabled = isSearchHighlightingEnabled,
                        onSearchHighlightingEnabledChange = { isSearchHighlightingEnabled = it }
                    )
                    SettingsCategory.FONTS -> FontsSettingsTab(
                        editorFontFamily = editorFontFamily,
                        onEditorFontFamilyChange = { editorFontFamily = it },
                        editorFontSizePt = editorFontSizePt,
                        onEditorFontSizePtChange = { editorFontSizePt = it },
                        lineHeightMultiplier = lineHeightMultiplier,
                        onLineHeightMultiplierChange = { lineHeightMultiplier = it },
                        editorFontWeight = editorFontWeight,
                        onEditorFontWeightChange = { editorFontWeight = it },
                        uiFontFamily = uiFontFamily,
                        onUiFontFamilyChange = { uiFontFamily = it },
                        uiFontSizePt = uiFontSizePt,
                        onUiFontSizePtChange = { uiFontSizePt = it },
                        enableLigatures = enableLigatures,
                        onEnableLigaturesChange = { enableLigatures = it }
                    )
                    SettingsCategory.VIEW -> ViewSettingsTab(
                        viewportMode = viewportMode,
                        onViewportModeChange = { viewportMode = it },
                        showLineNumbers = showLineNumbers,
                        onShowLineNumbersChange = { showLineNumbers = it },
                        showWhitespaceChars = showWhitespaceChars,
                        onShowWhitespaceCharsChange = { showWhitespaceChars = it },
                        statusBarShowPosition = statusBarShowPosition,
                        onStatusBarShowPositionChange = { statusBarShowPosition = it },
                        statusBarShowDocumentStats = statusBarShowDocumentStats,
                        onStatusBarShowDocumentStatsChange = { statusBarShowDocumentStats = it },
                        statusBarShowMode = statusBarShowMode,
                        onStatusBarShowModeChange = { statusBarShowMode = it },
                        statusBarShowIndexingDetails = statusBarShowIndexingDetails,
                        onStatusBarShowIndexingDetailsChange = { statusBarShowIndexingDetails = it },
                        statusBarShowMemory = statusBarShowMemory,
                        onStatusBarShowMemoryChange = { statusBarShowMemory = it },
                        restoreSessionOnStartup = restoreSessionOnStartup,
                        onRestoreSessionOnStartupChange = { restoreSessionOnStartup = it },
                        commandPaletteStyle = commandPaletteStyle,
                        onCommandPaletteStyleChange = { commandPaletteStyle = it },
                        enableDoubleShiftTrigger = enableDoubleShiftTrigger,
                        onEnableDoubleShiftTriggerChange = { enableDoubleShiftTrigger = it },
                        enableVimColonTrigger = enableVimColonTrigger,
                        onEnableVimColonTriggerChange = { enableVimColonTrigger = it },
                        commandPaletteShowShortcuts = commandPaletteShowShortcuts,
                        onCommandPaletteShowShortcutsChange = { commandPaletteShowShortcuts = it }
                    )
                    SettingsCategory.LANGUAGES -> LanguagesSettingsTab(
                        activeLanguageProfileId = activeLanguageProfileId,
                        onActiveLanguageProfileIdChange = { activeLanguageProfileId = it },
                        customErrorKeywords = customErrorKeywords,
                        onCustomErrorKeywordsChange = { customErrorKeywords = it }
                    )
                    SettingsCategory.PERFORMANCE -> PerformanceSettingsTab(
                        enableSimdAcceleration = enableSimdAcceleration,
                        onEnableSimdAccelerationChange = { enableSimdAcceleration = it },
                        maxShapedCharsPerLine = maxShapedCharsPerLine,
                        onMaxShapedCharsPerLineChange = { maxShapedCharsPerLine = it },
                        isLatencyGapHighlightingEnabled = isLatencyGapHighlightingEnabled,
                        onIsLatencyGapHighlightingEnabledChange = { isLatencyGapHighlightingEnabled = it },
                        latencyGapThresholdMs = latencyGapThresholdMs,
                        onLatencyGapThresholdMsChange = { latencyGapThresholdMs = it },
                        latencyGapSevereThresholdMs = latencyGapSevereThresholdMs,
                        onLatencyGapSevereThresholdMsChange = { latencyGapSevereThresholdMs = it }
                    )
                    SettingsCategory.AI_MCP -> AiMcpSettingsTab(
                        isMcpServerEnabled = isMcpServerEnabled,
                        onIsMcpServerEnabledChange = { isMcpServerEnabled = it },
                        mcpServerPort = mcpServerPort,
                        onMcpServerPortChange = { mcpServerPort = it },
                        aiProvider = aiProvider,
                        onAiProviderChange = { aiProvider = it },
                        aiModelName = aiModelName,
                        onAiModelNameChange = { aiModelName = it },
                        aiEndpointUrl = aiEndpointUrl,
                        onAiEndpointUrlChange = { aiEndpointUrl = it },
                        aiApiKey = aiApiKey,
                        onAiApiKeyChange = { aiApiKey = it }
                    )
                    SettingsCategory.PLUGINS -> PluginsSettingsTab()
                }
            }
        }
    }
}
