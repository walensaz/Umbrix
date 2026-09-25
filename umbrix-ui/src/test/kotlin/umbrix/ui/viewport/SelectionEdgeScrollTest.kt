package umbrix.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionEdgeScrollTest {
    @Test
    fun edgeVelocityHasDeadZoneAndSpeedsUpTowardEdges() {
        assertEquals(0f, SelectionEdgeScroll.edgeVelocity(100f, 400f))
        assertEquals(0f, SelectionEdgeScroll.edgeVelocity(48f, 400f))
        assertTrue(SelectionEdgeScroll.edgeVelocity(0f, 400f) < SelectionEdgeScroll.edgeVelocity(24f, 400f))
        assertTrue(SelectionEdgeScroll.edgeVelocity(400f, 400f) > SelectionEdgeScroll.edgeVelocity(376f, 400f))
    }

    @Test
    fun reverseAndDiagonalDirectionsAreIndependent() {
        val left = SelectionEdgeScroll.edgeVelocity(0f, 400f)
        val right = SelectionEdgeScroll.edgeVelocity(400f, 400f)
        val up = SelectionEdgeScroll.edgeVelocity(0f, 200f)
        val down = SelectionEdgeScroll.edgeVelocity(200f, 200f)
        assertTrue(left < 0f && up < 0f && right > 0f && down > 0f)
        assertEquals(-left, right)
        assertEquals(-up, down)
    }

    @Test
    fun horizontalAndVerticalCoordinatesClampAtBounds() {
        assertEquals(0f, SelectionEdgeScroll.clampHorizontal(3f, -100f, 100))
        assertEquals(100f, SelectionEdgeScroll.clampHorizontal(99f, 100f, 100))
        assertEquals(0.25f, SelectionEdgeScroll.clampHorizontal(0f, 0.25f, 100))
        assertEquals(0L to 0f, SelectionEdgeScroll.advanceVertical(0L, 0f, -50f, 20f, 100L, 10))
        assertEquals(90L to 0f, SelectionEdgeScroll.advanceVertical(90L, 0f, 50f, 20f, 100L, 10))
        assertEquals(2L to 5f, SelectionEdgeScroll.advanceVertical(1L, 15f, 10f, 20f, 100L, 10))
        assertEquals(1L to 5f, SelectionEdgeScroll.advanceVertical(2L, 15f, -30f, 20f, 100L, 10))
    }

    @Test
    fun verticalMovementRetainsLongPrecisionNearHugeDocumentEnd() {
        val total = Int.MAX_VALUE.toLong() + 10_000L
        val top = total - 11L
        assertEquals((total - 10L) to 0f,
            SelectionEdgeScroll.advanceVertical(top, 0f, 80f, 20f, total, 10))
        assertEquals((top - 2L) to 10f,
            SelectionEdgeScroll.advanceVertical(top, 0f, -30f, 20f, total, 10))
        assertEquals(Long.MAX_VALUE, SelectionEdgeScroll.virtualRowAt(Long.MAX_VALUE - 2L, 3, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE - 1L, SelectionEdgeScroll.virtualRowAt(Long.MAX_VALUE - 2L, 1, Long.MAX_VALUE))
    }
}
