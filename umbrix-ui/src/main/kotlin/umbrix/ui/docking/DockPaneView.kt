package umbrix.ui.docking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.timeline.InvestigationTimelineCanvas
import umbrix.ui.toast.ToastManager
import umbrix.ui.viewport.LogViewportCanvas

/**
 * Terminal leaf container rendering a localized [DockPaneTabBar] and the active document canvas
 * (either [LogViewportCanvas], [ScratchpadEditorCanvas], or [InvestigationTimelineCanvas]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DockPaneView(
    leaf: DockNode.Leaf,
    openTabs: List<WorkspaceTab>,
    isActiveLeaf: Boolean,
    totalLeavesCount: Int,
    callbacks: DockViewportCallbacks,
    modifier: Modifier = Modifier
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
                callbacks.onFocusLeaf(leaf.id)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                callbacks.onFocusLeaf(leaf.id)
            }
    ) {
        // Pane Tab Bar
        DockPaneTabBar(
            leafId = leaf.id,
            tabs = paneTabs,
            activeTabId = activeTab?.id,
            onSelectTab = { tabId ->
                leaf.activeTabId = tabId
                callbacks.onFocusLeaf(leaf.id)
            },
            onCloseTab = { tabId ->
                callbacks.onCloseTab(tabId)
            },
            onSplitRight = { callbacks.onSplitRight(leaf.id) },
            onSplitDown = { callbacks.onSplitDown(leaf.id) },
            onDetachTab = callbacks.onDetachTab,
            onClosePane = if (totalLeavesCount > 1) { { callbacks.onClosePane(leaf.id) } } else null,
            onDropTab = callbacks.onDropTab,
            availableScratchpads = callbacks.availableScratchpads,
            onOpenScratchpadSession = callbacks.onOpenScratchpadSession,
            onRenameScratchpad = callbacks.onRenameScratchpad,
            onCloseOtherTabs = callbacks.onCloseOtherTabs,
            onCloseAllTabs = callbacks.onCloseAllTabs,
            onOpenScratchpadManager = callbacks.onOpenScratchpadManager,
            onOpenFile = callbacks.onOpenFile,
            onNewScratchpad = callbacks.onNewScratchpad,
            onOpenTimeline = callbacks.onOpenTimeline
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
                activeTab.customDockView != null -> {
                    umbrix.ui.plugin.PluginDockViewHost(
                        view = activeTab.customDockView!!,
                        tab = activeTab,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                activeTab.isTimelineTab && callbacks.timelineManager != null -> {
                    InvestigationTimelineCanvas(
                        timelineManager = callbacks.timelineManager,
                        onJumpToEvent = { callbacks.onJumpToTimelineEvent(it) },
                        onOpenExportDialog = { callbacks.onOpenTimelineExportDialog() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                activeTab.isScratchpad -> {
                    ScratchpadEditorCanvas(
                        session = activeTab.scratchpadSession!!,
                        tab = activeTab,
                        onSaveRequested = {
                            ToastManager.success("Scratchpad saved")
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                activeTab.session != null -> {
                    LogViewportCanvas(
                        tab = activeTab,
                        modifier = Modifier.fillMaxSize(),
                        onFilterByText = callbacks.onFilterByText,
                        onSearchText = callbacks.onSearchText,
                        onSeekInScrubber = callbacks.onSeekInScrubber,
                        onExportStartingHere = callbacks.onExportStartingHere,
                        onAddToTimeline = callbacks.onAddToTimeline,
                        onAppendToScratchpad = callbacks.onAppendToScratchpad,
                        onNewScratchpadFromSelection = callbacks.onNewScratchpadFromSelection,
                        onOpenFile = { callbacks.onOpenFile() },
                        onNewScratchpad = { callbacks.onNewScratchpad() },
                        onToggleBookmarkFilter = callbacks.onToggleBookmarkFilter
                    )
                }
            }
        }
    }
}
