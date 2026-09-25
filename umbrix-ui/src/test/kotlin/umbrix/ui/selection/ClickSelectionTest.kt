package umbrix.ui.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClickSelectionTest {
    @Test
    fun nearbyTimelyClicksReplaceExistingClickCount() {
        val clicks = ClickSequence()
        val largeRow = Int.MAX_VALUE.toLong() + 20L
        assertEquals(1, clicks.press(100, 40f, 20f, largeRow, 300, 10f))
        assertEquals(2, clicks.press(400, 46f, 23f, largeRow, 300, 10f))
        assertEquals(3, clicks.press(650, 42f, 24f, largeRow, 300, 10f))
        assertEquals(1, clicks.press(700, 42f, 24f, largeRow, 300, 10f))
        clicks.cancel()
        assertEquals(1, clicks.press(710, 42f, 24f, largeRow, 300, 10f))
    }

    @Test
    fun slowDistantAndOtherRowClicksReset() {
        val clicks = ClickSequence()
        assertEquals(1, clicks.press(100, 10f, 10f, 4L, 300, 5f))
        assertEquals(1, clicks.press(401, 10f, 10f, 4L, 300, 5f))
        assertEquals(1, clicks.press(500, 16f, 10f, 4L, 300, 5f))
        assertEquals(1, clicks.press(600, 16f, 10f, 5L, 300, 5f))
        assertEquals(2, clicks.press(700, 16f, 10f, 5L, 300, 5f))
    }

    @Test
    fun wordPunctuationWhitespaceAndDisplayedLineBounds() {
        val text = "abc_def  ..!?  xy9"
        assertEquals(ClickSpan(0, 7), clickSpan(text, 4, ClickUnit.WORD))
        assertEquals(ClickSpan(9, 13), clickSpan(text, 10, ClickUnit.WORD))
        assertEquals(ClickSpan(15, 18), clickSpan(text, 16, ClickUnit.WORD))
        assertNull(clickSpan(text, 7, ClickUnit.WORD))
        assertNull(clickSpan(text, text.length, ClickUnit.WORD))
        assertEquals(ClickSpan(0, 0), clickSpan("", 0, ClickUnit.LINE))
        assertEquals(ClickSpan(0, 4096), clickSpan("x".repeat(5000), 4500, ClickUnit.LINE))
        assertNull(clickSpan("x".repeat(5000), 4096, ClickUnit.WORD))
    }

    @Test
    fun wordDragIncludesOriginalSpanAndSnapsBothDirections() {
        val anchor = ClickSpan(4, 8)
        val row = 2_147_483_800L
        assertEquals(
            TextPosition(row, 4) to TextPosition(row, 8),
            extendClickSelection(row, anchor, TextPosition(row, 5), "one word two", ClickUnit.WORD)
        )
        assertEquals(
            TextPosition(row, 4) to TextPosition(row + 1, 14),
            extendClickSelection(row, anchor, TextPosition(row + 1, 7), "one more_words", ClickUnit.WORD)
        )
        assertEquals(
            TextPosition(row, 8) to TextPosition(row - 1, 0),
            extendClickSelection(row, anchor, TextPosition(row - 1, 2), "first next", ClickUnit.WORD)
        )
    }

    @Test
    fun lineDragKeepsWholeDisplayedRowsAndCopiesMappedViews() {
        val line = "x".repeat(4096)
        val (start, end) = extendClickSelection(1L, ClickSpan(0, 4096), TextPosition(0L, 5), "earlier", ClickUnit.LINE)
        assertEquals(TextPosition(1L, 4096), start)
        assertEquals(TextPosition(0L, 0), end)
        val selection = TextSelectionState()
        selection.startPosition = start
        selection.endPosition = end
        assertEquals("earlier\n$line", selection.extractSelectedText(2L, { if (it == 0L) "earlier" else line }))

        val (forwardStart, forwardEnd) = extendClickSelection(0L, ClickSpan(0, 3), TextPosition(1L, 2), "second", ClickUnit.LINE)
        selection.startPosition = forwardStart
        selection.endPosition = forwardEnd
        val sourceRows = mapOf(17L to "one", 90L to "second", 101L to "one", 102L to "second")
        assertEquals("one\nsecond", selection.extractSelectedText(2L, { sourceRows.getValue(longArrayOf(17L, 90L)[it.toInt()]) }))
        assertEquals("one\nsecond", selection.extractSelectedText(2L, { sourceRows.getValue(101L + it) }))
    }

    @Test
    fun doubleClickReplacesPreviousSelectionAndCopiesOnlyTheWord() {
        val selection = TextSelectionState()
        selection.startPosition = TextPosition(0L, 0)
        selection.endPosition = TextPosition(2L, 4)
        val text = "before word_9 after"
        val span = clickSpan(text, 10, ClickUnit.WORD)!!
        selection.startPosition = TextPosition(1L, span.start)
        selection.endPosition = TextPosition(1L, span.endExclusive)

        assertEquals(7 until 13, selection.getSelectedRangeForLine(1L, text.length))
        val physical = mapOf(1L to text, 42L to text, 101L to text)
        assertEquals("word_9", selection.extractSelectedText(3L, { physical.getValue(it) }))
        val filteredRows = longArrayOf(5L, 42L, 88L)
        assertEquals("word_9", selection.extractSelectedText(3L, { physical.getValue(filteredRows[it.toInt()]) }))
        assertEquals("word_9", selection.extractSelectedText(3L, { physical.getValue(100L + it) }))
    }
}