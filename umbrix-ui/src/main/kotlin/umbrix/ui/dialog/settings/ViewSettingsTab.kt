package umbrix.ui.dialog.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.settings.CommandPaletteStyle
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: Viewport layout modes, line numbering, status bar items, and command palette personas.
 */
@Composable
fun ViewSettingsTab(
    viewportMode: ViewportLayoutMode,
    onViewportModeChange: (ViewportLayoutMode) -> Unit,
    showLineNumbers: Boolean,
    onShowLineNumbersChange: (Boolean) -> Unit,
    showWhitespaceChars: Boolean,
    onShowWhitespaceCharsChange: (Boolean) -> Unit,
    statusBarShowPosition: Boolean,
    onStatusBarShowPositionChange: (Boolean) -> Unit,
    statusBarShowDocumentStats: Boolean,
    onStatusBarShowDocumentStatsChange: (Boolean) -> Unit,
    statusBarShowMode: Boolean,
    onStatusBarShowModeChange: (Boolean) -> Unit,
    statusBarShowIndexingDetails: Boolean,
    onStatusBarShowIndexingDetailsChange: (Boolean) -> Unit,
    statusBarShowMemory: Boolean,
    onStatusBarShowMemoryChange: (Boolean) -> Unit,
    restoreSessionOnStartup: Boolean,
    onRestoreSessionOnStartupChange: (Boolean) -> Unit,
    commandPaletteStyle: CommandPaletteStyle,
    onCommandPaletteStyleChange: (CommandPaletteStyle) -> Unit,
    enableDoubleShiftTrigger: Boolean,
    onEnableDoubleShiftTriggerChange: (Boolean) -> Unit,
    enableVimColonTrigger: Boolean,
    onEnableVimColonTriggerChange: (Boolean) -> Unit,
    commandPaletteShowShortcuts: Boolean,
    onCommandPaletteShowShortcutsChange: (Boolean) -> Unit
) {
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
                        .clickable { onViewportModeChange(mode) }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = (viewportMode == mode),
                        onClick = { onViewportModeChange(mode) }
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
                modifier = Modifier.clickable { onShowLineNumbersChange(!showLineNumbers) }
            ) {
                Checkbox(
                    checked = showLineNumbers,
                    onCheckedChange = onShowLineNumbersChange
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
                modifier = Modifier.clickable { onShowWhitespaceCharsChange(!showWhitespaceChars) }
            ) {
                Checkbox(
                    checked = showWhitespaceChars,
                    onCheckedChange = onShowWhitespaceCharsChange
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
                modifier = Modifier.clickable { onStatusBarShowPositionChange(!statusBarShowPosition) }
            ) {
                Checkbox(
                    checked = statusBarShowPosition,
                    onCheckedChange = onStatusBarShowPositionChange
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
                modifier = Modifier.clickable { onStatusBarShowDocumentStatsChange(!statusBarShowDocumentStats) }
            ) {
                Checkbox(
                    checked = statusBarShowDocumentStats,
                    onCheckedChange = onStatusBarShowDocumentStatsChange
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
                modifier = Modifier.clickable { onStatusBarShowModeChange(!statusBarShowMode) }
            ) {
                Checkbox(
                    checked = statusBarShowMode,
                    onCheckedChange = onStatusBarShowModeChange
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
                modifier = Modifier.clickable { onStatusBarShowIndexingDetailsChange(!statusBarShowIndexingDetails) }
            ) {
                Checkbox(
                    checked = statusBarShowIndexingDetails,
                    onCheckedChange = onStatusBarShowIndexingDetailsChange
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
                modifier = Modifier.clickable { onStatusBarShowMemoryChange(!statusBarShowMemory) }
            ) {
                Checkbox(
                    checked = statusBarShowMemory,
                    onCheckedChange = onStatusBarShowMemoryChange
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

        // 4. Workstation Session Restoration
        Text(
            text = "Session & Multi-Document Tabs",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onRestoreSessionOnStartupChange(!restoreSessionOnStartup) }
        ) {
            Checkbox(
                checked = restoreSessionOnStartup,
                onCheckedChange = onRestoreSessionOnStartupChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Restore Previous Session on Startup",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
                Text(
                    text = "Automatically re-opens previously open log files and active scratchpad buffer on launch",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }

        Divider(Orientation.Horizontal)

        // 5. Command Palette & Workflow Persona
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
                        .clickable { onCommandPaletteStyleChange(style) }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = (commandPaletteStyle == style),
                        onClick = { onCommandPaletteStyleChange(style) }
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
                modifier = Modifier.clickable { onEnableDoubleShiftTriggerChange(!enableDoubleShiftTrigger) }
            ) {
                Checkbox(
                    checked = enableDoubleShiftTrigger,
                    onCheckedChange = onEnableDoubleShiftTriggerChange
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
                modifier = Modifier.clickable { onEnableVimColonTriggerChange(!enableVimColonTrigger) }
            ) {
                Checkbox(
                    checked = enableVimColonTrigger,
                    onCheckedChange = onEnableVimColonTriggerChange
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
                modifier = Modifier.clickable { onCommandPaletteShowShortcutsChange(!commandPaletteShowShortcuts) }
            ) {
                Checkbox(
                    checked = commandPaletteShowShortcuts,
                    onCheckedChange = onCommandPaletteShowShortcutsChange
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
