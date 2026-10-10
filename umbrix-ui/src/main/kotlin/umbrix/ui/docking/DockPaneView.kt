package umbrix.ui.docking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.scratchpad.ScratchpadEditorCanvas
import umbrix.ui.scratchpad.ScratchpadSession
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.viewport.LogViewportCanvas

/**
 * Terminal leaf container rendering a localized [DockPaneTabBar] and the active document canvas
 * (either [LogViewportCanvas] or [ScratchpadEditorCanvas]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DockPaneView(
    leaf: DockNode.Leaf,
    openTabs: List<WorkspaceTab>,
    isActiveLeaf: Boolean,
    onFocusLeaf: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onSplitRight: (String) -> Unit,
    onSplitDown: (String) -> Unit,
    onDetachTab: ((String) -> Unit)? = null,
    onClosePane: ((String) -> Unit)? = null,
    onDropTab: (tabId: String, targetLeafId: String, dropZone: DockDropZone, insertIndex: Int) -> Unit,
    availableScratchpads: List<ScratchpadSession> = emptyList(),
    onOpenScratchpadSession: ((ScratchpadSession) -> Unit)? = null,
    onRenameScratchpad: ((String) -> Unit)? = null,
    onCloseOtherTabs: ((String) -> Unit)? = null,
    onCloseAllTabs: (() -> Unit)? = null,
    onOpenScratchpadManager: (() -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onNewScratchpad: (() -> Unit)? = null,
    onOpenTimeline: (() -> Unit)? = null,
    timelineManager: umbrix.ui.timeline.InvestigationTimelineManager? = null,
    onJumpToTimelineEvent: ((umbrix.ui.timeline.TimelineEvent) -> Unit)? = null,
    onOpenTimelineExportDialog: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // Callback delegates for LogViewportCanvas
    onFilterByText: (String) -> Unit = {},
    onSearchText: (String) -> Unit = {},
    onSeekInScrubber: (Long) -> Unit = {},
    onExportStartingHere: (Long) -> Unit = {},
    onAddToTimeline: (Long, String) -> Unit = { _, _ -> },
    onAppendToScratchpad: (Long, String) -> Unit = { _, _ -> },
    onNewScratchpadFromSelection: (String) -> Unit = {},
    onToggleBookmarkFilter: () -> Unit = {}
) {
    DisposableEffect(leaf.id) {
        onDispose {
            DockDragCoordinator.unregisterLeafBounds(leaf.id)
        }
    }

    val paneTabs = remember(leaf.tabIds.toList(), openTabs) {
        leaf.tabIds.mapNotNull { tabId -> openTabs.find { it.id == tabId } }
    }
    val activeTab = openTabs.find { it.id == leaf.activeTabId } ?: paneTabs.firstOrNull()

    val focusBorder = if (isActiveLeaf) {
        Modifier.border(1.dp, UmbrixTheme.colors.accentPrimary.copy(alpha = 0.35f))
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(focusBorder)
            .background(UmbrixTheme.colors.surface)
            .onGloballyPositioned { coordinates ->
                DockDragCoordinator.registerLeafBounds(leaf.id, coordinates.boundsInWindow())
            }
            .onPointerEvent(PointerEventType.Press) {
                onFocusLeaf(leaf.id)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onFocusLeaf(leaf.id)
            }
    ) {
        // Pane Tab Bar (Option A)
        DockPaneTabBar(
            leafId = leaf.id,
            tabs = paneTabs,
            activeTabId = activeTab?.id,
            onSelectTab = { tabId ->
                leaf.activeTabId = tabId
                onFocusLeaf(leaf.id)
            },
            onCloseTab = { tabId ->
                onCloseTab(tabId)
            },
            onSplitRight = { onSplitRight(leaf.id) },
            onSplitDown = { onSplitDown(leaf.id) },
            onDetachTab = onDetachTab,
            onClosePane = if (onClosePane != null) { { onClosePane(leaf.id) } } else null,
            onDropTab = onDropTab,
            availableScratchpads = availableScratchpads,
            onOpenScratchpadSession = onOpenScratchpadSession,
            onRenameScratchpad = onRenameScratchpad,
            onCloseOtherTabs = onCloseOtherTabs,
            onCloseAllTabs = onCloseAllTabs,
            onOpenScratchpadManager = onOpenScratchpadManager,
            onOpenFile = onOpenFile,
            onNewScratchpad = onNewScratchpad,
            onOpenTimeline = onOpenTimeline
        )

        Divider(Orientation.Horizontal)

        // Active Document Viewport Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                activeTab == null -> {
                    // Empty pane drop invitation
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Drag a tab here or open a file/scratchpad/timeline",
                            fontSize = 12.sp,
                            color = UmbrixTheme.colors.textSubtle
                        )
                    }
                }
                activeTab.isTimelineTab && timelineManager != null -> {
                    umbrix.ui.timeline.InvestigationTimelineCanvas(
                        timelineManager = timelineManager,
                        onJumpToEvent = { onJumpToTimelineEvent?.invoke(it) },
                        onOpenExportDialog = { onOpenTimelineExportDialog?.invoke() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                activeTab.isScratchpad -> {
                    ScratchpadEditorCanvas(
                        session = activeTab.scratchpadSession!!,
                        onSaveRequested = {
                            umbrix.ui.toast.ToastManager.success("Scratchpad saved")
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                activeTab.session != null -> {
                    LogViewportCanvas(
                        tab = activeTab,
                        modifier = Modifier.fillMaxSize(),
                        onFilterByText = onFilterByText,
                        onSearchText = onSearchText,
                        onSeekInScrubber = onSeekInScrubber,
                        onExportStartingHere = onExportStartingHere,
                        onAddToTimeline = onAddToTimeline,
                        onAppendToScratchpad = onAppendToScratchpad,
                        onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                        onOpenFile = { onOpenFile?.invoke() },
                        onNewScratchpad = { onNewScratchpad?.invoke() },
                        onToggleBookmarkFilter = onToggleBookmarkFilter
                    )
                }
            }
        }
    }
}
