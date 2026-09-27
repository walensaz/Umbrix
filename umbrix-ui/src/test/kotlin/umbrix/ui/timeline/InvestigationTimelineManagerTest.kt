package umbrix.ui.timeline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvestigationTimelineManagerTest {

    @Test
    fun testAddEventAndClamp() {
        val manager = InvestigationTimelineManager()
        val longSnippet = "X".repeat(5000)
        val event = manager.addEvent(
            sourceDocumentPath = "/var/log/api.log",
            sourceDocumentTitle = "api.log",
            lineIndex = 42L,
            timestampMicros = 1000L,
            lineContent = longSnippet,
            tag = EventTag.ROOT_CAUSE,
            userNote = "Memory explosion"
        )

        assertEquals(1, manager.events.size)
        assertEquals(4096, event.lineContent.length)
        assertEquals(EventTag.ROOT_CAUSE, event.tag)
        assertEquals("Memory explosion", event.userNote)
    }

    @Test
    fun testSortChronologicallyAcrossMultiFiles() {
        val manager = InvestigationTimelineManager()
        val e3 = manager.addEvent("/var/log/db.log", "db.log", 300L, 3000L, "DB crash")
        val e1 = manager.addEvent("/var/log/gw.log", "gw.log", 100L, 1000L, "Request arrives")
        val e4 = manager.addEvent("/var/log/sys.log", "sys.log", 400L, 0L, "Unparseable line")
        val e2 = manager.addEvent("/var/log/auth.log", "auth.log", 200L, 2000L, "Auth success")

        // Before sort: order is e3, e1, e4, e2
        assertEquals(listOf(e3.id, e1.id, e4.id, e2.id), manager.events.map { it.id })

        // After sort: order is e1 (1000), e2 (2000), e3 (3000), e4 (0L at end)
        manager.sortChronologically()
        assertEquals(listOf(e1.id, e2.id, e3.id, e4.id), manager.events.map { it.id })
    }

    @Test
    fun testMultiRowSelection() {
        val manager = InvestigationTimelineManager()
        val e1 = manager.addEvent("f1", "f1", 1L, 10L, "line 1")
        val e2 = manager.addEvent("f2", "f2", 2L, 20L, "line 2")
        val e3 = manager.addEvent("f3", "f3", 3L, 30L, "line 3")
        val e4 = manager.addEvent("f4", "f4", 4L, 40L, "line 4")

        assertFalse(manager.isSelected(e1.id))
        manager.toggleSelection(e1.id)
        assertTrue(manager.isSelected(e1.id))

        // Range select e2 to e4
        manager.selectRange(e2.id, e4.id)
        assertTrue(manager.isSelected(e2.id))
        assertTrue(manager.isSelected(e3.id))
        assertTrue(manager.isSelected(e4.id))
        assertEquals(4, manager.selectedEventIds.size)

        manager.deselectAll()
        assertEquals(0, manager.selectedEventIds.size)

        manager.selectAll()
        assertEquals(4, manager.selectedEventIds.size)
    }

    @Test
    fun testBatchMovement() {
        val manager = InvestigationTimelineManager()
        val e1 = manager.addEvent("f1", "f1", 1L, 10L, "line 1")
        val e2 = manager.addEvent("f2", "f2", 2L, 20L, "line 2")
        val e3 = manager.addEvent("f3", "f3", 3L, 30L, "line 3")
        val e4 = manager.addEvent("f4", "f4", 4L, 40L, "line 4")
        val e5 = manager.addEvent("f5", "f5", 5L, 50L, "line 5")

        // Select e3 and e4
        manager.toggleSelection(e3.id)
        manager.toggleSelection(e4.id)

        // Move batch up: e3 and e4 should swap with e2
        // Expected order: e1, e3, e4, e2, e5
        manager.moveSelectedUp()
        assertEquals(listOf(e1.id, e3.id, e4.id, e2.id, e5.id), manager.events.map { it.id })

        // Move batch down: e3 and e4 should swap with e2 back
        // Expected order: e1, e2, e3, e4, e5
        manager.moveSelectedDown()
        assertEquals(listOf(e1.id, e2.id, e3.id, e4.id, e5.id), manager.events.map { it.id })

        // Move batch to top: e3 and e4 should be at top
        // Expected order: e3, e4, e1, e2, e5
        manager.moveSelectedToTop()
        assertEquals(listOf(e3.id, e4.id, e1.id, e2.id, e5.id), manager.events.map { it.id })

        // Move batch to bottom: e3 and e4 should be at bottom
        // Expected order: e1, e2, e5, e3, e4
        manager.moveSelectedToBottom()
        assertEquals(listOf(e1.id, e2.id, e5.id, e3.id, e4.id), manager.events.map { it.id })
    }

    @Test
    fun testBatchTagAndRemove() {
        val manager = InvestigationTimelineManager()
        val e1 = manager.addEvent("f1", "f1", 1L, 10L, "line 1")
        val e2 = manager.addEvent("f2", "f2", 2L, 20L, "line 2")
        val e3 = manager.addEvent("f3", "f3", 3L, 30L, "line 3")

        manager.toggleSelection(e1.id)
        manager.toggleSelection(e3.id)

        manager.setBatchTag(EventTag.CASCADE)
        assertEquals(EventTag.CASCADE, manager.events.first { it.id == e1.id }.tag)
        assertEquals(EventTag.ANOMALY, manager.events.first { it.id == e2.id }.tag)
        assertEquals(EventTag.CASCADE, manager.events.first { it.id == e3.id }.tag)

        manager.removeSelected()
        assertEquals(1, manager.events.size)
        assertEquals(e2.id, manager.events[0].id)
        assertEquals(0, manager.selectedEventIds.size)
    }
}
