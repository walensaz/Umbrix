package umbrix.ui.docking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.scratchpad.ScratchpadEditorCanvas
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.ThemeRegistry
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.viewport.LogViewportCanvas

/**
 * Host component rendering all detached tabs in secondary desktop OS windows.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetachedWindowsHost(
    detachedTabs: List<WorkspaceTab>,
    onReDockTab: (WorkspaceTab) -> Unit,
    onFilterByText: (String) -> Unit = {},
    onSearchText: (String) -> Unit = {},
    onSeekInScrubber: (Long) -> Unit = {},
    onExportStartingHere: (Long) -> Unit = {},
    onAddToTimeline: (Long, String) -> Unit = { _, _ -> },
    onAppendToScratchpad: (Long, String) -> Unit = { _, _ -> },
    onNewScratchpadFromSelection: (String) -> Unit = {},
    onOpenFile: (() -> Unit)? = null,
    onNewScratchpad: (() -> Unit)? = null
) {
    for (tab in detachedTabs) {
        key(tab.id) {
            val windowState = rememberWindowState(width = 960.dp, height = 700.dp)
            Window(
                onCloseRequest = { onReDockTab(tab) },
                state = windowState,
                title = "Umbrix — ${tab.title}",
                icon = umbrix.ui.icon.UmbrixIcon.painter,
                undecorated = false
            ) {
                DisposableEffect(window) {
                    umbrix.ui.icon.UmbrixIcon.applyToWindow(window)
                    onDispose {}
                }
                val currentTheme = ThemeRegistry.findByName(SettingsManager.currentSettings.themeName)
                    ?: ThemeRegistry.defaultTheme()

                UmbrixTheme(
                    theme = currentTheme,
                    fonts = SettingsManager.currentSettings.toFontSpec()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(UmbrixTheme.colors.surface)
                    ) {
                        // Detached Window Header with Re-dock button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(UmbrixTheme.colors.surfaceElevated)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = (if (tab.isScratchpad) "📝 " else "📄 ") + tab.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = UmbrixTheme.colors.textPrimary
                                )
                                tab.session?.let { s ->
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "${umbrix.ui.util.formatNumberWithCommas((s.totalLines() as Number).toLong())} lines",
                                        fontSize = 11.sp,
                                        color = UmbrixTheme.colors.textMuted
                                    )
                                }
                            }

                            Tooltip(tooltip = { Text("Re-dock this tab into main workstation window") }) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(UmbrixTheme.colors.surfacePanel)
                                        .clickable { onReDockTab(tab) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⤓", fontSize = 12.sp, color = UmbrixTheme.colors.accentPrimary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Re-dock Window",
                                        fontSize = 11.sp,
                                        color = UmbrixTheme.colors.textPrimary
                                    )
                                }
                            }
                        }

                        Divider(Orientation.Horizontal)

                        // Content Canvas
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (tab.isScratchpad) {
                                ScratchpadEditorCanvas(
                                    session = tab.scratchpadSession!!,
                                    onSaveRequested = {
                                        umbrix.ui.toast.ToastManager.success("Scratchpad saved")
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (tab.session != null) {
                                LogViewportCanvas(
                                    tab = tab,
                                    modifier = Modifier.fillMaxSize(),
                                    onFilterByText = onFilterByText,
                                    onSearchText = onSearchText,
                                    onSeekInScrubber = onSeekInScrubber,
                                    onExportStartingHere = onExportStartingHere,
                                    onAddToTimeline = onAddToTimeline,
                                    onAppendToScratchpad = onAppendToScratchpad,
                                    onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                                    onOpenFile = { onOpenFile?.invoke() },
                                    onNewScratchpad = { onNewScratchpad?.invoke() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
