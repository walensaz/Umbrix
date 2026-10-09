package umbrix.ui.docking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import umbrix.ui.scratchpad.ScratchpadSession
import umbrix.ui.state.WorkspaceTab

/**
 * Recursive layout host rendering either a split container (with [DockSplitDivider])
 * or a terminal [DockPaneView].
 */
@Composable
fun DockLayoutHost(
    node: DockNode,
    openTabs: List<WorkspaceTab>,
    activeLeafId: String,
    totalLeavesCount: Int,
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
    when (node) {
        is DockNode.Leaf -> {
            DockPaneView(
                leaf = node,
                openTabs = openTabs,
                isActiveLeaf = node.id == activeLeafId,
                onFocusLeaf = onFocusLeaf,
                onCloseTab = onCloseTab,
                onSplitRight = onSplitRight,
                onSplitDown = onSplitDown,
                onDetachTab = onDetachTab,
                onClosePane = if (totalLeavesCount > 1) onClosePane else null,
                onDropTab = onDropTab,
                availableScratchpads = availableScratchpads,
                onOpenScratchpadSession = onOpenScratchpadSession,
                onRenameScratchpad = onRenameScratchpad,
                onCloseOtherTabs = onCloseOtherTabs,
                onCloseAllTabs = onCloseAllTabs,
                onOpenScratchpadManager = onOpenScratchpadManager,
                onOpenFile = onOpenFile,
                onNewScratchpad = onNewScratchpad,
                modifier = modifier,
                onFilterByText = onFilterByText,
                onSearchText = onSearchText,
                onSeekInScrubber = onSeekInScrubber,
                onExportStartingHere = onExportStartingHere,
                onAddToTimeline = onAddToTimeline,
                onAppendToScratchpad = onAppendToScratchpad,
                onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                onToggleBookmarkFilter = onToggleBookmarkFilter
            )
        }

        is DockNode.Split -> {
            // Synchronized Scrolling Coordination between first and second children
            if (node.isSyncScrollEnabled) {
                val leafA = DockTreeManager.allLeaves(node.firstChild).firstOrNull()
                val leafB = DockTreeManager.allLeaves(node.secondChild).firstOrNull()
                val tabA = openTabs.find { it.id == leafA?.activeTabId }
                val tabB = openTabs.find { it.id == leafB?.activeTabId }

                if (tabA?.session != null && tabB?.session != null) {
                    var lastLineA by remember(tabA.id) { mutableStateOf(tabA.topVisibleLine) }
                    LaunchedEffect(tabA.topVisibleLine) {
                        val delta = tabA.topVisibleLine - lastLineA
                        if (delta != 0L) {
                            lastLineA = tabA.topVisibleLine
                            val totalB = (tabB.session.totalLines() as Number).toLong()
                            tabB.topVisibleLine = (tabB.topVisibleLine + delta).coerceIn(0L, maxOf(0L, totalB - 1L))
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                val totalWidthPx = constraints.maxWidth.toFloat()
                val totalHeightPx = constraints.maxHeight.toFloat()

                if (node.orientation == DockOrientation.HORIZONTAL) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(node.splitRatio)
                                .fillMaxHeight()
                        ) {
                            DockLayoutHost(
                                node = node.firstChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                onFocusLeaf = onFocusLeaf,
                                onCloseTab = onCloseTab,
                                onSplitRight = onSplitRight,
                                onSplitDown = onSplitDown,
                                onDetachTab = onDetachTab,
                                onClosePane = onClosePane,
                                onDropTab = onDropTab,
                                availableScratchpads = availableScratchpads,
                                onOpenScratchpadSession = onOpenScratchpadSession,
                                onRenameScratchpad = onRenameScratchpad,
                                onCloseOtherTabs = onCloseOtherTabs,
                                onCloseAllTabs = onCloseAllTabs,
                                onOpenScratchpadManager = onOpenScratchpadManager,
                                onOpenFile = onOpenFile,
                                onNewScratchpad = onNewScratchpad,
                                onFilterByText = onFilterByText,
                                onSearchText = onSearchText,
                                onSeekInScrubber = onSeekInScrubber,
                                onExportStartingHere = onExportStartingHere,
                                onAddToTimeline = onAddToTimeline,
                                onAppendToScratchpad = onAppendToScratchpad,
                                onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                                onToggleBookmarkFilter = onToggleBookmarkFilter
                            )
                        }

                        DockSplitDivider(
                            orientation = DockOrientation.HORIZONTAL,
                            splitRatio = node.splitRatio,
                            totalDimensionPx = totalWidthPx,
                            onSplitRatioChanged = { node.splitRatio = it },
                            isSyncScrollEnabled = node.isSyncScrollEnabled,
                            onToggleSyncScroll = { node.isSyncScrollEnabled = !node.isSyncScrollEnabled }
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f - node.splitRatio)
                                .fillMaxHeight()
                        ) {
                            DockLayoutHost(
                                node = node.secondChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                onFocusLeaf = onFocusLeaf,
                                onCloseTab = onCloseTab,
                                onSplitRight = onSplitRight,
                                onSplitDown = onSplitDown,
                                onDetachTab = onDetachTab,
                                onClosePane = onClosePane,
                                onDropTab = onDropTab,
                                availableScratchpads = availableScratchpads,
                                onOpenScratchpadSession = onOpenScratchpadSession,
                                onRenameScratchpad = onRenameScratchpad,
                                onCloseOtherTabs = onCloseOtherTabs,
                                onCloseAllTabs = onCloseAllTabs,
                                onOpenScratchpadManager = onOpenScratchpadManager,
                                onOpenFile = onOpenFile,
                                onNewScratchpad = onNewScratchpad,
                                onFilterByText = onFilterByText,
                                onSearchText = onSearchText,
                                onSeekInScrubber = onSeekInScrubber,
                                onExportStartingHere = onExportStartingHere,
                                onAddToTimeline = onAddToTimeline,
                                onAppendToScratchpad = onAppendToScratchpad,
                                onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                                onToggleBookmarkFilter = onToggleBookmarkFilter
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(node.splitRatio)
                                .fillMaxWidth()
                        ) {
                            DockLayoutHost(
                                node = node.firstChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                onFocusLeaf = onFocusLeaf,
                                onCloseTab = onCloseTab,
                                onSplitRight = onSplitRight,
                                onSplitDown = onSplitDown,
                                onDetachTab = onDetachTab,
                                onClosePane = onClosePane,
                                onDropTab = onDropTab,
                                availableScratchpads = availableScratchpads,
                                onOpenScratchpadSession = onOpenScratchpadSession,
                                onRenameScratchpad = onRenameScratchpad,
                                onCloseOtherTabs = onCloseOtherTabs,
                                onCloseAllTabs = onCloseAllTabs,
                                onOpenScratchpadManager = onOpenScratchpadManager,
                                onOpenFile = onOpenFile,
                                onNewScratchpad = onNewScratchpad,
                                onFilterByText = onFilterByText,
                                onSearchText = onSearchText,
                                onSeekInScrubber = onSeekInScrubber,
                                onExportStartingHere = onExportStartingHere,
                                onAddToTimeline = onAddToTimeline,
                                onAppendToScratchpad = onAppendToScratchpad,
                                onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                                onToggleBookmarkFilter = onToggleBookmarkFilter
                            )
                        }

                        DockSplitDivider(
                            orientation = DockOrientation.VERTICAL,
                            splitRatio = node.splitRatio,
                            totalDimensionPx = totalHeightPx,
                            onSplitRatioChanged = { node.splitRatio = it },
                            isSyncScrollEnabled = node.isSyncScrollEnabled,
                            onToggleSyncScroll = { node.isSyncScrollEnabled = !node.isSyncScrollEnabled }
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f - node.splitRatio)
                                .fillMaxWidth()
                        ) {
                            DockLayoutHost(
                                node = node.secondChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                onFocusLeaf = onFocusLeaf,
                                onCloseTab = onCloseTab,
                                onSplitRight = onSplitRight,
                                onSplitDown = onSplitDown,
                                onDetachTab = onDetachTab,
                                onClosePane = onClosePane,
                                onDropTab = onDropTab,
                                availableScratchpads = availableScratchpads,
                                onOpenScratchpadSession = onOpenScratchpadSession,
                                onRenameScratchpad = onRenameScratchpad,
                                onCloseOtherTabs = onCloseOtherTabs,
                                onCloseAllTabs = onCloseAllTabs,
                                onOpenScratchpadManager = onOpenScratchpadManager,
                                onOpenFile = onOpenFile,
                                onNewScratchpad = onNewScratchpad,
                                onFilterByText = onFilterByText,
                                onSearchText = onSearchText,
                                onSeekInScrubber = onSeekInScrubber,
                                onExportStartingHere = onExportStartingHere,
                                onAddToTimeline = onAddToTimeline,
                                onAppendToScratchpad = onAppendToScratchpad,
                                onNewScratchpadFromSelection = onNewScratchpadFromSelection,
                                onToggleBookmarkFilter = onToggleBookmarkFilter
                            )
                        }
                    }
                }
            }
        }
    }
}
