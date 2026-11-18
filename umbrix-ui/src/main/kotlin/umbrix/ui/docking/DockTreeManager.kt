package umbrix.ui.docking

/**
 * Pure and reactive algorithms for navigating, splitting, pruning, and mutating
 * the recursive [DockNode] layout tree.
 */
object DockTreeManager {

    /**
     * Recursively locate the terminal [DockNode.Leaf] containing the specified [tabId].
     */
    fun findLeafForTab(root: DockNode, tabId: String): DockNode.Leaf? {
        return when (root) {
            is DockNode.Leaf -> if (root.tabIds.contains(tabId)) root else null
            is DockNode.Split -> findLeafForTab(root.firstChild, tabId) ?: findLeafForTab(root.secondChild, tabId)
        }
    }

    /**
     * Recursively locate a [DockNode.Leaf] by its unique [leafId].
     */
    fun findLeafById(root: DockNode, leafId: String): DockNode.Leaf? {
        return when (root) {
            is DockNode.Leaf -> if (root.id == leafId) root else null
            is DockNode.Split -> findLeafById(root.firstChild, leafId) ?: findLeafById(root.secondChild, leafId)
        }
    }

    /**
     * Collect all terminal [DockNode.Leaf] instances currently in the tree.
     */
    fun allLeaves(root: DockNode): List<DockNode.Leaf> {
        val result = mutableListOf<DockNode.Leaf>()
        fun collect(node: DockNode) {
            when (node) {
                is DockNode.Leaf -> result.add(node)
                is DockNode.Split -> {
                    collect(node.firstChild)
                    collect(node.secondChild)
                }
            }
        }
        collect(root)
        return result
    }

    /**
     * Replace a node with [targetId] in the tree with [replacement].
     */
    fun replaceNode(root: DockNode, targetId: String, replacement: DockNode): DockNode {
        if (root.id == targetId) return replacement
        if (root is DockNode.Split) {
            if (root.firstChild.id == targetId) {
                root.firstChild = replacement
                return root
            }
            if (root.secondChild.id == targetId) {
                root.secondChild = replacement
                return root
            }
            root.firstChild = replaceNode(root.firstChild, targetId, replacement)
            root.secondChild = replaceNode(root.secondChild, targetId, replacement)
        }
        return root
    }

    /**
     * Split a target leaf by placing [tabId] in a new sibling leaf according to [dropZone].
     * If [tabId] already exists elsewhere in the tree, it is cleanly extracted from its old leaf,
     * and any resulting empty leaves are pruned.
     */
    fun splitLeaf(
        root: DockNode,
        targetLeafId: String,
        tabId: String,
        dropZone: DockDropZone
    ): DockNode {
        val targetLeaf = findLeafById(root, targetLeafId) ?: return root

        if (dropZone == DockDropZone.CENTER_TAB) {
            return moveTab(root, tabId, targetLeafId, -1)
        }

        // Handle edge splits (LEFT, RIGHT, TOP, BOTTOM)
        val sourceLeaf = findLeafForTab(root, tabId)
        if (sourceLeaf != null && sourceLeaf.id != targetLeafId) {
            sourceLeaf.tabIds.remove(tabId)
            if (sourceLeaf.activeTabId == tabId) {
                sourceLeaf.activeTabId = sourceLeaf.tabIds.firstOrNull()
            }
        } else if (sourceLeaf != null && sourceLeaf.id == targetLeafId && targetLeaf.tabIds.size > 1) {
            targetLeaf.tabIds.remove(tabId)
            if (targetLeaf.activeTabId == tabId) {
                targetLeaf.activeTabId = targetLeaf.tabIds.firstOrNull()
            }
        }

        val newLeaf = DockNode.Leaf(initialTabIds = listOf(tabId), initialActiveTabId = tabId)

        val split = when (dropZone) {
            DockDropZone.LEFT -> DockNode.Split(
                orientation = DockOrientation.HORIZONTAL,
                firstChild = newLeaf,
                secondChild = targetLeaf,
                initialSplitRatio = 0.5f
            )
            DockDropZone.RIGHT -> DockNode.Split(
                orientation = DockOrientation.HORIZONTAL,
                firstChild = targetLeaf,
                secondChild = newLeaf,
                initialSplitRatio = 0.5f
            )
            DockDropZone.TOP -> DockNode.Split(
                orientation = DockOrientation.VERTICAL,
                firstChild = newLeaf,
                secondChild = targetLeaf,
                initialSplitRatio = 0.5f
            )
            DockDropZone.BOTTOM -> DockNode.Split(
                orientation = DockOrientation.VERTICAL,
                firstChild = targetLeaf,
                secondChild = newLeaf,
                initialSplitRatio = 0.5f
            )
            DockDropZone.CENTER_TAB -> error("Handled above")
        }

        val updatedTree = replaceNode(root, targetLeafId, split)
        return pruneEmptyLeaves(updatedTree) ?: updatedTree
    }

