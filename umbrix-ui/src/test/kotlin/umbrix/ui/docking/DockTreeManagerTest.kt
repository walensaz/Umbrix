package umbrix.ui.docking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockTreeManagerTest {

    @Test
    fun testInitialTreeAndFind() {
        val leaf = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2"), initialActiveTabId = "tab-1")
        assertEquals(2, leaf.tabIds.size)
        assertEquals("tab-1", leaf.activeTabId)

        val found = DockTreeManager.findLeafForTab(leaf, "tab-2")
        assertNotNull(found)
        assertEquals(leaf.id, found?.id)

        val notFound = DockTreeManager.findLeafForTab(leaf, "non-existent")
        assertNull(notFound)
    }

    @Test
    fun testSplitLeafRight() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2"), initialActiveTabId = "tab-1")
        val updated = DockTreeManager.splitLeaf(
            root = root,
            targetLeafId = root.id,
            tabId = "tab-2",
            dropZone = DockDropZone.RIGHT
        )

        assertTrue(updated is DockNode.Split)
        val split = updated as DockNode.Split
        assertEquals(DockOrientation.HORIZONTAL, split.orientation)
        assertEquals(0.5f, split.splitRatio)

        assertTrue(split.firstChild is DockNode.Leaf)
        assertTrue(split.secondChild is DockNode.Leaf)

        val leftLeaf = split.firstChild as DockNode.Leaf
        val rightLeaf = split.secondChild as DockNode.Leaf

        assertEquals(listOf("tab-1"), leftLeaf.tabIds.toList())
        assertEquals(listOf("tab-2"), rightLeaf.tabIds.toList())
    }

    @Test
    fun testSplitLeafBottom() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2"), initialActiveTabId = "tab-1")
        val updated = DockTreeManager.splitLeaf(
            root = root,
            targetLeafId = root.id,
            tabId = "tab-2",
            dropZone = DockDropZone.BOTTOM
        )

        assertTrue(updated is DockNode.Split)
        val split = updated as DockNode.Split
        assertEquals(DockOrientation.VERTICAL, split.orientation)
        assertEquals(0.5f, split.splitRatio)

        val topLeaf = split.firstChild as DockNode.Leaf
        val bottomLeaf = split.secondChild as DockNode.Leaf

        assertEquals(listOf("tab-1"), topLeaf.tabIds.toList())
        assertEquals(listOf("tab-2"), bottomLeaf.tabIds.toList())
    }

    @Test
    fun testCenterTabDropGrouping() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1"), initialActiveTabId = "tab-1")
        val updated = DockTreeManager.splitLeaf(
            root = root,
            targetLeafId = root.id,
            tabId = "tab-2",
            dropZone = DockDropZone.CENTER_TAB
        )

        assertTrue(updated is DockNode.Leaf)
        val leaf = updated as DockNode.Leaf
        assertEquals(listOf("tab-1", "tab-2"), leaf.tabIds.toList())
        assertEquals("tab-2", leaf.activeTabId)
    }

    @Test
    fun testPruneEmptyLeavesOnTabRemoval() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2"), initialActiveTabId = "tab-1")
        val split = DockTreeManager.splitLeaf(
            root = root,
            targetLeafId = root.id,
            tabId = "tab-2",
            dropZone = DockDropZone.RIGHT
        )

        // Remove tab-2 from the right leaf; the right leaf should become empty, collapsing the split back to a single leaf!
        val afterRemove = DockTreeManager.removeTab(split, "tab-2")
        assertNotNull(afterRemove)
        assertTrue(afterRemove is DockNode.Leaf)
        val survivingLeaf = afterRemove as DockNode.Leaf
        assertEquals(listOf("tab-1"), survivingLeaf.tabIds.toList())
    }

    @Test
    fun testEqualizeSplits() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2", "tab-3"))
        val split1 = DockTreeManager.splitLeaf(root, root.id, "tab-2", DockDropZone.RIGHT) as DockNode.Split
        split1.splitRatio = 0.8f

        assertEquals(0.8f, split1.splitRatio)
        DockTreeManager.equalizeSplits(split1)
        assertEquals(0.5f, split1.splitRatio)
    }

    @Test
    fun testMoveTabReorderWithinSameLeaf() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2", "tab-3"), initialActiveTabId = "tab-1")
        
        // Move tab-3 to index 0 (beginning)
        val reordered1 = DockTreeManager.moveTab(root, "tab-3", root.id, insertIndex = 0)
        assertTrue(reordered1 is DockNode.Leaf)
        val leaf1 = reordered1 as DockNode.Leaf
        assertEquals(listOf("tab-3", "tab-1", "tab-2"), leaf1.tabIds.toList())
        assertEquals("tab-3", leaf1.activeTabId)

        // Move tab-3 from index 0 to index 3 (end)
        val reordered2 = DockTreeManager.moveTab(leaf1, "tab-3", leaf1.id, insertIndex = 3)
        val leaf2 = reordered2 as DockNode.Leaf
        assertEquals(listOf("tab-1", "tab-2", "tab-3"), leaf2.tabIds.toList())
        assertEquals("tab-3", leaf2.activeTabId)

        // Move tab-1 (index 0) to slot 2 (between tab-2 and tab-3)
        val reordered3 = DockTreeManager.moveTab(leaf2, "tab-1", leaf2.id, insertIndex = 2)
        val leaf3 = reordered3 as DockNode.Leaf
        assertEquals(listOf("tab-2", "tab-1", "tab-3"), leaf3.tabIds.toList())
        assertEquals("tab-1", leaf3.activeTabId)
    }

    @Test
    fun testMoveTabBetweenLeavesAndPrune() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2"), initialActiveTabId = "tab-1")
        val split = DockTreeManager.splitLeaf(
            root = root,
            targetLeafId = root.id,
            tabId = "tab-2",
            dropZone = DockDropZone.RIGHT
        ) as DockNode.Split

        val leftLeaf = split.firstChild as DockNode.Leaf
        val rightLeaf = split.secondChild as DockNode.Leaf
        assertEquals(listOf("tab-1"), leftLeaf.tabIds.toList())
        assertEquals(listOf("tab-2"), rightLeaf.tabIds.toList())

        // Move tab-2 from rightLeaf back to leftLeaf at index 0
        // Because rightLeaf is left with 0 tabs, it should be pruned, collapsing the split back to a single leaf!
        val collapsed = DockTreeManager.moveTab(split, "tab-2", leftLeaf.id, insertIndex = 0)
        assertTrue(collapsed is DockNode.Leaf)
        val survivingLeaf = collapsed as DockNode.Leaf
        assertEquals(listOf("tab-2", "tab-1"), survivingLeaf.tabIds.toList())
        assertEquals("tab-2", survivingLeaf.activeTabId)
    }

    @Test
    fun testMoveTabBetweenLeavesMultipleTabs() {
        val root = DockNode.Leaf(initialTabIds = listOf("tab-1", "tab-2", "tab-3", "tab-4"))
        val split = DockTreeManager.splitLeaf(root, root.id, "tab-3", DockDropZone.RIGHT) as DockNode.Split
        val rightLeaf = split.secondChild as DockNode.Leaf
        // Move tab-4 into rightLeaf
        DockTreeManager.moveTab(split, "tab-4", rightLeaf.id, insertIndex = -1)

        val leftLeaf = split.firstChild as DockNode.Leaf
        assertEquals(listOf("tab-1", "tab-2"), leftLeaf.tabIds.toList())
        assertEquals(listOf("tab-3", "tab-4"), rightLeaf.tabIds.toList())

        // Move tab-3 from rightLeaf to leftLeaf at index 1 (between tab-1 and tab-2)
        val afterMove = DockTreeManager.moveTab(split, "tab-3", leftLeaf.id, insertIndex = 1)
        assertTrue(afterMove is DockNode.Split)
        assertEquals(listOf("tab-1", "tab-3", "tab-2"), leftLeaf.tabIds.toList())
        assertEquals(listOf("tab-4"), rightLeaf.tabIds.toList())
        assertEquals("tab-3", leftLeaf.activeTabId)
        assertEquals("tab-4", rightLeaf.activeTabId)
    }
}
