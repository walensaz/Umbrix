package umbrix.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession

/**
 * State container for an active document tab with reactive Compose state properties.
 */
class WorkspaceTab(
    val session: LogSession,
    val filePath: String,
    initialTopVisibleLine: Long = 0L,
    initialSearchQuery: String = "",
    initialSearchResult: SearchResult? = null,
    initialIsFilteredView: Boolean = false,
    initialSelectedLineIndex: Long? = null
) {
    var topVisibleLine: Long by mutableStateOf(initialTopVisibleLine)
    var subLineScrollOffsetPx: Float by mutableStateOf(0f)
    var searchQuery: String by mutableStateOf(initialSearchQuery)
    var searchResult: SearchResult? by mutableStateOf(initialSearchResult)
    var isFilteredView: Boolean by mutableStateOf(initialIsFilteredView)
    var selectedLineIndex: Long? by mutableStateOf(initialSelectedLineIndex)
    var searchJob: Job? = null

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

    var indexingProgress: Double by mutableStateOf(session.indexingProgressPercent())
    var indexingStatus: String by mutableStateOf(session.indexingStatusText())
    var isIndexing: Boolean by mutableStateOf(!session.isIndexingComplete())
    var liveTotalLines: Long by mutableStateOf(session.totalLines())

    // --- Temporal Navigation & Focus Mode ---
    var isTimeScrubberOpen: Boolean by mutableStateOf(false)
    var isTemporalFocusActive: Boolean by mutableStateOf(false)
    var temporalFocusStartMicros: Long by mutableStateOf(0L)
    var temporalFocusEndMicros: Long by mutableStateOf(0L)
    var temporalFocusStartLine: Long by mutableStateOf(0L)
    var temporalFocusEndLine: Long by mutableStateOf(0L)

    val selectionState: umbrix.ui.selection.TextSelectionState = umbrix.ui.selection.TextSelectionState()
}
