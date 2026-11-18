package umbrix.ui.docking

import androidx.compose.runtime.Immutable
import umbrix.ui.scratchpad.ScratchpadSession
import umbrix.ui.timeline.InvestigationTimelineManager
import umbrix.ui.timeline.TimelineEvent

/**
 * Immutable callback and state bundle for docking layout containers.
 * Eliminates 20+ individual callback lambdas being forwarded down recursive dock split nodes.
 */
@Immutable
data class DockViewportCallbacks(
    val onFocusLeaf: (String) -> Unit = {},
    val onCloseTab: (String) -> Unit = {},
    val onCloseOtherTabs: (String) -> Unit = {},
    val onCloseAllTabs: () -> Unit = {},
    val onClosePane: (String) -> Unit = {},
    val onSplitRight: (String) -> Unit = {},
    val onSplitDown: (String) -> Unit = {},
    val onDetachTab: (String) -> Unit = {},
    val onDropTab: (tabId: String, targetLeafId: String, dropZone: DockDropZone, insertIndex: Int) -> Unit = { _, _, _, _ -> },
    val availableScratchpads: List<ScratchpadSession> = emptyList(),
    val onOpenScratchpadSession: (ScratchpadSession) -> Unit = {},
    val onRenameScratchpad: (String) -> Unit = {},
    val onOpenScratchpadManager: () -> Unit = {},
    val onOpenFile: () -> Unit = {},
    val onNewScratchpad: () -> Unit = {},
    val onOpenTimeline: () -> Unit = {},
    val timelineManager: InvestigationTimelineManager? = null,
    val onJumpToTimelineEvent: (TimelineEvent) -> Unit = {},
    val onOpenTimelineExportDialog: () -> Unit = {},
    val onFilterByText: (String) -> Unit = {},
    val onSearchText: (String) -> Unit = {},
    val onSeekInScrubber: (Long) -> Unit = {},
    val onExportStartingHere: (Long) -> Unit = {},
    val onAddToTimeline: (Long, String) -> Unit = { _, _ -> },
    val onAppendToScratchpad: (Long, String) -> Unit = { _, _ -> },
    val onNewScratchpadFromSelection: (String) -> Unit = {},
    val onToggleBookmarkFilter: () -> Unit = {}
)
