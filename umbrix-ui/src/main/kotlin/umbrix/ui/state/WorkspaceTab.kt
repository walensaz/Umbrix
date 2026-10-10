package umbrix.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.ui.bookmarks.BookmarkManager
import umbrix.ui.scratchpad.ScratchpadSession

/**
 * State container for an active document tab with reactive Compose state properties.
 */
class WorkspaceTab(
    val session: LogSession? = null,
    val filePath: String,
    val scratchpadSession: ScratchpadSession? = null,
    val bookmarkManager: BookmarkManager = BookmarkManager(),
    initialTopVisibleLine: Long = 0L,
    initialSearchQuery: String = "",
    initialSearchResult: SearchResult? = null,
    initialIsFilteredView: Boolean = false,
    initialSelectedLineIndex: Long? = null,
    val id: String = java.util.UUID.randomUUID().toString()
) {
    val isScratchpad: Boolean get() = scratchpadSession != null
    val isTimelineTab: Boolean get() = filePath.startsWith("timeline://")
    val title: String get() = if (isTimelineTab) "Incident Timeline" else scratchpadSession?.title ?: session?.fileName() ?: java.io.File(filePath).name
    var topVisibleLine: Long by mutableStateOf(initialTopVisibleLine)
    var subLineScrollOffsetPx: Float by mutableStateOf(0f)
    var searchQuery: String by mutableStateOf(initialSearchQuery)
    var activeSearchQuery: String by mutableStateOf("")
    var searchResult: SearchResult? by mutableStateOf(initialSearchResult)
    var rawSearchResult: SearchResult? by mutableStateOf(null)
    var isSearching: Boolean by mutableStateOf(false)
    var isFilteredView: Boolean by mutableStateOf(initialIsFilteredView)
    var isBookmarkFilterActive: Boolean by mutableStateOf(false)
    var selectedLineIndex: Long? by mutableStateOf(initialSelectedLineIndex)
    var searchJob: Job? = null

    /**
     * Character-column offset of the currently active match occurrence within [selectedLineIndex].
     * -1 means the column has not yet been resolved (e.g. on a fresh search before the first
     * navigation step). Updated by [umbrix.ui.screen.UmbrixMainScreen.navigateSearch] each time
     * the user steps forward or backward through occurrences.
     */
    var selectedMatchColumn: Int by mutableStateOf(-1)

    /**
     * Monotone counter incremented only when the user explicitly navigates between search matches
     * (Enter / F3 / ↑↓ search buttons). [umbrix.ui.viewport.LogViewportCanvas] watches this key
     * in a LaunchedEffect to trigger the horizontal scroll-to-match animation. Manual vertical
     * scrolling never increments this counter, so it never hijacks the horizontal scroll position.
     */
    var searchNavigationGeneration: Int by mutableStateOf(0)

    // --- Search Modes (Sublime / IntelliJ style) ---
    var isSearchMatchCase: Boolean by mutableStateOf(false)
    var isSearchWholeWord: Boolean by mutableStateOf(false)
    var isSearchRegex: Boolean by mutableStateOf(false)

    /**
     * Set to true when search navigation reaches the end/start of the document and prompts
     * the user to press Enter/Shift+Enter to confirm wrapping.
     */
    var searchWrapPromptPending: Boolean by mutableStateOf(false)
    var searchWrapIndicator: String? by mutableStateOf(null)

    /**
     * Written by LogViewportCanvas on each layout pass so that navigation logic in
     * UmbrixMainScreen can offset topVisibleLine by half this value to center matches.
     */
    var visibleRowCount: Int by mutableStateOf(20)

    /**
     * Running maximum pixel width of any line rendered so far in this tab. Written by
     * LogViewportCanvas in onTextLayout; only ever grows. Applied as a widthIn(min=) floor
     * on the horizontally-scrollable column so that vertical scrolling never causes the
     * ScrollState.maxValue to shrink and yank the user's horizontal position.
     * Reset to 0f when the view mode changes (filtered ↔ full ↔ temporal) so a stale
     * wide estimate from the previous mode does not over-extend the new one.
     */
    var maxObservedContentWidthPx: Float by mutableStateOf(0f)

    var indexingProgress: Double by mutableStateOf(session?.indexingProgressPercent() ?: 100.0)
    var indexingStatus: String by mutableStateOf(session?.indexingStatusText() ?: "Ready")
    var isIndexing: Boolean by mutableStateOf(session?.let { !it.isIndexingComplete() } ?: false)
    var liveTotalLines: Long by mutableStateOf(session?.let { (it.totalLines() as Number).toLong() } ?: 0L)

    // --- Temporal Navigation & Focus Mode ---
    var isTimeScrubberOpen: Boolean by mutableStateOf(false)
    var isTemporalFocusActive: Boolean by mutableStateOf(false)
    var temporalFocusStartMicros: Long by mutableStateOf(0L)
    var temporalFocusEndMicros: Long by mutableStateOf(0L)
    var temporalFocusStartLine: Long by mutableStateOf(0L)
    var temporalFocusEndLine: Long by mutableStateOf(0L)

    // --- Tri-Mode Viewport: Expandable Multi-Line Events ---
    val expandedEventHeaders = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()
    var isExpandAllActive: Boolean by mutableStateOf(false)

    fun isEventExpanded(eventHeaderLine: Long): Boolean {
        val override = expandedEventHeaders[eventHeaderLine]
        return override ?: isExpandAllActive
    }

    fun toggleEventExpansion(eventHeaderLine: Long) {
        val current = isEventExpanded(eventHeaderLine)
        expandedEventHeaders[eventHeaderLine] = !current
    }

    fun expandAllEvents() {
        isExpandAllActive = true
        expandedEventHeaders.clear()
    }

    fun collapseAllEvents() {
        isExpandAllActive = false
        expandedEventHeaders.clear()
    }

    val selectionState: umbrix.ui.selection.TextSelectionState = umbrix.ui.selection.TextSelectionState()
}