    /**
     * Remove a tab from the tree, auto-pruning empty leaves and collapsing orphaned splits.
     * Returns null if the entire tree becomes empty.
     */
    fun removeTab(root: DockNode, tabId: String): DockNode? {
        val leaf = findLeafForTab(root, tabId) ?: return root
        leaf.tabIds.remove(tabId)
        if (leaf.activeTabId == tabId) {
            leaf.activeTabId = leaf.tabIds.firstOrNull()
        }
        return pruneEmptyLeaves(root)
    }

    /**
     * Recursively prune leaves that have no remaining tabs. When a leaf becomes empty,
     * its parent [DockNode.Split] collapses, elevating the surviving sibling.
     */
    fun pruneEmptyLeaves(node: DockNode): DockNode? {
        return when (node) {
            is DockNode.Leaf -> if (node.tabIds.isEmpty()) null else node
            is DockNode.Split -> {
                val prunedFirst = pruneEmptyLeaves(node.firstChild)
                val prunedSecond = pruneEmptyLeaves(node.secondChild)
                when {
                    prunedFirst == null && prunedSecond == null -> null
                    prunedFirst == null -> prunedSecond
                    prunedSecond == null -> prunedFirst
                    else -> {
                        node.firstChild = prunedFirst
                        node.secondChild = prunedSecond
                        node
                    }
                }
            }
        }
    }

    /**
     * Move a tab to a specific leaf at an optional index.
     * Supports both reordering within the same leaf and moving tabs across split leaves.
     * Empty leaves are automatically pruned, cleanly collapsing split panes.
     */
    fun moveTab(root: DockNode, tabId: String, targetLeafId: String, insertIndex: Int = -1): DockNode {
        val sourceLeaf = findLeafForTab(root, tabId)
        val targetLeaf = findLeafById(root, targetLeafId) ?: return root

        if (sourceLeaf?.id == targetLeafId) {
            // Reordering within the same leaf
            val currentIndex = targetLeaf.tabIds.indexOf(tabId)
            if (currentIndex >= 0) {
                targetLeaf.tabIds.removeAt(currentIndex)
                val targetIndex = if (insertIndex < 0 || insertIndex > targetLeaf.tabIds.size) {
                    targetLeaf.tabIds.size
                } else if (insertIndex > currentIndex) {
                    insertIndex - 1
                } else {
                    insertIndex
                }
                targetLeaf.tabIds.add(targetIndex.coerceIn(0, targetLeaf.tabIds.size), tabId)
                targetLeaf.activeTabId = tabId
            }
            return root
        }

        sourceLeaf?.let {
            it.tabIds.remove(tabId)
            if (it.activeTabId == tabId) {
                it.activeTabId = it.tabIds.firstOrNull()
            }
        }

        val boundedIndex = if (insertIndex in 0..targetLeaf.tabIds.size) insertIndex else targetLeaf.tabIds.size
        targetLeaf.tabIds.add(boundedIndex, tabId)
        targetLeaf.activeTabId = tabId

        return pruneEmptyLeaves(root) ?: targetLeaf
    }

    /**
     * Add a tab to a specific leaf, making it the active tab in that leaf.
     */
    fun addTabToLeaf(root: DockNode, targetLeafId: String, tabId: String): DockNode {
        val targetLeaf = findLeafById(root, targetLeafId)
        if (targetLeaf != null) {
            if (!targetLeaf.tabIds.contains(tabId)) {
                targetLeaf.tabIds.add(tabId)
            }
            targetLeaf.activeTabId = tabId
            return root
        }
        val firstLeaf = allLeaves(root).firstOrNull()
        if (firstLeaf != null) {
            if (!firstLeaf.tabIds.contains(tabId)) {
                firstLeaf.tabIds.add(tabId)
            }
            firstLeaf.activeTabId = tabId
            return root
        }
        return DockNode.Leaf(initialTabIds = listOf(tabId), initialActiveTabId = tabId)
    }

    /**
     * Equalize all split ratios across the tree to 0.50f (50/50 balance).
     */
    fun equalizeSplits(node: DockNode) {
        if (node is DockNode.Split) {
            node.splitRatio = 0.5f
            equalizeSplits(node.firstChild)
            equalizeSplits(node.secondChild)
        }
    }
}
