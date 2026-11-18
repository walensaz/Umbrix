package umbrix.ui.dialog.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.ThemeRegistry
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: Theme selection presets and Minimap configuration.
 */
@Composable
fun AppearanceSettingsTab(
    themeName: String,
    onThemeNameChange: (String) -> Unit,
    isMinimapEnabled: Boolean,
    onMinimapEnabledChange: (Boolean) -> Unit,
    minimapWidthDp: Int,
    onMinimapWidthDpChange: (Int) -> Unit,
    isSearchHighlightingEnabled: Boolean,
    onSearchHighlightingEnabledChange: (Boolean) -> Unit
) {
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
                                .clickable { onThemeNameChange(preset.name) }
                                .padding(vertical = 3.dp)
                        ) {
                            RadioButton(
                                selected = (themeName == preset.name),
                                onClick = { onThemeNameChange(preset.name) }
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
                                .clickable { onThemeNameChange(preset.name) }
                                .padding(vertical = 3.dp)
                        ) {
                            RadioButton(
                                selected = (themeName == preset.name),
                                onClick = { onThemeNameChange(preset.name) }
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
                onCheckedChange = onMinimapEnabledChange
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
                            onClick = { onMinimapWidthDpChange(widthVal) },
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
                onCheckedChange = onSearchHighlightingEnabledChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enable Search Match Highlighting (Needle pills)")
        }
    }
}
