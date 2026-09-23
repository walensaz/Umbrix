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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.Badge
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.UmbrixColors

private enum class SettingsCategory(val displayName: String, val icon: String) {
    APPEARANCE("Appearance & Minimap", "🗺"),
    LANGUAGES("Log Languages & Profiles", "🔤"),
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

    var isMinimapEnabled by remember { mutableStateOf(initialSettings.isMinimapEnabled) }
    var minimapWidthDp by remember { mutableStateOf(initialSettings.minimapWidthDp) }
    var isSearchHighlightingEnabled by remember { mutableStateOf(initialSettings.isSearchHighlightingEnabled) }
    var activeLanguageProfileId by remember { mutableStateOf(initialSettings.activeLanguageProfileId) }
    var customErrorKeywords by remember { mutableStateOf(initialSettings.customErrorKeywords) }
    var customWarnKeywords by remember { mutableStateOf(initialSettings.customWarnKeywords) }
    var maxShapedCharsPerLine by remember { mutableStateOf(initialSettings.maxShapedCharsPerLine) }
    var enableSimdAcceleration by remember { mutableStateOf(initialSettings.enableSimdAcceleration) }

    fun buildCurrent(): WorkstationSettings = WorkstationSettings(
        isMinimapEnabled = isMinimapEnabled,
        minimapWidthDp = minimapWidthDp,
        isSearchHighlightingEnabled = isSearchHighlightingEnabled,
        activeLanguageProfileId = activeLanguageProfileId,
        customErrorKeywords = customErrorKeywords,
        customWarnKeywords = customWarnKeywords,
        maxShapedCharsPerLine = maxShapedCharsPerLine,
        enableSimdAcceleration = enableSimdAcceleration
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .size(780.dp, 520.dp)
                    .background(UmbrixColors.surface, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, UmbrixColors.surfaceElevated, shape = RoundedCornerShape(8.dp))
            ) {
                // --- Dialog Header ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UmbrixColors.surfacePanel)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UmbrixColors.textPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        color = UmbrixColors.textMuted,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss
                            )
                            .padding(4.dp)
                    )
                }

                Divider(Orientation.Horizontal)

                // --- Two-Pane Body ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Left Sidebar Navigation
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .background(UmbrixColors.surfaceGutter)
                            .padding(vertical = 8.dp)
                    ) {
                        for (category in SettingsCategory.values()) {
                            val isSelected = activeCategory == category
                            val itemBg = if (isSelected) UmbrixColors.surfaceElevated else Color.Transparent
                            val itemTextColor = if (isSelected) UmbrixColors.textPrimary else UmbrixColors.textSecondary

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
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        text = "Appearance & Seismograph Minimap",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixColors.textPrimary
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
                                                color = UmbrixColors.textSecondary
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
                                        Text("Enable Search Match Highlighting (Yellow spans)")
                                    }
                                }
                            }

                            SettingsCategory.LANGUAGES -> {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(
                                        text = "Log Language Profiles & Exception Formatting",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixColors.textPrimary
                                    )
                                    Text(
                                        text = "Configures multi-line stack trace folding and error severity keywords.",
                                        fontSize = 12.sp,
                                        color = UmbrixColors.textMuted
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
                                        Text("Custom Error Keywords (comma separated):", fontSize = 12.sp, color = UmbrixColors.textSecondary)
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
                                        color = UmbrixColors.textPrimary
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Badge(
                                            label = "Hardware Vector Acceleration Active",
                                            textColor = UmbrixColors.badgeSimdText,
                                            backgroundColor = UmbrixColors.badgeSimdBackground
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
                                        Text("Max Characters Per Line (Safety Clamp):", fontSize = 12.sp, color = UmbrixColors.textSecondary)
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
                                            color = UmbrixColors.textMuted
                                        )
                                    }
                                }
                            }

                            SettingsCategory.PLUGINS -> {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        text = "Async Analyzers & Plugins SPI",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = UmbrixColors.textPrimary
                                    )
                                    Text(
                                        text = "Background analysis extensions that run asynchronously without blocking the viewport.",
                                        fontSize = 12.sp,
                                        color = UmbrixColors.textMuted
                                    )

                                    PluginCard(
                                        name = "GPU Seismograph Minimap",
                                        status = "Active (Built-in)",
                                        description = "Real-time 1,000-bin severity density mipmap with zero GC heap ingestion."
                                    )

                                    PluginCard(
                                        name = "JVM Garbage Collection Analyzer",
                                        status = "Planned",
                                        description = "Inspects GC pause distribution, allocation rates, and stop-the-world spikes."
                                    )

                                    PluginCard(
                                        name = "Latency Gap & Stall Detector",
                                        status = "Planned",
                                        description = "Detects unlogged execution freezes and thread starvation gaps exceeding 1,000ms."
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(Orientation.Horizontal)

                // --- Dialog Actions Footer ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UmbrixColors.surfacePanel)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            .background(UmbrixColors.surfaceElevated, shape = RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = UmbrixColors.textPrimary)
            Spacer(modifier = Modifier.weight(1f))
            Badge(
                label = status,
                textColor = if (status.startsWith("Active")) UmbrixColors.badgeSimdText else UmbrixColors.textMuted,
                backgroundColor = if (status.startsWith("Active")) UmbrixColors.badgeSimdBackground else UmbrixColors.surfaceGutter
            )
        }
        Text(description, fontSize = 12.sp, color = UmbrixColors.textSecondary)
    }
}
