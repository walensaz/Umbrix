package umbrix.ui.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    @Test
    fun reverseMultilineCopyUsesDisplayedRowOrder() {
        val state = TextSelectionState()
        state.startPosition = TextPosition(2L, 3)
        state.endPosition = TextPosition(0L, 2)
        val rows = listOf("abcdef", "middle", "12345")

        assertEquals("cdef\nmiddle\n123", state.extractSelectedText(rows.size.toLong(), { rows[it.toInt()] }))
        assertEquals(2 until 6, state.getSelectedRangeForLine(0L, rows[0].length))
        assertEquals(0 until 3, state.getSelectedRangeForLine(2L, rows[2].length))
    }

    @Test
    fun filteredAndTemporalCopyResolveVirtualRows() {
        val state = TextSelectionState()
        state.startPosition = TextPosition(0L, 1)
        state.endPosition = TextPosition(2L, 2)
        val physical = mapOf(4L to "four", 18L to "eighteen", 91L to "ninety")
        val matchedLines = longArrayOf(4L, 18L, 91L)
        assertEquals("our\neighteen\nni", state.extractSelectedText(3L, { physical.getValue(matchedLines[it.toInt()]) }))

        val temporal = mapOf(100L to "first", 101L to "second", 102L to "third")
        assertEquals("irst\nsecond\nth", state.extractSelectedText(3L, { temporal.getValue(100L + it) }))
    }

    @Test
    fun largeCopyIsRejectedBeforeReadingUnboundedRows() {
        val state = TextSelectionState()
        state.startPosition = TextPosition(0L, 0)
        state.endPosition = TextPosition(20_000L, 1)
        assertFailsWith<SelectionTooLargeException> {
            state.extractSelectedText(30_000L, { error("Should not read log rows") })
        }

        state.endPosition = TextPosition(2L, 1)
        assertFailsWith<SelectionTooLargeException> {
            state.extractSelectedText(3L, { "abc" }, maxCopyChars = 5)
        }
    }
}
