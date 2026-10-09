package umbrix.ui.docking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.UUID

/**
 * Recursive layout tree node representing either a terminal leaf pane (hosting tabs)
 * or a split container dividing space between two child nodes.
 */
sealed interface DockNode {
    val id: String

    /**
     * A terminal leaf pane hosting one or more tabbed documents.
     */
    class Leaf(
        override val id: String = UUID.randomUUID().toString(),
        initialTabIds: List<String> = emptyList(),
        initialActiveTabId: String? = null
    ) : DockNode {
        val tabIds: SnapshotStateList<String> = mutableStateListOf<String>().apply { addAll(initialTabIds) }
        var activeTabId: String? by mutableStateOf(initialActiveTabId ?: initialTabIds.firstOrNull())
    }

    /**
     * A split container dividing space between two child nodes with a draggable divider.
     */
    class Split(
        override val id: String = UUID.randomUUID().toString(),
        val orientation: DockOrientation,
        var firstChild: DockNode,
        var secondChild: DockNode,
        initialSplitRatio: Float = 0.5f
    ) : DockNode {
        var splitRatio: Float by mutableStateOf(initialSplitRatio.coerceIn(0.05f, 0.95f))
        var isSyncScrollEnabled: Boolean by mutableStateOf(false)
    }
}
