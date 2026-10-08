package umbrix.ui.scratchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.toast.ToastManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Modal dialog for discovering, searching, reopening, copying, and deleting
 * scratchpad note buffers and extracted log slices.
 */
@Composable
fun ScratchpadManagerDialog(
    scratchpadManager: ScratchpadManager,
    openScratchpadIds: Set<String>,
    onOpenScratchpad: (ScratchpadSession) -> Unit,
    onNewScratchpad: () -> Unit,
    onDeleteScratchpad: (ScratchpadSession) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val colors = UmbrixTheme.colors

    val filteredSessions = remember(searchQuery, scratchpadManager.sessions.size, scratchpadManager.sessions.map { it.content }) {
        scratchpadManager.findSessions(searchQuery)
    }

    UmbrixModalCard(
        title = "Scratchpad Notes & Slices (${scratchpadManager.sessions.size})",
        onDismiss = onDismiss,
        width = 760.dp,
        isScrollable = false,
        actions = {
            DefaultButton(
                onClick = {
                    onNewScratchpad()
                    onDismiss()
                }
            ) {
                Text("+ New Scratchpad")
            }
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            // Search & Filter Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search scratchpads by title, content, or path...") },
                    modifier = Modifier.weight(1f)
                )

                if (searchQuery.isNotBlank()) {
                    OutlinedButton(onClick = { searchQuery = "" }) {
                        Text("Clear")
                    }
                }
            }

            // Scratchpads List
            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(colors.surfaceElevated, RoundedCornerShape(8.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (scratchpadManager.sessions.isEmpty()) "No scratchpads recorded yet" else "No matching scratchpads found",
                            color = colors.textMuted,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Create a scratchpad with Ctrl+N or select log lines and right-click 'Append to Scratchpad'",
                            color = colors.textMuted.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        val isOpenInTab = openScratchpadIds.contains(session.id)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceElevated, RoundedCornerShape(8.dp))
                                .border(1.dp, if (isOpenInTab) colors.borderFocused.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Header Row: Title, Badges, Metrics
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📝 ${session.title}",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )

                                    if (isOpenInTab) {
                                        Box(
                                            modifier = Modifier
                                                .background(colors.accentPrimary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "ACTIVE TAB",
                                                color = colors.textLink,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    if (session.isDirty) {
                                        Box(
                                            modifier = Modifier
                                                .background(colors.severityWarn.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "UNSAVED",
                                                color = colors.severityWarn,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else if (session.diskPath != null) {
                                        Box(
                                            modifier = Modifier
                                                .background(colors.severityInfo.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "SAVED",
                                                color = colors.severityInfo,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${session.lineCount} lines",
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "${session.charCount} chars",
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                    if (session.snippetCount > 0) {
                                        Text(
                                            text = "${session.snippetCount} snippets",
                                            color = colors.textMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            // Content Preview Box
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.surface, RoundedCornerShape(4.dp))
                                    .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = session.previewSnippet,
                                    color = if (session.content.isBlank()) colors.textMuted else colors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }

                            // Actions Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (session.diskPath != null) {
                                    Text(
                                        text = "Path: ${session.diskPath}",
                                        color = colors.textMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                } else {
                                    Text(
                                        text = "Virtual Memory Buffer",
                                        color = colors.textMuted.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val selection = StringSelection(session.content)
                                                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                                ToastManager.success("Copied ${session.title} to clipboard")
                                            } catch (e: Exception) {
                                                ToastManager.error("Failed to copy: ${e.message}")
                                            }
                                        }
                                    ) {
                                        Text("📋 Copy")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            onDeleteScratchpad(session)
                                            ToastManager.info("Deleted ${session.title}")
                                        }
                                    ) {
                                        Text("🗑 Delete")
                                    }

                                    DefaultButton(
                                        onClick = {
                                            onOpenScratchpad(session)
                                            onDismiss()
                                        }
                                    ) {
                                        Text(if (isOpenInTab) "Switch to Tab" else "Open in Tab")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
