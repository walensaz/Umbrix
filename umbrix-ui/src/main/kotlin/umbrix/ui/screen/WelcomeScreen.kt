package umbrix.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.settings.SettingsManager
import umbrix.ui.theme.UmbrixTheme
import java.io.File

/**
 * Modern, authentic workstation welcome dashboard displayed when no log session is active.
 *
 * Provides instant drag-and-drop targets, quick open actions, recent file history,
 * and essential forensic keyboard shortcuts.
 */
@Composable
fun WelcomeScreen(
    onOpenFileClicked: () -> Unit,
    onSelectRecentFile: (String) -> Unit,
    onClearRecentFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = UmbrixTheme.colors
    val recentFiles = SettingsManager.currentSettings.recentFiles

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // --- 1. Branding Header ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚡",
                        fontSize = 24.sp
                    )
                    Text(
                        text = "Umbrix Workstation",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Box(
                        modifier = Modifier
                            .background(colors.badgeLinesBackground, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v0.1.0-alpha",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.badgeLinesText
                        )
                    }
                }
                Text(
                    text = "High-throughput native log analysis workstation for multi-gigabyte files",
                    fontSize = 13.sp,
                    color = colors.textMuted
                )
            }

            // --- 2. Interactive Drop Zone & Open Action ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceGutter, shape = RoundedCornerShape(12.dp))
                    .border(1.dp, colors.border, shape = RoundedCornerShape(12.dp))
                    .clickable { onOpenFileClicked() }
                    .padding(vertical = 28.dp, horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📂",
                        fontSize = 32.sp
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Drag and drop log files here to analyze",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Supports 25GB–100GB+ files with zero GC heap ingestion and sub-second render",
                            fontSize = 12.sp,
                            color = colors.textMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    DefaultButton(onClick = onOpenFileClicked) {
                        Text("Open Log File... (Ctrl+O)")
                    }
                }
            }

            // --- 3. Recent Files Section ---
            if (recentFiles.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceGutter, shape = RoundedCornerShape(10.dp))
                        .border(1.dp, colors.border, shape = RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Logs",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Clear History",
                            fontSize = 11.sp,
                            color = colors.textMuted,
                            modifier = Modifier
                                .clickable { onClearRecentFiles() }
                                .padding(2.dp)
                        )
                    }

                    for (filePath in recentFiles.take(5)) {
                        val file = File(filePath)
                        val exists = file.exists()

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface, shape = RoundedCornerShape(6.dp))
                                .border(0.5.dp, colors.border.copy(alpha = 0.5f), shape = RoundedCornerShape(6.dp))
                                .clickable { onSelectRecentFile(filePath) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (exists) "📄" else "⚠",
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (exists) colors.textPrimary else colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = filePath,
                                    fontSize = 11.sp,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (exists && file.isFile) {
                                val sizeFormatted = formatFileSize(file.length())
                                Text(
                                    text = sizeFormatted,
                                    fontSize = 11.sp,
                                    color = colors.textMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // --- 4. Essential Keyboard Shortcuts ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShortcutChip("Ctrl+O", "Open File")
                Spacer(modifier = Modifier.width(12.dp))
                ShortcutChip("Ctrl+F", "Find / Filter")
                Spacer(modifier = Modifier.width(12.dp))
                ShortcutChip("Ctrl+G", "Go to Line")
                Spacer(modifier = Modifier.width(12.dp))
                ShortcutChip("Ctrl+T", "Time Scrubber")
                Spacer(modifier = Modifier.width(12.dp))
                ShortcutChip("Ctrl+,", "Settings")
            }
        }
    }
}

@Composable
private fun ShortcutChip(
    keyLabel: String,
    actionLabel: String
) {
    val colors = UmbrixTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .background(colors.surfaceGutter, shape = RoundedCornerShape(4.dp))
                .border(1.dp, colors.border, shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = keyLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = colors.textPrimary
            )
        }
        Text(
            text = actionLabel,
            fontSize = 11.sp,
            color = colors.textMuted
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.0f KB", bytes.toDouble() / 1024.0)
    else -> "$bytes B"
}
