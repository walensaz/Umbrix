package umbrix.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.session.LogSession
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatByteSize
import umbrix.ui.util.formatEpochMicros
import umbrix.ui.util.formatNumberWithCommas
import kotlin.math.max

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.menu.UmbrixDropdownMenu
import umbrix.ui.settings.SettingsManager

/**
 * Bottom workstation status bar displaying telemetry, viewport metrics, and coordinate positions.
 *
 * Features:
 * - Left: Active indexing progress bar or resting status message.
 * - Center: Dedicated Anomaly navigation pill with keyboard shortcuts (Shift+F4 / F4).
 * - Right: Clean, uncluttered document telemetry (position, line count, size).
 * - Right-Click Menu: Allows the operator to toggle visibility of any status bar section.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun BottomStatusBar(
    statusMessage: String,
    activeSession: LogSession?,
    topVisibleLine: Long = 0L,
    activeTab: WorkspaceTab? = null,
    onNavigateNextAnomaly: () -> Unit = {},
    onNavigatePrevAnomaly: () -> Unit = {}
) {
    var jvmHeapUsedBytes by remember {
        val runtime = Runtime.getRuntime()
        mutableStateOf(runtime.totalMemory() - runtime.freeMemory())
    }
    var isContextMenuOpen by remember { mutableStateOf(false) }
    val settings = SettingsManager.currentSettings

    LaunchedEffect(settings.statusBarShowMemory) {
        if (settings.statusBarShowMemory) {
            while (true) {
                delay(1000L)
                val runtime = Runtime.getRuntime()
                jvmHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(UmbrixTheme.colors.surfaceGutter)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    isContextMenuOpen = true
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- Left: Indexing Progress or Status Message ---
            if (activeTab != null && activeTab.isIndexing) {
                Text(
                    text = activeTab.indexingStatus,
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.progressText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(UmbrixTheme.colors.surfaceElevated)
                ) {
                    val progressFraction = (activeTab.indexingProgress / 100.0).toFloat().coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(UmbrixTheme.colors.progressGradientStart, UmbrixTheme.colors.progressGradientEnd)
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "${activeTab.indexingProgress.toInt()}%",
                    fontSize = 10.sp,
                    color = UmbrixTheme.colors.progressText,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            } else {
                Text(
                    text = statusMessage,
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // --- Center: Anomaly Navigation Pill ---
            if (activeSession != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(UmbrixTheme.colors.surfaceElevated)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Anomalies:",
                        fontSize = 10.sp,
                        color = UmbrixTheme.colors.textSecondary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Tooltip(
                        tooltip = {
                            Text("Jump to previous error or warning line (Shift+F4)")
                        }
                    ) {
                        Text(
                            text = "▲ Prev (Shift+F4)",
                            fontSize = 10.sp,
                            color = UmbrixTheme.colors.accentPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clickable(onClick = onNavigatePrevAnomaly)
                                .padding(horizontal = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Tooltip(
                        tooltip = {
                            Text("Jump to next error or warning line (F4)")
                        }
                    ) {
                        Text(
                            text = "▼ Next (F4)",
                            fontSize = 10.sp,
                            color = UmbrixTheme.colors.accentPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clickable(onClick = onNavigateNextAnomaly)
                                .padding(horizontal = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // --- Right: Configurable Document Telemetry ---
            val statusItems = mutableListOf<String>()

            if (settings.statusBarShowPosition) {
                val currentTopLine = activeTab?.topVisibleLine ?: topVisibleLine
                val positionText = if (activeTab?.selectedLineIndex != null) {
                    "Line ${formatNumberWithCommas(activeTab.selectedLineIndex!! + 1L)}"
                } else {
                    "Line ${formatNumberWithCommas(currentTopLine + 1L)}"
                }
                statusItems.add(positionText)
            }

            if (settings.statusBarShowDocumentStats && activeSession != null) {
                statusItems.add("${formatNumberWithCommas(activeSession.totalLines())} lines")
                statusItems.add(formatByteSize(activeSession.fileSizeBytes()))
            }

            if (settings.statusBarShowMode) {
                statusItems.add(settings.viewportMode.displayLabel)
                when {
                    activeTab?.isFilteredView == true -> {
                        statusItems.add("Filtered (${formatNumberWithCommas(activeTab.searchResult?.totalMatches() ?: 0L)} matches)")
                    }
                    activeTab?.isTemporalFocusActive == true -> {
                        val count = max(0L, activeTab.temporalFocusEndLine - activeTab.temporalFocusStartLine + 1L)
                        statusItems.add("Temporal Focus (${formatNumberWithCommas(count)} lines)")
                    }
                }
            }

            if (settings.statusBarShowIndexingDetails && activeSession != null) {
                statusItems.add("${activeSession.simdVectorWidth() * 8}-bit SIMD")
                statusItems.add("Index ${activeSession.indexingDurationMs()}ms")
            }

            if (settings.statusBarShowMemory) {
                statusItems.add("Heap: ${formatByteSize(jvmHeapUsedBytes)}")
            }

            Text(
                text = statusItems.joinToString("  |  "),
                fontSize = 11.sp,
                color = UmbrixTheme.colors.textDimmed,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        // --- Right-Click Customization Context Menu ---
        UmbrixDropdownMenu(
            expanded = isContextMenuOpen,
            onDismissRequest = { isContextMenuOpen = false }
        ) {
            StatusBarContextMenuItem(
                label = "Line Position",
                checked = settings.statusBarShowPosition,
                onToggle = {
                    SettingsManager.updateSettings(settings.copy(statusBarShowPosition = !settings.statusBarShowPosition))
                }
            )
            StatusBarContextMenuItem(
                label = "Document Size & Lines",
                checked = settings.statusBarShowDocumentStats,
                onToggle = {
                    SettingsManager.updateSettings(settings.copy(statusBarShowDocumentStats = !settings.statusBarShowDocumentStats))
                }
            )
            StatusBarContextMenuItem(
                label = "Mode Indicator",
                checked = settings.statusBarShowMode,
                onToggle = {
                    SettingsManager.updateSettings(settings.copy(statusBarShowMode = !settings.statusBarShowMode))
                }
            )
            StatusBarContextMenuItem(
                label = "Indexing & SIMD Telemetry",
                checked = settings.statusBarShowIndexingDetails,
                onToggle = {
                    SettingsManager.updateSettings(settings.copy(statusBarShowIndexingDetails = !settings.statusBarShowIndexingDetails))
                }
            )
            StatusBarContextMenuItem(
                label = "JVM Heap Memory",
                checked = settings.statusBarShowMemory,
                onToggle = {
                    SettingsManager.updateSettings(settings.copy(statusBarShowMemory = !settings.statusBarShowMemory))
                }
            )
        }
    }
}

@Composable
private fun StatusBarContextMenuItem(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (checked) "✓" else "  ",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (checked) UmbrixTheme.colors.accentPrimary else Color.Transparent
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = UmbrixTheme.colors.textPrimary
            )
        }
    }
}
