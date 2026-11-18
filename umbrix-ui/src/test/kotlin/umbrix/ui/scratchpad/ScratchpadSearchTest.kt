package umbrix.ui.scratchpad

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import umbrix.ui.screen.MainScreenSearchController
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.state.WorkspaceTab

class ScratchpadSearchTest {

    @Test
    fun testComputeLineStartOffsets() {
        // Empty text
        val emptyOffsets = MainScreenSearchController.computeLineStartOffsets("")
        assertEquals(listOf(0), emptyOffsets)

        // Single line
        val singleLineOffsets = MainScreenSearchController.computeLineStartOffsets("Single line without newline")
        assertEquals(listOf(0), singleLineOffsets)

        // Multi-line with simple newlines
        val text = "Line 1\nLine 2\nLine 3"
        // Line 1: 0..5, \n at 6 -> Line 2 starts at 7
        // Line 2: 7..12, \n at 13 -> Line 3 starts at 14
        val offsets = MainScreenSearchController.computeLineStartOffsets(text)
        assertEquals(listOf(0, 7, 14), offsets)

        // Trailing newline
        val trailing = "Hello\nWorld\n"
        // Hello: 0..4, \n at 5 -> World starts at 6
        // World: 6..10, \n at 11 -> empty line starts at 12
        val trailingOffsets = MainScreenSearchController.computeLineStartOffsets(trailing)
        assertEquals(listOf(0, 6, 12), trailingOffsets)

        // Consecutive newlines
        val consecutive = "A\n\nB"
        // A: 0, \n at 1 -> line 1 starts at 2
        // empty line: \n at 2 -> line 2 starts at 3
        val consecutiveOffsets = MainScreenSearchController.computeLineStartOffsets(consecutive)
        assertEquals(listOf(0, 2, 3), consecutiveOffsets)
    }

    @Test
    fun testFindLineIndexForOffset() {
        val starts = listOf(0, 10, 25, 40)

        // Negative offset clamped to 0
        assertEquals(0, MainScreenSearchController.findLineIndexForOffset(starts, -5))
        assertEquals(0, MainScreenSearchController.findLineIndexForOffset(starts, 0))

        // Inside first line
        assertEquals(0, MainScreenSearchController.findLineIndexForOffset(starts, 5))
        assertEquals(0, MainScreenSearchController.findLineIndexForOffset(starts, 9))

        // Exact line 1 start and inside line 1
        assertEquals(1, MainScreenSearchController.findLineIndexForOffset(starts, 10))
        assertEquals(1, MainScreenSearchController.findLineIndexForOffset(starts, 24))

        // Exact line 2 start and inside line 2
        assertEquals(2, MainScreenSearchController.findLineIndexForOffset(starts, 25))
        assertEquals(2, MainScreenSearchController.findLineIndexForOffset(starts, 39))

        // Line 3 start and past end of document
        assertEquals(3, MainScreenSearchController.findLineIndexForOffset(starts, 40))
        assertEquals(3, MainScreenSearchController.findLineIndexForOffset(starts, 100))
    }

    @Test
    fun testFindNeedleIntervalsModes() {
        val content = "ERROR Error error err Err"

        // Case-sensitive exact
        val caseSensitive = findNeedleIntervals(
            rawText = content,
            needles = listOf("ERROR"),
            isMatchCase = true,
            isWholeWord = false,
            isRegex = false
        )
        assertEquals(1, caseSensitive.size)
        assertEquals(0 to 5, caseSensitive.first())

        // Case-insensitive exact
        val caseInsensitive = findNeedleIntervals(
            rawText = content,
            needles = listOf("error"),
            isMatchCase = false,
            isWholeWord = false,
            isRegex = false
        )
        assertEquals(3, caseInsensitive.size)

        // Whole word mode
        val wholeWord = findNeedleIntervals(
            rawText = content,
            needles = listOf("err"),
            isMatchCase = false,
            isWholeWord = true,
            isRegex = false
        )
        // Should only match "err" and "Err", not "ERROR", "Error", "error"
        assertEquals(2, wholeWord.size)

        // Regex mode
        val regexText = "2026-10-11 [WARN] Code: 404 and 500"
        val regexIntervals = findNeedleIntervals(
            rawText = regexText,
            needles = listOf("\\b\\d{3}\\b"),
            isMatchCase = false,
            isWholeWord = false,
            isRegex = true
        )
        assertEquals(2, regexIntervals.size)
        assertEquals(regexText.indexOf("404") to regexText.indexOf("404") + 3, regexIntervals[0])
        assertEquals(regexText.indexOf("500") to regexText.indexOf("500") + 3, regexIntervals[1])
    }

