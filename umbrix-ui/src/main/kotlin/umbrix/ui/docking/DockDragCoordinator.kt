package umbrix.ui.docking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Encapsulates a tab's physical bounds and position in a specific pane's tab strip.
 */
data class TabBoundsInfo(
    val tabId: String,
    val leafId: String,
    val index: Int,
    val bounds: Rect
)

/**
 * Encapsulates the resolved drop target during a drag gesture.
 */
data class DropTarget(
    val leafId: String,
    val dropZone: DockDropZone,
    val targetRect: Rect,
    val insertIndex: Int = -1,
    val tabInsertionX: Float? = null,
    val isTabBarDrop: Boolean = false
)

/**
 * Global reactive coordinator managing tab drag-and-drop gestures,
 * leaf bounding box hit-testing, tab strip reordering, and drop zone calculation.
 */
object DockDragCoordinator {

    var isDragging: Boolean by mutableStateOf(false)
        private set

    var draggedTabId: String? by mutableStateOf(null)
        private set

    var sourceLeafId: String? by mutableStateOf(null)
        private set

    var draggedTabTitle: String? by mutableStateOf(null)
        private set

    var pointerWindowOffset: Offset by mutableStateOf(Offset.Zero)
        private set

    var activeDropTarget: DropTarget? by mutableStateOf(null)
        private set

    private val registeredLeafBounds = mutableMapOf<String, Rect>()
    private val registeredTabBarBounds = mutableMapOf<String, Rect>()
    private val registeredTabBounds = mutableMapOf<String, TabBoundsInfo>()

    fun registerLeafBounds(leafId: String, bounds: Rect) {
        registeredLeafBounds[leafId] = bounds
    }

    fun unregisterLeafBounds(leafId: String) {
        registeredLeafBounds.remove(leafId)
    }

    fun registerTabBarBounds(leafId: String, bounds: Rect) {
        registeredTabBarBounds[leafId] = bounds
    }

    fun unregisterTabBarBounds(leafId: String) {
        registeredTabBarBounds.remove(leafId)
    }

    fun registerTabBounds(tabId: String, leafId: String, index: Int, bounds: Rect) {
        registeredTabBounds[tabId] = TabBoundsInfo(tabId, leafId, index, bounds)
    }

    fun unregisterTabBounds(tabId: String) {
        registeredTabBounds.remove(tabId)
    }

    fun startDrag(tabId: String, sourceLeafId: String, tabTitle: String, startOffset: Offset) {
        isDragging = true
        draggedTabId = tabId
        this.sourceLeafId = sourceLeafId
        draggedTabTitle = tabTitle
        pointerWindowOffset = startOffset
        activeDropTarget = computeDropTarget(startOffset)
    }

    fun updatePointer(offset: Offset) {
        if (!isDragging) return
        pointerWindowOffset = offset
        activeDropTarget = computeDropTarget(offset)
    }

    fun endDrag(): DropTarget? {
        val target = activeDropTarget
        cancelDrag()
        return target
    }

    fun cancelDrag() {
        isDragging = false
        draggedTabId = null
        sourceLeafId = null
        draggedTabTitle = null
        pointerWindowOffset = Offset.Zero
        activeDropTarget = null
    }

    private fun computeDropTarget(pointer: Offset): DropTarget? {
        // 1. Check if pointer is hovering over any pane's registered Tab Strip
        for ((leafId, tabBarRect) in registeredTabBarBounds) {
            val hitRect = Rect(tabBarRect.left, tabBarRect.top - 6f, tabBarRect.right, tabBarRect.bottom + 8f)
            if (hitRect.contains(pointer)) {
                val leafTabs = registeredTabBounds.values
                    .filter { it.leafId == leafId }
                    .sortedBy { it.index }

                var insertIndex = leafTabs.size
                var insertionX = if (leafTabs.isEmpty()) tabBarRect.left + 8f else leafTabs.last().bounds.right

                for (item in leafTabs) {
                    val tabCenterX = (item.bounds.left + item.bounds.right) / 2f
                    if (pointer.x < tabCenterX) {
                        insertIndex = item.index
                        insertionX = item.bounds.left
                        break
                    }
                }

                return DropTarget(
                    leafId = leafId,
                    dropZone = DockDropZone.CENTER_TAB,
                    targetRect = tabBarRect,
                    insertIndex = insertIndex,
                    tabInsertionX = insertionX,
                    isTabBarDrop = true
                )
            }
        }

        // 2. Pointer is inside a leaf canvas body (below the tab bar)
        for ((leafId, r) in registeredLeafBounds) {
            val expandedR = Rect(r.left - 24f, r.top - 12f, r.right + 24f, r.bottom + 24f)
            if (expandedR.contains(pointer)) {
                val horizontalThreshold = minOf(r.width * 0.35f, 220f)
                val verticalThreshold = minOf(r.height * 0.32f, 160f)

                val tabBarBottom = registeredTabBarBounds[leafId]?.bottom ?: (r.top + 32f)

                val clampedX = pointer.x.coerceIn(r.left, r.right)
                val clampedY = pointer.y.coerceIn(r.top, r.bottom)

                val distLeft = clampedX - r.left
                val distRight = r.right - clampedX
                val distTop = (clampedY - tabBarBottom).coerceAtLeast(0f)
                val distBottom = r.bottom - clampedY

                val minDistance = minOf(distLeft, distRight, distTop, distBottom)

                return when {
                    distLeft == minDistance && distLeft < horizontalThreshold -> {
                        DropTarget(
                            leafId = leafId,
                            dropZone = DockDropZone.LEFT,
                            targetRect = Rect(r.left, r.top, r.left + r.width / 2f, r.bottom)
                        )
                    }
                    distRight == minDistance && distRight < horizontalThreshold -> {
                        DropTarget(
                            leafId = leafId,
                            dropZone = DockDropZone.RIGHT,
                            targetRect = Rect(r.left + r.width / 2f, r.top, r.right, r.bottom)
                        )
                    }
                    distTop == minDistance && distTop < verticalThreshold -> {
                        DropTarget(
                            leafId = leafId,
                            dropZone = DockDropZone.TOP,
                            targetRect = Rect(r.left, r.top, r.right, r.top + r.height / 2f)
                        )
                    }
                    distBottom == minDistance && distBottom < verticalThreshold -> {
                        DropTarget(
                            leafId = leafId,
                            dropZone = DockDropZone.BOTTOM,
                            targetRect = Rect(r.left, r.top + r.height / 2f, r.right, r.bottom)
                        )
                    }
                    else -> {
                        // Canvas body center: drop tab to group/merge into this leaf
                        DropTarget(
                            leafId = leafId,
                            dropZone = DockDropZone.CENTER_TAB,
                            targetRect = r,
                            insertIndex = -1,
                            isTabBarDrop = false
                        )
                    }
                }
            }
        }
        return null
    }
}
