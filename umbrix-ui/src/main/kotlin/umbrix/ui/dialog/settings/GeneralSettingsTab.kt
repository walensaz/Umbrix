package umbrix.ui.dialog.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import org.jetbrains.jewel.ui.component.Text
import umbrix.index.PersistentIndexCache
import umbrix.ui.settings.SettingsManager
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.toast.ToastManager

/**
 * Settings tab: General & Workspace configuration, session restoration, and cache maintenance.
 */
@Composable
fun GeneralSettingsTab(
    restoreSessionOnStartup: Boolean,
    onRestoreSessionOnStartupChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "General & Workspace",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )

        // 1. Session Restoration Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Multi-Document Session & Startup",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
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
                        text = "Automatically re-opens previously open log files and active scratchpad buffer when launching Umbrix",
                        fontSize = 11.sp,
                        color = UmbrixTheme.colors.textMuted
                    )
                }
            }
        }

        Divider(Orientation.Horizontal)

        // 2. Scratchpad Buffers Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Virtual Scratchpads",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Text(
                text = "Scratchpad buffers are automatically persisted to ~/.umbrix/scratchpads/ so your investigation drafts and pinned snippets are never lost.",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textMuted
            )
        }

        Divider(Orientation.Horizontal)

        // 3. Persistent Index Disk Cache
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Persistent Sidecar Index Cache",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Text(
                text = "Coordinate indexes are cached in ~/.umbrix/cache/indexes/ allowing multi-gigabyte files to re-open instantly (<1ms). Caches unused for 7+ days are automatically cleaned on startup.",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textMuted
            )
            Row {
                OutlinedButton(onClick = {
                    val cleaned = PersistentIndexCache.cleanStaleCaches(0L)
                    ToastManager.info("Pruned $cleaned index cache files")
                }) {
                    Text("Clear Index Caches Now")
                }
            }
        }

        Divider(Orientation.Horizontal)

        // 4. Recent Files History
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Recent Files History",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Row {
                OutlinedButton(onClick = {
                    SettingsManager.clearRecentFiles()
                    ToastManager.info("Cleared recent files history")
                }) {
                    Text("Clear Recent Files History")
                }
            }
        }
    }
}
