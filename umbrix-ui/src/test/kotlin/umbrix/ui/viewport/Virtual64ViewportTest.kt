package umbrix.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Virtual64ViewportTest {

    @Test
    fun testSafetyClampEnforcedOnGiantLines() {
        val giantLine = "A".repeat(5000)
        val clamped = ViewportState.clampForDisplay(giantLine)
        assertTrue(clamped.contains("[… +904 chars clamped]"))
        assertEquals(ViewportState.MAX_SHAPED_CHARS, clamped.indexOf(" [… +"))
    }

    @Test
    fun testNormalLineUnmodified() {
        val normalLine = "2026-09-21 14:02:10.123 [INFO] System started."
        assertEquals(normalLine, ViewportState.clampForDisplay(normalLine))
    }

    @Test
    fun testExactBoundaryLineNotClamped() {
        val exactBoundaryLine = "B".repeat(ViewportState.MAX_SHAPED_CHARS)
        assertEquals(exactBoundaryLine, ViewportState.clampForDisplay(exactBoundaryLine))
    }
}
