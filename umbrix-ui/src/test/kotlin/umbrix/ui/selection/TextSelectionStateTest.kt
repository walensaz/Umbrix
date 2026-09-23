package umbrix.ui.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextSelectionStateTest {

    @Test
    fun testSingleLineSelectionRange() {
        val state = TextSelectionState()
        assertFalse(state.hasSelection())

        state.startPosition = TextPosition(10L, 5)
        state.endPosition = TextPosition(10L, 20)
        assertTrue(state.hasSelection())

        // Line before selection
        assertNull(state.getSelectedRangeForLine(9L, 100))

        // Line after selection
        assertNull(state.getSelectedRangeForLine(11L, 100))

        // Exactly on selected line
        val range = state.getSelectedRangeForLine(10L, 100)
        assertNotNull(range)
        assertEquals(5 until 20, range)
    }

    @Test
    fun testMultiLineSelectionRange() {
        val state = TextSelectionState()
        state.startPosition = TextPosition(10L, 15)
        state.endPosition = TextPosition(12L, 8)

        // Line 10 (first line)
        assertEquals(15 until 50, state.getSelectedRangeForLine(10L, 50))

        // Line 11 (middle line)
        assertEquals(0 until 60, state.getSelectedRangeForLine(11L, 60))

        // Line 12 (last line)
        assertEquals(0 until 8, state.getSelectedRangeForLine(12L, 40))

        // Outside bounds
        assertNull(state.getSelectedRangeForLine(9L, 50))
        assertNull(state.getSelectedRangeForLine(13L, 50))
    }

    @Test
    fun testReverseDragNormalization() {
        val state = TextSelectionState()
        // Dragged backwards from line 15 to line 10
        state.startPosition = TextPosition(15L, 20)
        state.endPosition = TextPosition(10L, 5)

        val bounds = state.normalizedRange()
        assertNotNull(bounds)
        assertEquals(10L, bounds.first.lineIndex)
        assertEquals(5, bounds.first.charOffset)
        assertEquals(15L, bounds.second.lineIndex)
        assertEquals(20, bounds.second.charOffset)
    }
}
