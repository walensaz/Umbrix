package umbrix.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme

private data class ShortcutItem(
    val category: String,
    val action: String,
    val keys: List<String>
)

private val ALL_SHORTCUTS = listOf(
    // Command Palette & Global Search
    ShortcutItem("Command Palette", "Universal Command Palette (Quick Open)", listOf("Ctrl", "P")),
    ShortcutItem("Command Palette", "Command Palette (Actions)", listOf("Ctrl", "Shift", "P")),
    ShortcutItem("Command Palette", "Search Everywhere", listOf("Shift", "Shift")),
    ShortcutItem("Command Palette", "Vim Ex-Command Mode", listOf(":")),
    ShortcutItem("Command Palette", "Row Context Actions", listOf("Right Click")),

    // File Operations
    ShortcutItem("File", "Open Log File", listOf("Ctrl", "O")),
    ShortcutItem("File", "New Scratchpad Buffer", listOf("Ctrl", "N")),
    ShortcutItem("File", "Export Sub-File", listOf("Ctrl", "Shift", "S")),
    ShortcutItem("File", "Close Active Tab", listOf("Ctrl", "W")),
    ShortcutItem("File", "Exit Application", listOf("Alt", "F4")),

    // Navigation & Forensics
    ShortcutItem("Navigation", "Go to Line or Timestamp", listOf("Ctrl", "G")),
    ShortcutItem("Navigation", "Temporal Range Scrubber", listOf("Ctrl", "T")),
    ShortcutItem("Navigation", "Jump to First Line", listOf("Home")),
    ShortcutItem("Navigation", "Jump to Last Line", listOf("End")),
    ShortcutItem("Navigation", "Page Down Viewport", listOf("PageDown")),
    ShortcutItem("Navigation", "Page Up Viewport", listOf("PageUp")),
    ShortcutItem("Bookmarks", "Next Bookmark", listOf("F2")),
    ShortcutItem("Bookmarks", "Previous Bookmark", listOf("Shift", "F2")),
    ShortcutItem("Bookmarks", "Toggle Bookmark on Line", listOf("Ctrl", "B")),
    ShortcutItem("Timeline", "Toggle Incident Timeline Drawer", listOf("Ctrl", "Shift", "T")),

    // Search & Forensic Analysis
    ShortcutItem("Search", "Find in File", listOf("Ctrl", "F")),
    ShortcutItem("Search", "Next Match", listOf("F3")),
    ShortcutItem("Search", "Previous Match", listOf("Shift", "F3")),
    ShortcutItem("Search", "Next Forensic Anomaly (Error/Warn)", listOf("F4")),
    ShortcutItem("Search", "Previous Forensic Anomaly", listOf("Shift", "F4")),
    ShortcutItem("Search", "Copy Selected Log Text", listOf("Ctrl", "C")),

    // View & Scaling
    ShortcutItem("View", "Zoom In (Increase Font)", listOf("Ctrl", "+")),
    ShortcutItem("View", "Zoom Out (Decrease Font)", listOf("Ctrl", "-")),
    ShortcutItem("View", "Reset Zoom to Default", listOf("Ctrl", "0")),
    ShortcutItem("View", "Toggle Minimap Visibility", listOf("Ctrl", "M")),
    ShortcutItem("View", "Toggle Fullscreen", listOf("F11")),

    // Window & Settings
    ShortcutItem("Window", "Open Workstation Settings", listOf("Ctrl", ",")),
    ShortcutItem("Window", "Show Keyboard Shortcuts", listOf("F1")),
    ShortcutItem("Window", "About Umbrix Workstation", listOf("Ctrl", "Shift", "A"))
)

/**
 * Interactive searchable modal dialog indexing all workstation keyboard shortcuts.
 */
@Composable
fun KeyboardShortcutsDialog(
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredShortcuts = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            ALL_SHORTCUTS
        } else {
            ALL_SHORTCUTS.filter {
                it.action.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true) ||
                it.keys.any { key -> key.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    val groupedShortcuts = remember(filteredShortcuts) {
        filteredShortcuts.groupBy { it.category }
    }

    UmbrixModalCard(
        title = "Keyboard Shortcuts",
        onDismiss = onDismiss,
        width = 580.dp,
        showCloseButton = true,
        isScrollable = true,
        actions = {
            DefaultButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    ) {
        // Search Filter Input
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search shortcuts (e.g. 'zoom', 'find', 'line')...") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (groupedShortcuts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No shortcuts matching \"$searchQuery\"",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        } else {
            for ((category, items) in groupedShortcuts) {
                Text(
                    text = category.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = UmbrixTheme.colors.accentPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UmbrixTheme.colors.surfacePanel, shape = RoundedCornerShape(6.dp))
                        .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    items.forEachIndexed { index, shortcut ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = shortcut.action,
                                fontSize = 12.sp,
                                color = UmbrixTheme.colors.textSecondary
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                shortcut.keys.forEach { keyLabel ->
                                    KbdKeyChip(keyLabel)
                                }
                            }
                        }
                        if (index < items.size - 1) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(UmbrixTheme.colors.border)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun KbdKeyChip(keyText: String) {
    Box(
        modifier = Modifier
            .background(UmbrixTheme.colors.surfaceElevated, RoundedCornerShape(4.dp))
            .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = keyText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = UmbrixTheme.colors.textPrimary
        )
    }
}
