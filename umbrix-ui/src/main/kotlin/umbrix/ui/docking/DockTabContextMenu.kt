package umbrix.ui.docking

import androidx.compose.runtime.Composable
import umbrix.ui.menu.UmbrixDropdownMenu
import umbrix.ui.menu.UmbrixMenuGlyph
import umbrix.ui.menu.UmbrixMenuItem
import umbrix.ui.menu.UmbrixMenuSeparator
import umbrix.ui.scratchpad.ScratchpadSession
import umbrix.ui.state.WorkspaceTab

/**
 * Context menu displayed on right-clicking a tab header in [DockPaneTabBar].
 */
@Composable
fun DockTabContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    tab: WorkspaceTab,
    totalTabsInPane: Int,
    onCloseTab: (String) -> Unit,
    onCloseOtherTabs: ((String) -> Unit)?,
    onCloseAllTabs: (() -> Unit)?,
    onSplitRight: () -> Unit,
    onSplitDown: () -> Unit,
    onDetachTab: ((String) -> Unit)?,
    onRenameScratchpad: ((String) -> Unit)?
) {
    UmbrixDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (tab.isScratchpad && onRenameScratchpad != null) {
            UmbrixMenuItem(
                text = "Rename Scratchpad...",
                icon = { UmbrixMenuGlyph("✏️") },
                onClick = {
                    onDismissRequest()
                    onRenameScratchpad(tab.id)
                }
            )
            UmbrixMenuSeparator()
        }
        UmbrixMenuItem(
            text = "Close Tab",
            shortcut = "Ctrl+W",
            icon = { UmbrixMenuGlyph("✕") },
            onClick = {
                onDismissRequest()
                onCloseTab(tab.id)
            }
        )
        if (totalTabsInPane > 1 && onCloseOtherTabs != null) {
            UmbrixMenuItem(
                text = "Close Other Tabs",
                onClick = {
                    onDismissRequest()
                    onCloseOtherTabs(tab.id)
                }
            )
        }
        if (onCloseAllTabs != null) {
            UmbrixMenuItem(
                text = "Close All Tabs",
                onClick = {
                    onDismissRequest()
                    onCloseAllTabs()
                }
            )
        }
        UmbrixMenuSeparator()
        UmbrixMenuItem(
            text = "Split Right",
            shortcut = "Ctrl+\\",
            icon = { UmbrixMenuGlyph("◫") },
            onClick = {
                onDismissRequest()
                onSplitRight()
            }
        )
        UmbrixMenuItem(
            text = "Split Down",
            shortcut = "Ctrl+Alt+\\",
            icon = { UmbrixMenuGlyph("⬒") },
            onClick = {
                onDismissRequest()
                onSplitDown()
            }
        )
        if (onDetachTab != null) {
            UmbrixMenuItem(
                text = "Move to New Window",
                icon = { UmbrixMenuGlyph("⤢") },
                onClick = {
                    onDismissRequest()
                    onDetachTab(tab.id)
                }
            )
        }
    }
}

/**
 * Dropdown menu triggered by the '+' button in [DockPaneTabBar].
 */
@Composable
fun DockNewTabDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onNewScratchpad: (() -> Unit)?,
    onOpenScratchpadManager: (() -> Unit)?,
    onOpenTimeline: (() -> Unit)?,
    onOpenFile: (() -> Unit)?,
    availableScratchpads: List<ScratchpadSession>,
    onOpenScratchpadSession: ((ScratchpadSession) -> Unit)?
) {
    UmbrixDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (onNewScratchpad != null) {
            UmbrixMenuItem(
                text = "New Scratchpad",
                shortcut = "Ctrl+N",
                icon = { UmbrixMenuGlyph("📝") },
                onClick = {
                    onDismissRequest()
                    onNewScratchpad()
                }
            )
        }
        if (onOpenScratchpadManager != null) {
            UmbrixMenuItem(
                text = "Manage All Scratchpads...",
                shortcut = "Ctrl+Alt+S",
                icon = { UmbrixMenuGlyph("📋") },
                onClick = {
                    onDismissRequest()
                    onOpenScratchpadManager()
                }
            )
        }
        if (onOpenTimeline != null) {
            UmbrixMenuItem(
                text = "Incident Timeline",
                shortcut = "Ctrl+Shift+T",
                icon = { UmbrixMenuGlyph("⏱️") },
                onClick = {
                    onDismissRequest()
                    onOpenTimeline()
                }
            )
        }
        if (onOpenFile != null) {
            UmbrixMenuItem(
                text = "Open Log File...",
                shortcut = "Ctrl+O",
                icon = { UmbrixMenuGlyph("📂") },
                onClick = {
                    onDismissRequest()
                    onOpenFile()
                }
            )
        }
        if (availableScratchpads.isNotEmpty()) {
            UmbrixMenuSeparator()
            availableScratchpads.take(6).forEach { session ->
                UmbrixMenuItem(
                    text = "📝 ${session.title}",
                    onClick = {
                        onDismissRequest()
                        onOpenScratchpadSession?.invoke(session)
                    }
                )
            }
        }
    }
}