    @Test
    fun testNavigateScratchpadSearchForwardAndWrap() {
        val text = "alpha 1\nalpha 2\nalpha 3"
        val scratchpad = ScratchpadSession(
            initialTitle = "scratch.log",
            initialContent = text
        )
        val tab = WorkspaceTab(
            session = null,
            filePath = "scratchpad://scratch-1",
            scratchpadSession = scratchpad
        )

        // Trigger synchronous search population via runBlocking
        runBlocking {
            MainScreenSearchController.triggerScratchpadSearch(
                tab = tab,
                coroutineScope = this,
                queryText = "alpha",
                immediate = true,
                onSearchErrorMessageChange = {}
            )
            tab.searchJob?.join()
        }

        assertNotNull(tab.searchResult)
        assertEquals(3L, tab.searchResult?.totalMatches())
        assertNull(tab.selectedLineIndex)
        assertEquals(-1, tab.selectedMatchColumn)

        // First forward navigation jumps to match 1 on line 0
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = true)
        assertEquals(0L, tab.selectedLineIndex)
        assertEquals(0, tab.selectedMatchColumn)
        assertFalse(tab.searchWrapPromptPending)

        // Step forward -> match 2 on line 1
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = true)
        assertEquals(1L, tab.selectedLineIndex)
        assertEquals(0, tab.selectedMatchColumn)
        assertFalse(tab.searchWrapPromptPending)

        // Step forward -> match 3 on line 2
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = true)
        assertEquals(2L, tab.selectedLineIndex)
        assertEquals(0, tab.selectedMatchColumn)
        assertFalse(tab.searchWrapPromptPending)

        // Step forward at end -> prompts for wrap
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = true)
        assertTrue(tab.searchWrapPromptPending)
        assertEquals("Reached end. Press Enter to wrap to top.", tab.searchWrapIndicator)
        assertEquals(2L, tab.selectedLineIndex) // Line unchanged on prompt

        // Step forward again -> confirms wrap to top (match 1 on line 0)
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = true)
        assertFalse(tab.searchWrapPromptPending)
        assertEquals(0L, tab.selectedLineIndex)
        assertEquals(0, tab.selectedMatchColumn)
        assertEquals("Wrapped to top of scratchpad", tab.searchWrapIndicator)
    }

    @Test
    fun testNavigateScratchpadSearchBackwardAndWrap() {
        val text = "alpha 1\nalpha 2\nalpha 3"
        val scratchpad = ScratchpadSession(
            initialTitle = "scratch.log",
            initialContent = text
        )
        val tab = WorkspaceTab(
            session = null,
            filePath = "scratchpad://scratch-1",
            scratchpadSession = scratchpad
        )

        runBlocking {
            MainScreenSearchController.triggerScratchpadSearch(
                tab = tab,
                coroutineScope = this,
                queryText = "alpha",
                immediate = true,
                onSearchErrorMessageChange = {}
            )
            tab.searchJob?.join()
        }

        assertNull(tab.selectedLineIndex)
        assertEquals(-1, tab.selectedMatchColumn)

        // Step backward at start -> prompts for wrap
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = false)
        assertTrue(tab.searchWrapPromptPending)
        assertEquals("Reached start. Press Shift+Enter to wrap to bottom.", tab.searchWrapIndicator)
        assertNull(tab.selectedLineIndex)

        // Step backward again -> wraps to bottom (match 3 on line 2)
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = false)
        assertFalse(tab.searchWrapPromptPending)
        assertEquals(2L, tab.selectedLineIndex)
        assertEquals(0, tab.selectedMatchColumn)
        assertEquals("Wrapped to bottom of scratchpad", tab.searchWrapIndicator)

        // Step backward from line 2 -> moves to line 1
        MainScreenSearchController.navigateScratchpadSearch(tab, forward = false)
        assertEquals(1L, tab.selectedLineIndex)
        assertEquals(0, tab.selectedMatchColumn)
    }

    @Test
    fun testTriggerScratchpadSearchBlankQueryResetsState() {
        val scratchpad = ScratchpadSession(initialContent = "Sample log text")
        val tab = WorkspaceTab(
            session = null,
            filePath = "scratchpad://scratch-1",
            scratchpadSession = scratchpad
        )

        var errorMessage: String? = "Initial"
        runBlocking {
            MainScreenSearchController.triggerScratchpadSearch(
                tab = tab,
                coroutineScope = this,
                queryText = "",
                immediate = true,
                onSearchErrorMessageChange = { errorMessage = it }
            )
        }

        assertNull(tab.searchResult)
        assertNull(tab.rawSearchResult)
        assertEquals("", tab.activeSearchQuery)
        assertFalse(tab.isSearching)
        assertFalse(tab.isFilteredView)
        assertEquals(-1, tab.selectedMatchColumn)
        assertNull(errorMessage)
    }

    @Test
    fun testTriggerScratchpadSearchInvalidRegexReportsError() {
        val scratchpad = ScratchpadSession(initialContent = "Sample log text")
        val tab = WorkspaceTab(
            session = null,
            filePath = "scratchpad://scratch-1",
            scratchpadSession = scratchpad
        )
        tab.isSearchRegex = true

        var reportedError: String? = null
        runBlocking {
            MainScreenSearchController.triggerScratchpadSearch(
                tab = tab,
                coroutineScope = this,
                queryText = "[invalid-regex(",
                immediate = true,
                onSearchErrorMessageChange = { reportedError = it }
            )
            tab.searchJob?.join()
        }

        assertNotNull(reportedError)
        assertNull(tab.searchResult)
        assertFalse(tab.isSearching)
    }
}
