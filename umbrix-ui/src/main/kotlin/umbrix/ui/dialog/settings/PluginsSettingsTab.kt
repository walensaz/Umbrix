package umbrix.ui.dialog.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.core.plugin.InstalledPlugin
import umbrix.core.plugin.PluginManager
import umbrix.plugin.api.config.PluginSettingDescriptor
import umbrix.plugin.api.config.PluginSettingType
import umbrix.plugin.ui.ComposePluginSettingsPanel
import umbrix.ui.components.Badge
import umbrix.ui.settings.SettingsManager
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.toast.ToastManager
import umbrix.ui.util.showNativeJarOpenFileDialog
import java.awt.Desktop
import java.nio.file.Paths

/**
 * Settings tab: Async background analyzers and extension plugins SPI.
 */
@Composable
fun PluginsSettingsTab() {
    var reloadTrigger by remember { mutableStateOf(0) }
    val installedPlugins = remember(reloadTrigger) {
        try {
            PluginManager.instance().allInstalledPlugins()
        } catch (_: Throwable) {
            emptyList<InstalledPlugin>()
        }
    }
    val expandedSettings = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Plugins & Extensibility Architecture",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )
        Text(
            text = "Manage external plugin extensions, SIMD accelerators, custom binary format framers, document decorators, and companion dock views.",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textMuted
        )

        // Action Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DefaultButton(onClick = {
                val jarPath = showNativeJarOpenFileDialog()
                if (jarPath != null) {
                    try {
                        val manifest = PluginManager.instance().installPluginJar(Paths.get(jarPath))
                        reloadTrigger++
                        ToastManager.success("Installed plugin: ${manifest.name()} v${manifest.version()}")
                    } catch (t: Throwable) {
                        ToastManager.error("Failed to install plugin: ${t.message}")
                    }
                }
            }) {
                Text("Install Plugin from Disk (.jar)...")
            }

            OutlinedButton(onClick = {
                try {
                    val dir = PluginManager.instance().pluginsDirectory().toFile()
                    if (!dir.exists()) dir.mkdirs()
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(dir)
                    }
                } catch (t: Throwable) {
                    ToastManager.error("Could not open plugins folder: ${t.message}")
                }
            }) {
                Text("Open Plugins Folder")
            }

            OutlinedButton(onClick = {
                try {
                    val disabledIds = SettingsManager.currentSettings.disabledPluginIds
                    PluginManager.instance().reloadPlugins(scala.jdk.javaapi.CollectionConverters.asScala(disabledIds).toSet())
                    reloadTrigger++
                    ToastManager.info("Plugins reloaded")
                } catch (t: Throwable) {
                    ToastManager.error("Failed to reload plugins: ${t.message}")
                }
            }) {
                Text("Reload Plugins")
            }
        }

        // Section: Installed External Plugins
        Text(
            text = "Installed Third-Party Plugins (${installedPlugins.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = UmbrixTheme.colors.textPrimary
        )

        if (installedPlugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surfaceElevated, shape = RoundedCornerShape(6.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No third-party plugins installed in ~/.umbrix/plugins/ yet.",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textSubtle
                )
            }
        } else {
            for (pluginEntry in installedPlugins) {
                val manifest = pluginEntry.manifest()
                val isEnabled = pluginEntry.isEnabled()
                val isPendingRestart = pluginEntry.isPendingRestart()
                val isExpanded = expandedSettings[manifest.id()] ?: false

                val statusLabel = when {
                    isPendingRestart -> "Restart Required"
                    isEnabled -> "Active"
                    else -> "Disabled"
                }
                val statusTextColor = when {
                    isPendingRestart -> UmbrixTheme.colors.badgeWarningText
                    isEnabled -> UmbrixTheme.colors.badgeSimdText
                    else -> UmbrixTheme.colors.textMuted
                }
                val statusBgColor = when {
                    isPendingRestart -> UmbrixTheme.colors.badgeWarningBackground
                    isEnabled -> UmbrixTheme.colors.badgeSimdBackground
                    else -> UmbrixTheme.colors.surfaceGutter
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(6.dp))
                        .background(UmbrixTheme.colors.surfaceElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = manifest.name(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = UmbrixTheme.colors.textPrimary
                                )
                                Text(
                                    text = "v${manifest.version()}",
                                    fontSize = 11.sp,
                                    color = UmbrixTheme.colors.textMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (manifest.vendor().isNotEmpty()) {
                                Text(
                                    text = "by ${manifest.vendor()}",
                                    fontSize = 11.sp,
                                    color = UmbrixTheme.colors.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Badge(
                            label = statusLabel,
                            textColor = statusTextColor,
                            backgroundColor = statusBgColor
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(onClick = {
                            val currentDisabled = SettingsManager.currentSettings.disabledPluginIds
                            val updatedDisabled = if (isEnabled) {
                                currentDisabled + manifest.id()
                            } else {
                                currentDisabled - manifest.id()
                            }
                            SettingsManager.updateSettings(
                                SettingsManager.currentSettings.copy(disabledPluginIds = updatedDisabled)
                            )
                            reloadTrigger++
                        }) {
                            Text(if (isEnabled) "Disable" else "Enable", fontSize = 11.sp)
                        }
                    }

                    // Description
                    if (manifest.description().isNotEmpty()) {
                        Text(
                            text = manifest.description(),
                            fontSize = 12.sp,
                            color = UmbrixTheme.colors.textSecondary
                        )
                    }

                    // Capabilities Badges Row
                    val capabilities = manifest.capabilities()
                    if (capabilities != null && capabilities.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (cap in capabilities) {
                                Badge(
                                    label = cap.name,
                                    textColor = UmbrixTheme.colors.textSecondary,
                                    backgroundColor = UmbrixTheme.colors.surfaceGutter
                                )
                            }
                        }
                    }

                    // Configuration Drawer
                    val hasCustomPanel = pluginEntry.pluginInstance() is ComposePluginSettingsPanel
                    val schemaOpt = PluginManager.instance().getSettingsSchema(manifest.id())
                    val hasSchema = schemaOpt.isDefined && schemaOpt.get().isNotEmpty()

                    if (hasCustomPanel || hasSchema) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    expandedSettings[manifest.id()] = !isExpanded
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isExpanded) "▼ Hide Settings" else "▶ Configure Plugin Settings",
                                fontSize = 11.sp,
                                color = UmbrixTheme.colors.accentPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(UmbrixTheme.colors.surfaceGutter)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasCustomPanel) {
                                    val customPanel = pluginEntry.pluginInstance() as ComposePluginSettingsPanel
                                    customPanel.SettingsContent(pluginEntry.context().configStore())
                                } else if (hasSchema) {
                                    val descriptors = schemaOpt.get()
                                    val store = pluginEntry.context().configStore()
                                    for (desc in descriptors) {
                                        PluginSettingRow(desc, store)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Built-in Analyzers
        Text(
            text = "Built-in Forensic Analyzers",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = UmbrixTheme.colors.textPrimary
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

@Composable
private fun PluginSettingRow(
    descriptor: PluginSettingDescriptor,
    store: umbrix.plugin.api.config.PluginConfigStore
) {
    val key = descriptor.key()
    when (descriptor.settingType()) {
        PluginSettingType.BOOLEAN -> {
            var checked by remember(key) {
                mutableStateOf(store.getBoolean(key, descriptor.defaultValue().toBoolean()))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { newVal ->
                        checked = newVal
                        store.setBoolean(key, newVal)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(descriptor.label(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UmbrixTheme.colors.textPrimary)
                    if (descriptor.description().isNotEmpty()) {
                        Text(descriptor.description(), fontSize = 11.sp, color = UmbrixTheme.colors.textMuted)
                    }
                }
            }
        }
        else -> {
            var textValue by remember(key) {
                mutableStateOf(store.getString(key, descriptor.defaultValue()))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(descriptor.label(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UmbrixTheme.colors.textPrimary)
                if (descriptor.description().isNotEmpty()) {
                    Text(descriptor.description(), fontSize = 11.sp, color = UmbrixTheme.colors.textMuted)
                }
                TextField(
                    value = textValue,
                    onValueChange = { newVal ->
                        textValue = newVal
                        store.setString(key, newVal)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
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
