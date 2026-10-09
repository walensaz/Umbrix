package umbrix.ui.docking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.menu.UmbrixDropdownMenu
import umbrix.ui.menu.UmbrixMenuGlyph
import umbrix.ui.menu.UmbrixMenuItem
import umbrix.ui.menu.UmbrixMenuSeparator
import umbrix.ui.scratchpad.ScratchpadSession
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme

/**
 * Per-pane multi-document tab bar (VS Code Option A style).
 * Supports tab switching, middle-click close, context menu splits,
 * plus interactive drag initiation into the [DockDragCoordinator].
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DockPaneTabBar(
    tabs: List<WorkspaceTab>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onSplitRight: () -> Unit,
    onSplitDown: () -> Unit,
    onDetachTab: ((String) -> Unit)? = null,
    onClosePane: (() -> Unit)? = null,
    onDropTab: (tabId: String, targetLeafId: String, dropZone: DockDropZone, insertIndex: Int) -> Unit,
    availableScratchpads: List<ScratchpadSession> = emptyList(),
    onOpenScratchpadSession: ((ScratchpadSession) -> Unit)? = null,
    onRenameScratchpad: ((String) -> Unit)? = null,
    onCloseOtherTabs: ((String) -> Unit)? = null,
    onCloseAllTabs: (() -> Unit)? = null,
    onOpenScratchpadManager: (() -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onNewScratchpad: (() -> Unit)? = null,
    leafId: String = "",
    modifier: Modifier = Modifier
) {
    var contextMenuTabId by remember { mutableStateOf<String?>(null) }
    var isPlusMenuOpen by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(leafId) {
        onDispose {
            if (leafId.isNotBlank()) {
                DockDragCoordinator.unregisterTabBarBounds(leafId)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(UmbrixTheme.colors.surface)
            .padding(horizontal = 4.dp)
            .onGloballyPositioned { coordinates ->
                if (leafId.isNotBlank()) {
                    DockDragCoordinator.registerTabBarBounds(leafId, coordinates.boundsInWindow())
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Tabs list
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { tabIndex, tab ->
                val isActive = tab.id == activeTabId
                val tabBackground = if (isActive) UmbrixTheme.colors.surfaceElevated else UmbrixTheme.colors.surface
                val textColor = if (isActive) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textMuted

                androidx.compose.runtime.DisposableEffect(tab.id) {
                    onDispose {
                        DockDragCoordinator.unregisterTabBounds(tab.id)
                    }
                }

                var tabGlobalOffset by remember { mutableStateOf(Offset.Zero) }

                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        tabGlobalOffset = bounds.topLeft
                        if (leafId.isNotBlank()) {
                            DockDragCoordinator.registerTabBounds(tab.id, leafId, tabIndex, bounds)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .background(tabBackground, shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .onPointerEvent(PointerEventType.Press) { event ->
                                if (event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    contextMenuTabId = tab.id
                                } else if (event.button == PointerButton.Tertiary) {
                                    event.changes.forEach { it.consume() }
                                    onCloseTab(tab.id)
                                } else if (event.button == PointerButton.Primary && !event.buttons.isSecondaryPressed) {
                                    onSelectTab(tab.id)
                                }
                            }
                            .pointerInput(tab.id) {
                                var dragAccumulator = Offset.Zero
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        dragAccumulator = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulator += dragAmount
                                        if (!DockDragCoordinator.isDragging && dragAccumulator.getDistance() > 6f) {
                                            val currentGlobal = tabGlobalOffset + change.position
                                            DockDragCoordinator.startDrag(tab.id, leafId, tab.title, currentGlobal)
                                        } else if (DockDragCoordinator.isDragging) {
                                            val currentGlobal = tabGlobalOffset + change.position
                                            DockDragCoordinator.updatePointer(currentGlobal)
                                        }
                                    },
                                    onDragEnd = {
                                        if (DockDragCoordinator.isDragging) {
                                            val target = DockDragCoordinator.endDrag()
                                            if (target != null) {
                                                onDropTab(tab.id, target.leafId, target.dropZone, target.insertIndex)
                                            }
                                        }
                                        dragAccumulator = Offset.Zero
                                    },
                                    onDragCancel = {
                                        DockDragCoordinator.cancelDrag()
                                        dragAccumulator = Offset.Zero
                                    }
                                )
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelectTab(tab.id) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (if (tab.isScratchpad) "📝 " else "") + tab.title,
                            fontSize = 11.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "✕",
                            fontSize = 10.sp,
                            color = UmbrixTheme.colors.textSubtle,
                            modifier = Modifier.clickable { onCloseTab(tab.id) }
                        )
                    }

                    // Context Menu for Tab
                    UmbrixDropdownMenu(
                        expanded = contextMenuTabId == tab.id,
                        onDismissRequest = { contextMenuTabId = null }
                    ) {
                        if (tab.isScratchpad && onRenameScratchpad != null) {
                            UmbrixMenuItem(
                                text = "Rename Scratchpad...",
                                icon = { UmbrixMenuGlyph("✏️") },
                                onClick = {
                                    contextMenuTabId = null
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
                                contextMenuTabId = null
                                onCloseTab(tab.id)
                            }
                        )
                        if (tabs.size > 1 && onCloseOtherTabs != null) {
                            UmbrixMenuItem(
                                text = "Close Other Tabs",
                                onClick = {
                                    contextMenuTabId = null
                                    onCloseOtherTabs(tab.id)
                                }
                            )
                        }
                        if (onCloseAllTabs != null) {
                            UmbrixMenuItem(
                                text = "Close All Tabs",
                                onClick = {
                                    contextMenuTabId = null
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
                                contextMenuTabId = null
                                onSplitRight()
                            }
                        )
                        UmbrixMenuItem(
                            text = "Split Down",
                            shortcut = "Ctrl+Alt+\\",
                            icon = { UmbrixMenuGlyph("⬒") },
                            onClick = {
                                contextMenuTabId = null
                                onSplitDown()
                            }
                        )
                        if (onDetachTab != null) {
                            UmbrixMenuItem(
                                text = "Move to New Window",
                                icon = { UmbrixMenuGlyph("⤢") },
                                onClick = {
                                    contextMenuTabId = null
                                    onDetachTab(tab.id)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(2.dp))
            }

            // New Tab (+) Button
            Box {
                Tooltip(tooltip = { Text("Open File or Scratchpad (+)") }) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { isPlusMenuOpen = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "+",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = UmbrixTheme.colors.textMuted
                        )
                    }
                }

                UmbrixDropdownMenu(
                    expanded = isPlusMenuOpen,
                    onDismissRequest = { isPlusMenuOpen = false }
                ) {
                    if (onNewScratchpad != null) {
                        UmbrixMenuItem(
                            text = "New Scratchpad",
                            shortcut = "Ctrl+N",
                            icon = { UmbrixMenuGlyph("📝") },
                            onClick = {
                                isPlusMenuOpen = false
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
                                isPlusMenuOpen = false
                                onOpenScratchpadManager()
                            }
                        )
                    }
                    if (onOpenFile != null) {
                        UmbrixMenuItem(
                            text = "Open Log File...",
                            shortcut = "Ctrl+O",
                            icon = { UmbrixMenuGlyph("📂") },
                            onClick = {
                                isPlusMenuOpen = false
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
                                    isPlusMenuOpen = false
                                    onOpenScratchpadSession?.invoke(session)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Right: Pane action buttons (Split Right ◫, Split Down ⬒, Pop Out ⤢, Close Pane ✕)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tooltip(tooltip = { Text("Split Right (Ctrl+\\)") }) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { onSplitRight() }
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                ) {
                    Text("◫", fontSize = 12.sp, color = UmbrixTheme.colors.textMuted)
                }
            }
            Tooltip(tooltip = { Text("Split Down (Ctrl+Alt+\\)") }) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { onSplitDown() }
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                ) {
                    Text("⬒", fontSize = 12.sp, color = UmbrixTheme.colors.textMuted)
                }
            }
            if (activeTabId != null && onDetachTab != null) {
                Tooltip(tooltip = { Text("Pop Out to Separate Window") }) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onDetachTab(activeTabId) }
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Text("⤢", fontSize = 12.sp, color = UmbrixTheme.colors.textMuted)
                    }
                }
            }
            if (onClosePane != null) {
                Tooltip(tooltip = { Text("Close Pane") }) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onClosePane() }
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Text("✕", fontSize = 11.sp, color = UmbrixTheme.colors.textSubtle)
                    }
                }
            }
        }
    }
}
