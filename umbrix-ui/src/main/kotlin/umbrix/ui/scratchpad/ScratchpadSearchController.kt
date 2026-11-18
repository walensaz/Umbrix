package umbrix.ui.scratchpad

import com.google.re2j.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.core.search.SearchResult
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.toast.ToastManager

/**
 * High-performance search and match navigation engine for in-memory scratchpad buffers.
 */
object ScratchpadSearchController {

    /**
     * Precomputes 0-based character start offsets for each line in [text].
     */
    fun computeLineStartOffsets(text: String): List<Int> {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in 0 until text.length) {
            if (text[i] == '\n') {
                starts.add(i + 1)
            }
        }
        return starts
    }

    /**
     * Resolves the 0-based line index for a character offset in $O(\log L)$ time.
     */
    fun findLineIndexForOffset(lineStarts: List<Int>, charOffset: Int): Int {
        if (lineStarts.isEmpty() || charOffset <= 0) return 0
        val binarySearchResult = java.util.Collections.binarySearch(lineStarts, charOffset)
        val resolvedLineIndex = if (binarySearchResult >= 0) binarySearchResult else -binarySearchResult - 2
        return resolvedLineIndex.coerceIn(0, lineStarts.size - 1)
    }

    /**
     * Evaluates a search query asynchronously against an in-memory scratchpad buffer,
     * populating [tab.searchResult] and setting up bi-directional match navigation.
     */
    fun triggerScratchpadSearch(
        tab: WorkspaceTab,
        coroutineScope: CoroutineScope,
        queryText: String,
        immediate: Boolean = false,
        onSearchErrorMessageChange: (String?) -> Unit
    ) {
        tab.searchQuery = queryText
        tab.searchJob?.cancel()

        if (queryText.isBlank()) {
            tab.searchResult = null
            tab.rawSearchResult = null
            tab.activeSearchQuery = ""
            tab.isSearching = false
            tab.isFilteredView = false
            tab.selectedMatchColumn = -1
            tab.pendingSearchNavigation = null
            tab.searchWrapPromptPending = false
            tab.searchWrapIndicator = null
            onSearchErrorMessageChange(null)
            return
        }

        tab.searchResult = null
        tab.rawSearchResult = null
        tab.selectedMatchColumn = -1
        tab.pendingSearchNavigation = null
        tab.searchWrapPromptPending = false
        tab.searchWrapIndicator = null
        tab.isSearching = true
        onSearchErrorMessageChange(null)

        tab.searchJob = coroutineScope.launch {
            try {
                if (!immediate) {
                    delay(200)
                }

                if (tab.isSearchRegex && queryText.isNotBlank()) {
                    try {
                        val flags = if (tab.isSearchMatchCase) 0 else Pattern.CASE_INSENSITIVE
                        val regexPattern = if (tab.isSearchWholeWord) "\\b(?:$queryText)\\b" else queryText
                        Pattern.compile(regexPattern, flags)
                    } catch (regexException: Exception) {
                        withContext(Dispatchers.Main) {
                            if (tab.searchQuery == queryText) {
                                tab.isSearching = false
                                tab.searchResult = null
                                tab.rawSearchResult = null
                                tab.pendingSearchNavigation = null
                                onSearchErrorMessageChange(regexException.message ?: "Invalid regular expression")
                            }
                        }
                        return@launch
                    }
                }

                val session = tab.scratchpadSession
                val content = session?.content ?: ""
                val startTimeNanos = System.nanoTime()

                val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                val intervals = findNeedleIntervals(
                    rawText = content,
                    needles = needles,
                    isMatchCase = tab.isSearchMatchCase,
                    isWholeWord = tab.isSearchWholeWord,
                    isRegex = tab.isSearchRegex
                )

                val lineStarts = computeLineStartOffsets(content)
                val matchingLines = Roaring64NavigableMap()
                for (interval in intervals) {
                    val lineIndex = findLineIndexForOffset(lineStarts, interval.first)
                    matchingLines.add(lineIndex.toLong())
                }

                val durationNanos = System.nanoTime() - startTimeNanos
                val result = SearchResult(
                    matchingLines,
                    intervals.size.toLong(),
                    durationNanos,
                    true
                )

                if (tab.searchQuery != queryText) return@launch

                withContext(Dispatchers.Main) {
                    if (tab.searchQuery == queryText) {
                        tab.isSearching = false
                        tab.rawSearchResult = result
                        tab.searchResult = result
                        tab.activeSearchQuery = queryText
                        tab.searchWrapPromptPending = false
                        tab.searchWrapIndicator = null
                        onSearchErrorMessageChange(null)

                        val pendingNav = tab.pendingSearchNavigation
                        tab.pendingSearchNavigation = null
                        if (pendingNav != null && intervals.isNotEmpty()) {
                            navigateScratchpadSearch(tab, forward = pendingNav)
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (throwable: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(throwable)) throw throwable
                withContext(Dispatchers.Main) {
                    if (tab.searchQuery == queryText) {
                        tab.isSearching = false
                        tab.searchResult = null
                        tab.rawSearchResult = null
                        tab.pendingSearchNavigation = null
                        onSearchErrorMessageChange("Search failed: ${throwable.message}")
                    }
                }
            } finally {
                if (tab.searchQuery == queryText) {
                    tab.isSearching = false
                }
            }
        }
    }

    /**
     * Navigates forward or backward between search match occurrences in a scratchpad buffer.
     */
    fun navigateScratchpadSearch(tab: WorkspaceTab, forward: Boolean) {
        if (tab.isSearching) {
            tab.pendingSearchNavigation = forward
            return
        }

        val result = tab.searchResult ?: return
        if (result.isEmpty()) return

        val scratchpad = tab.scratchpadSession ?: return
        val content = scratchpad.content
        val needles = extractSearchNeedles(tab.searchQuery, isRegex = tab.isSearchRegex)
        if (needles.isEmpty()) return

        val intervals = findNeedleIntervals(
            rawText = content,
            needles = needles,
            isMatchCase = tab.isSearchMatchCase,
            isWholeWord = tab.isSearchWholeWord,
            isRegex = tab.isSearchRegex
        )
        if (intervals.isEmpty()) return

        val lineStarts = computeLineStartOffsets(content)
        val currentLine = tab.selectedLineIndex ?: 0L
        val currentCol = tab.selectedMatchColumn
        val currentOffset = if (currentLine.toInt() in lineStarts.indices && currentCol >= 0) {
            lineStarts[currentLine.toInt()] + currentCol
        } else {
            -1
        }

        val activeMatchIndex = if (currentOffset >= 0) {
            intervals.indexOfFirst { it.first == currentOffset }
        } else -1

        if (forward) {
            val nextIndex = if (currentCol < 0) {
                val anchorOffset = if (currentLine.toInt() in lineStarts.indices) lineStarts[currentLine.toInt()] else 0
                val candidate = intervals.indexOfFirst { it.first >= anchorOffset }
                if (candidate >= 0) candidate else intervals.size
            } else if (activeMatchIndex >= 0) {
                activeMatchIndex + 1
            } else {
                val candidate = intervals.indexOfFirst { it.first > currentOffset }
                if (candidate >= 0) candidate else intervals.size
            }

            if (nextIndex in intervals.indices) {
                val targetInterval = intervals[nextIndex]
                val targetLine = findLineIndexForOffset(lineStarts, targetInterval.first).toLong()
                val targetCol = targetInterval.first - lineStarts[targetLine.toInt()]
                tab.selectedLineIndex = targetLine
                tab.selectedMatchColumn = targetCol
                tab.searchNavigationGeneration++
                tab.searchWrapPromptPending = false
                tab.searchWrapIndicator = null
            } else {
                if (!tab.searchWrapPromptPending) {
                    tab.searchWrapPromptPending = true
                    tab.searchWrapIndicator = "Reached end. Press Enter to wrap to top."
                    ToastManager.info("Reached end of scratchpad. Press Enter to wrap to top.")
                } else {
                    val targetInterval = intervals.first()
                    val targetLine = findLineIndexForOffset(lineStarts, targetInterval.first).toLong()
                    val targetCol = targetInterval.first - lineStarts[targetLine.toInt()]
                    tab.selectedLineIndex = targetLine
                    tab.selectedMatchColumn = targetCol
                    tab.searchNavigationGeneration++
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = "Wrapped to top of scratchpad"
                    ToastManager.info("Wrapped to top of scratchpad (Match 1 of ${intervals.size})")
                }
            }
        } else {
            val prevIndex = if (currentCol < 0) {
                val anchorOffset = if (currentLine.toInt() in lineStarts.indices) lineStarts[currentLine.toInt()] else 0
                intervals.indexOfLast { it.first < anchorOffset }
            } else if (activeMatchIndex >= 0) {
                activeMatchIndex - 1
            } else {
                intervals.indexOfLast { it.first < currentOffset }
            }

            if (prevIndex in intervals.indices) {
                val targetInterval = intervals[prevIndex]
                val targetLine = findLineIndexForOffset(lineStarts, targetInterval.first).toLong()
                val targetCol = targetInterval.first - lineStarts[targetLine.toInt()]
                tab.selectedLineIndex = targetLine
                tab.selectedMatchColumn = targetCol
                tab.searchNavigationGeneration++
                tab.searchWrapPromptPending = false
                tab.searchWrapIndicator = null
            } else {
                if (!tab.searchWrapPromptPending) {
                    tab.searchWrapPromptPending = true
                    tab.searchWrapIndicator = "Reached start. Press Shift+Enter to wrap to bottom."
                    ToastManager.info("Reached start of scratchpad. Press Shift+Enter to wrap to bottom.")
                } else {
                    val targetInterval = intervals.last()
                    val targetLine = findLineIndexForOffset(lineStarts, targetInterval.first).toLong()
                    val targetCol = targetInterval.first - lineStarts[targetLine.toInt()]
                    tab.selectedLineIndex = targetLine
                    tab.selectedMatchColumn = targetCol
                    tab.searchNavigationGeneration++
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = "Wrapped to bottom of scratchpad"
                    ToastManager.info("Wrapped to bottom of scratchpad (Match ${intervals.size} of ${intervals.size})")
                }
            }
        }
    }
}
