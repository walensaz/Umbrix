package umbrix.ui.docking

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import umbrix.core.session.LogSession
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.ui.CustomDockView
import umbrix.plugin.api.ui.DockViewFactory
import umbrix.ui.scratchpad.ScratchpadManager
import umbrix.ui.screen.MainScreenTabCoordinator
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.timeline.InvestigationTimelineManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CustomDockViewTabRoutingTest {

    private fun createCoordinator(
        openTabs: androidx.compose.runtime.snapshots.SnapshotStateList<WorkspaceTab>,
        initialDockTree: DockNode = DockNode.Leaf()
    ): Pair<MainScreenTabCoordinator, () -> DockNode> {
        var activeTabIndex = 0
        var dockTree = initialDockTree
        var activeLeafId = dockTree.id
        var statusMessage = ""
        var isIndexing = false

        val coordinator = MainScreenTabCoordinator(
            openTabs = openTabs,
            detachedTabs = mutableStateListOf(),
            scratchpadManager = ScratchpadManager(),
            timelineManager = InvestigationTimelineManager(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            getActiveTabIndex = { activeTabIndex },
            setActiveTabIndex = { activeTabIndex = it },
            getDockTree = { dockTree },
            setDockTree = { dockTree = it },
            getActiveLeafId = { activeLeafId },
            setActiveLeafId = { activeLeafId = it },
            setStatusMessage = { statusMessage = it },
            setIsIndexingActive = { isIndexing = it }
        )
        return Pair(coordinator, { dockTree })
    }

    private fun createFakeFactory(targetViewId: String): DockViewFactory {
        return object : DockViewFactory {
            override fun viewId(): String = targetViewId
            override fun displayName(): String = "Display $targetViewId"
            override fun createView(session: PluginSessionView): CustomDockView = object : CustomDockView {
                override fun viewId(): String = targetViewId
                override fun title(): String = "Title $targetViewId"
            }
        }
    }

    @Test
    fun testOpenCustomDockViewDeduplication() {
        val tempFile = File.createTempFile("umbrix-dedupe-", ".log")
        tempFile.deleteOnExit()
        Files.write(tempFile.toPath(), "test line 1\n".toByteArray(StandardCharsets.UTF_8))

        val session = LogSession.open(tempFile.toPath(), false)
        try {
            val openTabs = mutableStateListOf<WorkspaceTab>()
            val initialLeaf = DockNode.Leaf()
            val (coordinator, _) = createCoordinator(openTabs, initialLeaf)

            // Setup initial tab so there is an active session
            val initialTab = WorkspaceTab(
                session = session,
                filePath = tempFile.absolutePath,
                initialTopVisibleLine = 0L
            )
            openTabs.add(initialTab)
            initialLeaf.tabIds.add(initialTab.id)
            initialLeaf.activeTabId = initialTab.id

            val factory = createFakeFactory("hprof.histogram")

            // First open: adds a new tab
            coordinator.openCustomDockView(factory, DockDropZone.CENTER_TAB)
            assertEquals(2, openTabs.size)
            assertEquals("plugin://hprof.histogram", openTabs[1].filePath)
            assertNotNull(openTabs[1].customDockView)
            assertEquals("hprof.histogram", openTabs[1].customDockView?.viewId())

            // Second open for the same viewId + session: de-dupes and does not open a second tab
            coordinator.openCustomDockView(factory, DockDropZone.RIGHT)
            assertEquals(2, openTabs.size, "Must de-dupe tabs for the same viewId + session")
        } finally {
            session.close()
        }
    }

    @Test
    fun testShowRawRecordsEscapeHatch() {
        val tempFile = File.createTempFile("umbrix-raw-escape-", ".log")
        tempFile.deleteOnExit()
        Files.write(tempFile.toPath(), "test line 1\ntest line 2\n".toByteArray(StandardCharsets.UTF_8))

        val session = LogSession.open(tempFile.toPath(), false)
        try {
            val openTabs = mutableStateListOf<WorkspaceTab>()
            val initialLeaf = DockNode.Leaf()
            val (coordinator, getDockTree) = createCoordinator(openTabs, initialLeaf)

            // Setup active tab as a custom dock view with this session
            val factory = createFakeFactory("hprof.histogram")
            val view = factory.createView(session.pluginSessionView())
            val dockTab = WorkspaceTab(
                session = session,
                filePath = tempFile.absolutePath,
                initialCustomDockView = view
            )
            openTabs.add(dockTab)
            initialLeaf.tabIds.add(dockTab.id)
            initialLeaf.activeTabId = dockTab.id

            // Trigger raw records escape hatch
            coordinator.showRawRecordsForActiveSession()

            // Must now have 2 tabs: the original custom dock view, and a new raw tab sharing the same session
            assertEquals(2, openTabs.size)
            val rawTab = openTabs[1]
            assertEquals(session, rawTab.session)
            assertNull(rawTab.customDockView, "Raw tab must have null customDockView")

            // The dock tree must be split to show raw tab on the right
            val tree = getDockTree()
            assertTrue(tree is DockNode.Split, "Dock tree must be split horizontally")
            val split = tree as DockNode.Split
            assertEquals(DockOrientation.HORIZONTAL, split.orientation)

            // Second call: must de-dupe and not create a third tab
            coordinator.showRawRecordsForActiveSession()
            assertEquals(2, openTabs.size, "Must de-dupe existing raw tab")
        } finally {
            session.close()
        }
    }
}
