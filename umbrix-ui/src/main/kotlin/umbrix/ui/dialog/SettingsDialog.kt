package umbrix.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import umbrix.ui.components.UmbrixModalCard
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.Badge
import umbrix.ui.settings.CommandPaletteStyle
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.EditorTypography
import umbrix.ui.theme.FontSpec
import umbrix.ui.theme.ThemeRegistry
import umbrix.ui.theme.UmbrixTheme

private enum class SettingsCategory(val displayName: String, val icon: String) {
    APPEARANCE("Appearance & Minimap", "🗺"),
    FONTS("Fonts & Typography", "🔤"),
    VIEW("View & Viewport", "👁"),
    LANGUAGES("Log Languages & Profiles", "🌐"),
    PERFORMANCE("Performance & SIMD", "⚡"),
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
    var activeCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE) }

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
        enableDoubleShiftTrigger = enableDoubleShiftTrigger
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
        // --- Two-Pane Body ---
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
                            SettingsCategory.APPEARANCE -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "Appearance & Themes",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    // Theme Selection Section
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Color Theme Preset",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            // Dark themes column
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "Dark Themes",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = UmbrixTheme.colors.textMuted
                                                )
                                                for (preset in ThemeRegistry.darkThemes()) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { themeName = preset.name }
                                                            .padding(vertical = 3.dp)
                                                    ) {
                                                        RadioButton(
                                                            selected = (themeName == preset.name),
                                                            onClick = { themeName = preset.name }
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = preset.displayName,
                                                            fontSize = 12.sp,
                                                            color = if (themeName == preset.name) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                                                        )
                                                    }
                                                }
                                            }

                                            // Light themes column
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "Light Themes",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = UmbrixTheme.colors.textMuted
                                                )
                                                for (preset in ThemeRegistry.lightThemes()) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { themeName = preset.name }
                                                            .padding(vertical = 3.dp)
                                                    ) {
                                                        RadioButton(
                                                            selected = (themeName == preset.name),
                                                            onClick = { themeName = preset.name }
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = preset.displayName,
                                                            fontSize = 12.sp,
                                                            color = if (themeName == preset.name) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    Text(
                                        text = "Viewport & Minimap",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isMinimapEnabled,
                                            onCheckedChange = { isMinimapEnabled = it }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Enable GPU Seismograph Minimap (Density radar)")
                                    }

                                    if (isMinimapEnabled) {
                                        Column(modifier = Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "Minimap Strip Width:",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textSecondary
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                for (widthVal in listOf(32, 48, 64, 80)) {
                                                    OutlinedButton(
                                                        onClick = { minimapWidthDp = widthVal },
                                                        enabled = minimapWidthDp != widthVal
                                                    ) {
                                                        Text("${widthVal}px")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isSearchHighlightingEnabled,
                                            onCheckedChange = { isSearchHighlightingEnabled = it }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Enable Search Match Highlighting (Needle pills)")
                                    }
                                }
                            }

                            SettingsCategory.FONTS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "Editor Typography",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    // 1. Editor Font
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Editor Font Family",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )
                                        Dropdown(
                                            modifier = Modifier.width(300.dp),
                                            menuContent = {
                                                for (font in EditorTypography.availableSystemMonospaceFonts) {
                                                    selectableItem(
                                                        selected = (editorFontFamily.equals(font, ignoreCase = true)),
                                                        onClick = { editorFontFamily = font }
                                                    ) {
                                                        Text(font)
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(editorFontFamily)
                                        }
                                    }

                                    // 2. Font Size
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Font Size",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(
                                                onClick = {
                                                    editorFontSizePt = (editorFontSizePt - 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
                                                },
                                                enabled = editorFontSizePt > FontSpec.MIN_FONT_SIZE
                                            ) {
                                                Text("-", fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "${String.format("%.1f", editorFontSizePt)} pt",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    editorFontSizePt = (editorFontSizePt + 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
                                                },
                                                enabled = editorFontSizePt < FontSpec.MAX_FONT_SIZE
                                            ) {
                                                Text("+", fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = "(${FontSpec.MIN_FONT_SIZE.toInt()} pt – ${FontSpec.MAX_FONT_SIZE.toInt()} pt)",
                                                fontSize = 11.sp,
                                                color = UmbrixTheme.colors.textMuted
                                            )
                                        }
                                    }

                                    // 3. Line Height
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Line Height Multiplier",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Slider(
                                                value = lineHeightMultiplier,
                                                onValueChange = { newVal ->
                                                    lineHeightMultiplier = (Math.round(newVal * 20.0f) / 20.0f).coerceIn(FontSpec.MIN_LINE_HEIGHT_MULT, FontSpec.MAX_LINE_HEIGHT_MULT)
                                                },
                                                valueRange = FontSpec.MIN_LINE_HEIGHT_MULT..FontSpec.MAX_LINE_HEIGHT_MULT,
                                                modifier = Modifier.width(220.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = "${String.format("%.2f", lineHeightMultiplier)}×",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "(${FontSpec.MIN_LINE_HEIGHT_MULT}× – ${FontSpec.MAX_LINE_HEIGHT_MULT}×)",
                                                fontSize = 11.sp,
                                                color = UmbrixTheme.colors.textMuted
                                            )
                                        }
                                    }

                                    // 4. Font Weight
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Font Weight",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            listOf(
                                                300 to "Light (300)",
                                                400 to "Regular (400)",
                                                500 to "Medium (500)",
                                                700 to "Bold (700)"
                                            ).forEach { (weightVal, label) ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.clickable { editorFontWeight = weightVal }
                                                ) {
                                                    RadioButton(
                                                        selected = (editorFontWeight == weightVal),
                                                        onClick = { editorFontWeight = weightVal }
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = label,
                                                        fontSize = 12.sp,
                                                        color = if (editorFontWeight == weightVal) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 5. Ligatures
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = enableLigatures,
                                                onCheckedChange = { enableLigatures = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Enable OpenType ligatures (!=, ->, ==, >=)",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    // 6. UI Chrome Typography
                                    Text(
                                        text = "UI Chrome Typography",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "UI Font Family",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )
                                        Dropdown(
                                            modifier = Modifier.width(300.dp),
                                            menuContent = {
                                                for (font in EditorTypography.availableSystemProportionalFonts) {
                                                    selectableItem(
                                                        selected = (uiFontFamily.equals(font, ignoreCase = true)),
                                                        onClick = { uiFontFamily = font }
                                                    ) {
                                                        Text(font)
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(uiFontFamily)
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "UI Font Size",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(
                                                onClick = {
                                                    uiFontSizePt = (uiFontSizePt - 1.0f).coerceIn(FontSpec.MIN_UI_FONT_SIZE, FontSpec.MAX_UI_FONT_SIZE)
                                                },
                                                enabled = uiFontSizePt > FontSpec.MIN_UI_FONT_SIZE
                                            ) {
                                                Text("-", fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "${String.format("%.1f", uiFontSizePt)} pt",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    uiFontSizePt = (uiFontSizePt + 1.0f).coerceIn(FontSpec.MIN_UI_FONT_SIZE, FontSpec.MAX_UI_FONT_SIZE)
                                                },
                                                enabled = uiFontSizePt < FontSpec.MAX_UI_FONT_SIZE
                                            ) {
                                                Text("+", fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = "(${FontSpec.MIN_UI_FONT_SIZE.toInt()} pt – ${FontSpec.MAX_UI_FONT_SIZE.toInt()} pt)",
                                                fontSize = 11.sp,
                                                color = UmbrixTheme.colors.textMuted
                                            )
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    // 7. Live Preview
                                    Text(
                                        text = "Live Preview",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    val previewFontFamily = remember(editorFontFamily) {
                                        EditorTypography.resolvedEditorFontFamily(editorFontFamily)
                                    }
                                    val previewFontWeight = remember(editorFontWeight) {
                                        FontSpec(editorFontWeight = editorFontWeight).resolvedEditorWeight
                                    }
                                    val previewFeatureSettings = remember(enableLigatures) {
                                        EditorTypography.resolveFontFeatureSettings(enableLigatures)
                                    }
                                    val previewSample = "2026-09-23 14:32:01.123 [main] INFO  com.app.Service - Starting application\n" +
                                        "2026-09-23 14:32:01.456 [pool-3] ERROR com.app.Handler - NullPointerException\n" +
                                        "    at com.app.Handler.process(Handler.java:42)\n" +
                                        "2026-09-23 14:32:02.789 [worker-1] WARN  com.app.Queue - queue.size() >= 5000 -> latency != 0ms"

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(UmbrixTheme.colors.surfaceGutter, shape = RoundedCornerShape(6.dp))
                                            .border(1.dp, UmbrixTheme.colors.border, shape = RoundedCornerShape(6.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = previewSample,
                                            fontFamily = previewFontFamily,
                                            fontSize = editorFontSizePt.sp,
                                            fontWeight = previewFontWeight,
                                            style = TextStyle(
                                                fontFeatureSettings = previewFeatureSettings,
                                                lineHeight = (editorFontSizePt * lineHeightMultiplier).sp
                                            ),
                                            color = UmbrixTheme.colors.textEditor
                                        )
                                    }
                                }
                            }

                            SettingsCategory.VIEW -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "Viewport & Layout",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    // 1. Viewport Layout Mode
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Viewport Layout Mode",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )

                                        val modes = listOf(
                                            Triple(ViewportLayoutMode.EXPANDABLE, "Expandable (Default)", "20px base rows with dropdown chevrons [⯈] to inline multi-line stack traces"),
                                            Triple(ViewportLayoutMode.COMPACT, "Compact Fixed Mode", "Strict 20px rows, maximum 60–120 FPS performance, horizontal scrolling"),
                                            Triple(ViewportLayoutMode.FULL_WRAP, "Full Wrap Mode", "Soft line-wrapping within viewport width with 4,096 char safety clamp")
                                        )

                                        for ((mode, title, desc) in modes) {
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { viewportMode = mode }
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                RadioButton(
                                                    selected = (viewportMode == mode),
                                                    onClick = { viewportMode = mode }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = title,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (viewportMode == mode) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                                                    )
                                                    Text(
                                                        text = desc,
                                                        fontSize = 11.sp,
                                                        color = UmbrixTheme.colors.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    // 2. Gutter & Text Rendering
                                    Text(
                                        text = "Gutter & Text Visualization",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { showLineNumbers = !showLineNumbers }
                                        ) {
                                            Checkbox(
                                                checked = showLineNumbers,
                                                onCheckedChange = { showLineNumbers = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "Show 64-bit Line Numbers",
                                                    fontSize = 12.sp,
                                                    color = UmbrixTheme.colors.textPrimary
                                                )
                                                Text(
                                                    text = "Renders 1-indexed physical or virtual line numbers in the viewport gutter",
                                                    fontSize = 11.sp,
                                                    color = UmbrixTheme.colors.textMuted
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { showWhitespaceChars = !showWhitespaceChars }
                                        ) {
                                            Checkbox(
                                                checked = showWhitespaceChars,
                                                onCheckedChange = { showWhitespaceChars = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "Show Whitespace Glyphs",
                                                    fontSize = 12.sp,
                                                    color = UmbrixTheme.colors.textPrimary
                                                )
                                                Text(
                                                    text = "Renders subtle visible indicators for space (·) and tab (→) characters",
                                                    fontSize = 11.sp,
                                                    color = UmbrixTheme.colors.textMuted
                                                )
                                            }
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    // 3. Status Bar Telemetry Customization
                                    Text(
                                        text = "Status Bar Telemetry",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { statusBarShowPosition = !statusBarShowPosition }
                                        ) {
                                            Checkbox(
                                                checked = statusBarShowPosition,
                                                onCheckedChange = { statusBarShowPosition = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Show Line & Cursor Position",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { statusBarShowDocumentStats = !statusBarShowDocumentStats }
                                        ) {
                                            Checkbox(
                                                checked = statusBarShowDocumentStats,
                                                onCheckedChange = { statusBarShowDocumentStats = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Show Document Total Lines & File Size",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { statusBarShowMode = !statusBarShowMode }
                                        ) {
                                            Checkbox(
                                                checked = statusBarShowMode,
                                                onCheckedChange = { statusBarShowMode = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Show Viewport Layout Mode",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { statusBarShowIndexingDetails = !statusBarShowIndexingDetails }
                                        ) {
                                            Checkbox(
                                                checked = statusBarShowIndexingDetails,
                                                onCheckedChange = { statusBarShowIndexingDetails = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Show SIMD Vector Width & Indexing Telemetry",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { statusBarShowMemory = !statusBarShowMemory }
                                        ) {
                                            Checkbox(
                                                checked = statusBarShowMemory,
                                                onCheckedChange = { statusBarShowMemory = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Show JVM Heap Memory & GC Invariant Telemetry",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    // 4. Command Palette & Workflow Persona
                                    Text(
                                        text = "Command Palette & Persona",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Palette Style & Shortcuts",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = UmbrixTheme.colors.textSecondary
                                        )

                                        val styles = listOf(
                                            Triple(CommandPaletteStyle.OMNI, "Unified Omni (Default)", "Intelligently auto-detects '>', ':', '@', '#' prefixes. Full fuzzy matching across all categories."),
                                            Triple(CommandPaletteStyle.VSCODE, "VS Code Style", "Ctrl+Shift+P opens '>' actions prompt; Ctrl+P opens quick file open. Compact single-line input."),
                                            Triple(CommandPaletteStyle.INTELLIJ, "IntelliJ Search Everywhere", "Double Shift opens full search with category filter pills [All] [Actions] [Files] [Settings]."),
                                            Triple(CommandPaletteStyle.VIM, "Vim / Linux Ex-Commands", "Modal ':' prompt with command line preview, ex-commands (:w, :q, :line), and status indicator.")
                                        )

                                        for ((style, title, desc) in styles) {
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { commandPaletteStyle = style }
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                RadioButton(
                                                    selected = (commandPaletteStyle == style),
                                                    onClick = { commandPaletteStyle = style }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = title,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (commandPaletteStyle == style) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                                                    )
                                                    Text(
                                                        text = desc,
                                                        fontSize = 11.sp,
                                                        color = UmbrixTheme.colors.textMuted
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { enableDoubleShiftTrigger = !enableDoubleShiftTrigger }
                                        ) {
                                            Checkbox(
                                                checked = enableDoubleShiftTrigger,
                                                onCheckedChange = { enableDoubleShiftTrigger = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "Enable Double-Shift Trigger",
                                                    fontSize = 12.sp,
                                                    color = UmbrixTheme.colors.textPrimary
                                                )
                                                Text(
                                                    text = "Quickly pressing Shift twice within 400ms opens the Command Palette",
                                                    fontSize = 11.sp,
                                                    color = UmbrixTheme.colors.textMuted
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { enableVimColonTrigger = !enableVimColonTrigger }
                                        ) {
                                            Checkbox(
                                                checked = enableVimColonTrigger,
                                                onCheckedChange = { enableVimColonTrigger = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "Enable Vim ':' Trigger in Viewport",
                                                    fontSize = 12.sp,
                                                    color = UmbrixTheme.colors.textPrimary
                                                )
                                                Text(
                                                    text = "Pressing ':' while viewing logs opens the Command Palette in Ex-command mode",
                                                    fontSize = 11.sp,
                                                    color = UmbrixTheme.colors.textMuted
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { commandPaletteShowShortcuts = !commandPaletteShowShortcuts }
                                        ) {
                                            Checkbox(
                                                checked = commandPaletteShowShortcuts,
                                                onCheckedChange = { commandPaletteShowShortcuts = it }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Show Keyboard Shortcuts in Command Results",
                                                fontSize = 12.sp,
                                                color = UmbrixTheme.colors.textPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            SettingsCategory.LANGUAGES -> {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(
                                        text = "Log Language Profiles & Exception Formatting",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )
                                    Text(
                                        text = "Configures multi-line stack trace folding and error severity keywords.",
                                        fontSize = 12.sp,
                                        color = UmbrixTheme.colors.textMuted
                                    )

                                    val profiles = listOf(
                                        "auto" to "Auto-Detect Format (Recommended)",
                                        "java" to "Java / JVM (Logback, Log4j2, Spring Boot)",
                                        "python" to "Python (traceback, logging)",
                                        "go" to "Go (slog, zap, panic)",
                                        "rust" to "Rust (tracing, env_logger)",
                                        "syslog" to "Syslog & Generic Plaintext"
                                    )

                                    for ((id, label) in profiles) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { activeLanguageProfileId = id }
                                        ) {
                                            RadioButton(
                                                selected = activeLanguageProfileId == id,
                                                onClick = { activeLanguageProfileId = id }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(label, fontSize = 13.sp)
                                        }
                                    }

                                    Divider(Orientation.Horizontal)

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Custom Error Keywords (comma separated):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                                        TextField(
                                            value = customErrorKeywords,
                                            onValueChange = { customErrorKeywords = it },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            SettingsCategory.PERFORMANCE -> {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        text = "Performance, SIMD & Memory Invariants",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Badge(
                                            label = "Hardware Vector Acceleration Active",
                                            textColor = UmbrixTheme.colors.badgeSimdText,
                                            backgroundColor = UmbrixTheme.colors.badgeSimdBackground
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = enableSimdAcceleration,
                                            onCheckedChange = { enableSimdAcceleration = it }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Enable Java Vector API SIMD (AVX-512 / AVX2)")
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Max Characters Per Line (Safety Clamp):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            for (limit in listOf(2048, 4096, 8192)) {
                                                OutlinedButton(
                                                    onClick = { maxShapedCharsPerLine = limit },
                                                    enabled = maxShapedCharsPerLine != limit
                                                ) {
                                                    Text("${limit} chars")
                                                }
                                            }
                                        }
                                        Text(
                                            text = "Protects Skia text shaper from multi-megabyte minified payloads.",
                                            fontSize = 11.sp,
                                            color = UmbrixTheme.colors.textMuted
                                        )
                                    }

                                    Divider(Orientation.Horizontal)

                                    Text(
                                        text = "Temporal Navigation & Latency Gaps",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isLatencyGapHighlightingEnabled,
                                            onCheckedChange = { isLatencyGapHighlightingEnabled = it }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Highlight Latency Gaps in Viewport Gutter (Amber/Red dots)")
                                    }

                                    if (isLatencyGapHighlightingEnabled) {
                                        Column(modifier = Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Warning Gap Threshold (Amber):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                for (ms in listOf(500L, 1000L, 2000L, 5000L)) {
                                                    OutlinedButton(
                                                        onClick = { latencyGapThresholdMs = ms },
                                                        enabled = latencyGapThresholdMs != ms
                                                    ) {
                                                        Text("${ms}ms")
                                                    }
                                                }
                                            }

                                            Text("Severe Gap Threshold (Red Outage):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                for (ms in listOf(3000L, 5000L, 10000L)) {
                                                    OutlinedButton(
                                                        onClick = { latencyGapSevereThresholdMs = ms },
                                                        enabled = latencyGapSevereThresholdMs != ms
                                                    ) {
                                                        Text("${ms}ms")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            SettingsCategory.PLUGINS -> {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        text = "Async Analyzers & Plugins SPI",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixTheme.colors.textPrimary
                                    )
                                    Text(
                                        text = "Background analysis extensions that run asynchronously without blocking the viewport.",
                                        fontSize = 12.sp,
                                        color = UmbrixTheme.colors.textMuted
                                    )

                                    PluginCard(
                                        name = "GPU Seismograph Minimap",
                                        status = "Active (Built-in)",
                                        description = "Real-time 1,000-bin severity density mipmap with zero GC heap ingestion."
                                    )

                                    PluginCard(
                                        name = "Latency Gap & Stall Detector",
                                        status = "Active (Built-in)",
                                        description = "Detects unlogged execution freezes and thread starvation gaps exceeding 1,000ms."
                                    )

                                    PluginCard(
                                        name = "JVM Garbage Collection Analyzer",
                                        status = "Planned",
                                        description = "Inspects GC pause distribution, allocation rates, and stop-the-world spikes."
                                    )
                                }
                            }
                        }
                    }
                }

        }
    }

@Composable
private fun PluginCard(
    name: String,
    status: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfaceElevated, shape = RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = UmbrixTheme.colors.textPrimary)
            Spacer(modifier = Modifier.weight(1f))
            Badge(
                label = status,
                textColor = if (status.startsWith("Active")) UmbrixTheme.colors.badgeSimdText else UmbrixTheme.colors.textMuted,
                backgroundColor = if (status.startsWith("Active")) UmbrixTheme.colors.badgeSimdBackground else UmbrixTheme.colors.surfaceGutter
            )
        }
        Text(description, fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
    }
}
