package umbrix.ui.screen

import com.google.re2j.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.parser.QueryParseError
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.toast.ToastManager
import umbrix.ui.viewport.ViewportState

/**
 * Coordinates asynchronous search indexing, needle interval resolution, and bi-directional
 * search navigation across full, filtered, and temporal slice views.
 */
object MainScreenSearchController {

    private const val CHUNK_SIZE_BYTES: Long = 16L * 1024L * 1024L

    /**
     * Resolves the viewport-aware anchor line for search indexing and navigation.
     * If the user clicked/selected a line and that line is currently visible in the viewport,
     * the anchor is that selected line. If the user scrolled away so the selected line is
     * off-screen (or no line is selected), the anchor is the top visible line in the viewport.
     */
    fun resolveAnchorLine(tab: WorkspaceTab, totalLines: Long): Long {
        val isTemporal = tab.isTemporalFocusActive
        val minLine = if (isTemporal) tab.temporalFocusStartLine else 0L
        val maxLine = if (isTemporal) tab.temporalFocusEndLine else (totalLines - 1L)

        val visibleTop = if (isTemporal) tab.temporalFocusStartLine + tab.topVisibleLine else tab.topVisibleLine
        val visibleBottom = visibleTop + tab.visibleRowCount.toLong()

        val selected = tab.selectedLineIndex
        val isSelectionVisible = selected != null && selected in visibleTop..visibleBottom

        val anchor = if (isSelectionVisible) selected!! else visibleTop
        return anchor.coerceIn(minLine, maxLine)
    }

    private fun navigateToMatchLine(
        tab: WorkspaceTab,
        session: LogSession,
        targetLine: Long,
        queryText: String
    ) {
        val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
        if (tab.isFilteredView) {
            val searchRes = tab.searchResult
            if (searchRes != null && !searchRes.isEmpty) {
                val filteredView = session.createFilteredView(searchRes.matchingLines())
                val vIdx = (filteredView.virtualIndexForPhysicalLine(targetLine) as Number).toLong()
                val lineText = try { filteredView.getVirtualLineText(vIdx, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                val intervals = findNeedleIntervals(
                    lineText,
                    needles,
                    isMatchCase = tab.isSearchMatchCase,
                    isWholeWord = tab.isSearchWholeWord,
                    isRegex = tab.isSearchRegex
                )
                tab.topVisibleLine = vIdx
                tab.selectedLineIndex = targetLine
                tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                tab.searchNavigationGeneration++
            }
        } else {
            val lineText = try { session.getLineText(targetLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
            val intervals = findNeedleIntervals(
                lineText,
                needles,
                isMatchCase = tab.isSearchMatchCase,
                isWholeWord = tab.isSearchWholeWord,
                isRegex = tab.isSearchRegex
            )
            val halfVisible = (tab.visibleRowCount / 2).toLong()
            if (tab.isTemporalFocusActive) {
                val relativeLine = targetLine - tab.temporalFocusStartLine
                val totalTemporal = maxOf(0L, tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L)
                val maxTop = maxOf(0L, totalTemporal - tab.visibleRowCount)
                tab.topVisibleLine = (relativeLine - halfVisible).coerceIn(0L, maxTop)
            } else {
                val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                tab.topVisibleLine = (targetLine - halfVisible).coerceIn(0L, maxTop)
            }
            tab.selectedLineIndex = targetLine
            tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
            tab.subLineScrollOffsetPx = 0f
            tab.searchNavigationGeneration++
        }
    }

    fun triggerSearch(
        tab: WorkspaceTab,
        session: LogSession,
        coroutineScope: CoroutineScope,
        queryText: String,
        immediate: Boolean = false,
        onSearchErrorMessageChange: (String?) -> Unit
    ) {
        tab.searchQuery = queryText
        session.abortSearch()
        tab.searchJob?.cancel()

        if (queryText.isBlank()) {
            tab.searchResult = null
            tab.rawSearchResult = null
            tab.activeSearchQuery = ""
            tab.isSearching = false
            tab.isFilteredView = false
            tab.selectedMatchColumn = -1
            onSearchErrorMessageChange(null)
            session.trimWorkingSet()
            return
        }

        // Clear stale search result immediately so UI and highlights don't show old cached data
        tab.searchResult = null
        tab.rawSearchResult = null
        tab.selectedMatchColumn = -1
        tab.isSearching = true
        onSearchErrorMessageChange(null)

        val effectiveQuery = when {
            tab.isSearchRegex -> {
                val flags = if (tab.isSearchMatchCase) "" else "(?i)"
                val pattern = if (tab.isSearchWholeWord) "\\b(?:$queryText)\\b" else queryText
                "regex:$flags$pattern"
            }
            tab.isSearchWholeWord -> {
                val flags = if (tab.isSearchMatchCase) "" else "(?i)"
                "regex:$flags\\b" + com.google.re2j.Pattern.quote(queryText) + "\\b"
            }
            tab.isSearchMatchCase -> {
                val raw = queryText.removeSurrounding("\"")
                "case:sensitive \"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            else -> queryText
        }

        tab.searchJob = coroutineScope.launch {
            try {
                if (!immediate) {
                    delay(250) // Debounce rapid keystrokes to prevent concurrent query pile-up
                }

                var hasJumpedToFirstMatch = false
                val totalLines = (session.totalLines() as Number).toLong()
                val anchorLine = resolveAnchorLine(tab, totalLines)

                val parseAndSearchResult: scala.util.Either<QueryParseError, SearchResult> = kotlinx.coroutines.runInterruptible(Dispatchers.Default) {
                    session.searchProgressive(effectiveQuery, anchorLine) { rawPartialResult: SearchResult ->
                        if (tab.searchQuery == queryText) {
                            val partialResult = if (tab.isTemporalFocusActive) {
                                rawPartialResult.constrainToRange(tab.temporalFocusStartLine, tab.temporalFocusEndLine)
                            } else {
                                rawPartialResult
                            }

                            coroutineScope.launch(Dispatchers.Main) {
                                if (tab.searchQuery == queryText) {
                                    tab.rawSearchResult = rawPartialResult
                                    tab.searchResult = partialResult
                                    tab.activeSearchQuery = queryText

                                    if (!hasJumpedToFirstMatch && !partialResult.isEmpty()) {
                                        val minLine = if (tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                        val maxLine = if (tab.isTemporalFocusActive) tab.temporalFocusEndLine else (totalLines - 1L)
                                        val candidateLine = partialResult.findNextLine(anchorLine)

                                        if (candidateLine >= 0L && candidateLine <= maxLine) {
                                            val anchorByte = try { (session.indexer().byteOffsetForLine(anchorLine) as Number).toLong() } catch (_: Exception) { 0L }
                                            val candidateByte = try { (session.indexer().byteOffsetForLine(candidateLine) as Number).toLong() } catch (_: Exception) { -1L }
                                            val isLocalChunkMatch = candidateByte >= 0L && (candidateByte / CHUNK_SIZE_BYTES) == (anchorByte / CHUNK_SIZE_BYTES)

                                            if (isLocalChunkMatch) {
                                                hasJumpedToFirstMatch = true
                                                tab.searchWrapPromptPending = false
                                                tab.searchWrapIndicator = null
                                                navigateToMatchLine(tab, session, candidateLine, queryText)
                                            }
                                        }
                                        // Out-of-order chunks are still running; never trigger premature wrap warnings
                                    }
                                }
                            }
                        }
                    }
                }

                // Guard: Discard stale result if query was superseded while searching
                if (tab.searchQuery != queryText) return@launch

                withContext(Dispatchers.Main) {
                    if (tab.searchQuery == queryText) {
                        tab.isSearching = false
                        parseAndSearchResult.fold(
                            { parseError: QueryParseError ->
                                onSearchErrorMessageChange(parseError.message())
                                tab.searchResult = null
                                tab.rawSearchResult = null
                                tab.activeSearchQuery = ""
                                tab.selectedMatchColumn = -1
                                tab.searchWrapPromptPending = false
                                tab.searchWrapIndicator = null
                                tab.pendingSearchNavigation = null
                            },
                            { rawSearchResult: SearchResult ->
                                val searchResult = if (tab.isTemporalFocusActive) {
                                    rawSearchResult.constrainToRange(tab.temporalFocusStartLine, tab.temporalFocusEndLine)
                                } else {
                                    rawSearchResult
                                }
                                tab.rawSearchResult = rawSearchResult
                                tab.searchResult = searchResult
                                tab.activeSearchQuery = queryText

                                val pendingNav = tab.pendingSearchNavigation
                                tab.pendingSearchNavigation = null

                                if (searchResult.isEmpty()) {
                                    onSearchErrorMessageChange(null)
                                    tab.searchWrapPromptPending = false
                                    tab.searchWrapIndicator = null
                                } else if (pendingNav != null) {
                                    // Fulfill queued Enter/F3 navigation requested while search was in flight
                                    onSearchErrorMessageChange(null)
                                    tab.searchWrapPromptPending = false
                                    tab.searchWrapIndicator = null
                                    navigateSearch(tab, session, forward = pendingNav)
                                } else if (!hasJumpedToFirstMatch) {
                                    val minLine = if (tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                    val maxLine = if (tab.isTemporalFocusActive) tab.temporalFocusEndLine else (totalLines - 1L)
                                    val candidateLine = searchResult.findNextLine(anchorLine)

                                    if (candidateLine >= 0L && candidateLine <= maxLine) {
                                        onSearchErrorMessageChange(null)
                                        tab.searchWrapPromptPending = false
                                        tab.searchWrapIndicator = null
                                        hasJumpedToFirstMatch = true
                                        navigateToMatchLine(tab, session, candidateLine, queryText)
                                    } else {
                                        tab.searchWrapPromptPending = true
                                        tab.searchWrapIndicator = "No matches below cursor. Press Enter to wrap."
                                        onSearchErrorMessageChange("No matches below cursor. Press Enter to wrap.")
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Normal coroutine cancellation when user continues typing
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext.fromSession(
                    tab.filePath,
                    session.fileSizeBytes(),
                    (session.totalLines() as Number).toLong(),
                    tab.topVisibleLine
                )
                WorkstationErrorHandler.reportException(e, context)
                onSearchErrorMessageChange("Search failed: ${e.message}")
            } finally {
                if (tab.searchQuery == queryText) {
                    tab.isSearching = false
                }
                session.trimWorkingSet()
            }
        }
    }

    fun navigateSearch(tab: WorkspaceTab, session: LogSession, forward: Boolean) {
        if (tab.isSearching) {
            tab.pendingSearchNavigation = forward
            umbrix.ui.toast.ToastManager.info("Searching... navigating on completion")
            return
        }

        val result = tab.searchResult ?: return
        if (result.isEmpty()) return

        val needles = extractSearchNeedles(tab.searchQuery, isRegex = tab.isSearchRegex)
        if (needles.isEmpty()) return

        if (tab.isFilteredView) {
            val totalFiltered = result.totalMatches()
            if (totalFiltered <= 0L) return
            val filteredView = session.createFilteredView(result.matchingLines())

            fun getFilteredLineIntervals(vLine: Long): List<Pair<Int, Int>> {
                if (vLine < 0L || vLine >= totalFiltered) return emptyList()
                val lineText = try {
                    filteredView.getVirtualLineText(vLine, ViewportState.MAX_SHAPED_CHARS)
                } catch (_: Exception) { "" }
                return findNeedleIntervals(
                    lineText,
                    needles,
                    isMatchCase = tab.isSearchMatchCase,
                    isWholeWord = tab.isSearchWholeWord,
                    isRegex = tab.isSearchRegex
                )
            }

            val visibleTopVirtual = tab.topVisibleLine
            val visibleBottomVirtual = visibleTopVirtual + tab.visibleRowCount.toLong()

            val selectedPhysical = tab.selectedLineIndex
            val selectedVirtual = if (selectedPhysical != null) {
                (filteredView.virtualIndexForPhysicalLine(selectedPhysical) as Number).toLong()
            } else -1L

            val currentVirtual = if (selectedVirtual in visibleTopVirtual..visibleBottomVirtual) {
                selectedVirtual.coerceIn(0L, totalFiltered - 1L)
            } else {
                tab.topVisibleLine.coerceIn(0L, totalFiltered - 1L)
            }
            val currentCol = tab.selectedMatchColumn
            val currentIntervals = getFilteredLineIntervals(currentVirtual)

            if (forward) {
                val nextInterval = if (currentCol >= 0) {
                    currentIntervals.firstOrNull { it.first > currentCol }
                } else {
                    currentIntervals.firstOrNull()
                }

                if (nextInterval != null) {
                    tab.selectedMatchColumn = nextInterval.first
                    tab.selectedLineIndex = (filteredView.physicalLineForVirtualIndex(currentVirtual) as Number).toLong()
                    tab.searchNavigationGeneration++
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = null
                } else if (currentVirtual + 1L < totalFiltered) {
                    val targetVirtual = currentVirtual + 1L
                    val targetIntervals = getFilteredLineIntervals(targetVirtual)
                    val targetPhysical = (filteredView.physicalLineForVirtualIndex(targetVirtual) as Number).toLong()
                    tab.topVisibleLine = targetVirtual
                    tab.selectedLineIndex = targetPhysical
                    tab.selectedMatchColumn = targetIntervals.firstOrNull()?.first ?: 0
                    tab.subLineScrollOffsetPx = 0f
                    tab.searchNavigationGeneration++
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = null
                } else {
                    // At end of filtered results
                    if (!tab.searchWrapPromptPending) {
                        tab.searchWrapPromptPending = true
                        tab.searchWrapIndicator = "Reached end. Press Enter to wrap to top."
                        umbrix.ui.toast.ToastManager.info("Reached end of filtered view. Press Enter to wrap to top.")
                    } else {
                        // User confirmed wrap
                        val targetVirtual = 0L
                        val targetIntervals = getFilteredLineIntervals(targetVirtual)
                        val targetPhysical = (filteredView.physicalLineForVirtualIndex(targetVirtual) as Number).toLong()
                        tab.topVisibleLine = targetVirtual
                        tab.selectedLineIndex = targetPhysical
                        tab.selectedMatchColumn = targetIntervals.firstOrNull()?.first ?: 0
                        tab.subLineScrollOffsetPx = 0f
                        tab.searchNavigationGeneration++
                        tab.searchWrapPromptPending = false
                        tab.searchWrapIndicator = "Wrapped to top of filtered view"
                        umbrix.ui.toast.ToastManager.info("Wrapped to top of filtered view (Match 1 of $totalFiltered)")
                    }
                }
            } else {
                val prevInterval = if (currentCol >= 0) {
                    currentIntervals.lastOrNull { it.first < currentCol }
                } else {
                    null
                }

                if (prevInterval != null) {
                    tab.selectedMatchColumn = prevInterval.first
                    tab.selectedLineIndex = (filteredView.physicalLineForVirtualIndex(currentVirtual) as Number).toLong()
                    tab.searchNavigationGeneration++
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = null
                } else if (currentVirtual - 1L >= 0L) {
                    val targetVirtual = currentVirtual - 1L
                    val targetIntervals = getFilteredLineIntervals(targetVirtual)
                    val targetPhysical = (filteredView.physicalLineForVirtualIndex(targetVirtual) as Number).toLong()
                    tab.topVisibleLine = targetVirtual
                    tab.selectedLineIndex = targetPhysical
                    tab.selectedMatchColumn = targetIntervals.lastOrNull()?.first ?: 0
                    tab.subLineScrollOffsetPx = 0f
                    tab.searchNavigationGeneration++
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = null
                } else {
                    // At start of filtered results
                    if (!tab.searchWrapPromptPending) {
                        tab.searchWrapPromptPending = true
                        tab.searchWrapIndicator = "Reached start. Press Shift+Enter to wrap to bottom."
                        umbrix.ui.toast.ToastManager.info("Reached start of filtered view. Press Shift+Enter to wrap to bottom.")
                    } else {
                        // User confirmed wrap
                        val targetVirtual = totalFiltered - 1L
                        val targetIntervals = getFilteredLineIntervals(targetVirtual)
                        val targetPhysical = (filteredView.physicalLineForVirtualIndex(targetVirtual) as Number).toLong()
                        tab.topVisibleLine = targetVirtual
                        tab.selectedLineIndex = targetPhysical
                        tab.selectedMatchColumn = targetIntervals.lastOrNull()?.first ?: 0
                        tab.subLineScrollOffsetPx = 0f
                        tab.searchNavigationGeneration++
                        tab.searchWrapPromptPending = false
                        tab.searchWrapIndicator = "Wrapped to bottom of filtered view"
                        umbrix.ui.toast.ToastManager.info("Wrapped to bottom of filtered view (Match $totalFiltered of $totalFiltered)")
                    }
                }
            }
        } else {
            val isTemporal = tab.isTemporalFocusActive
            val minLine = if (isTemporal) tab.temporalFocusStartLine else 0L
            val maxLine = if (isTemporal) tab.temporalFocusEndLine else ((session.totalLines() as Number).toLong() - 1L)

            fun getLineIntervals(line: Long): List<Pair<Int, Int>> {
                if (line < 0L || line >= (session.totalLines() as Number).toLong()) return emptyList()
                val lineText = try {
                    session.getLineText(line, ViewportState.MAX_SHAPED_CHARS)
                } catch (_: Exception) { "" }
                return findNeedleIntervals(
                    lineText,
                    needles,
                    isMatchCase = tab.isSearchMatchCase,
                    isWholeWord = tab.isSearchWholeWord,
                    isRegex = tab.isSearchRegex
                )
            }

            val totalLines = (session.totalLines() as Number).toLong()
            val clampedAnchor = resolveAnchorLine(tab, totalLines)
            val currentCol = tab.selectedMatchColumn
            val isAnchorMatch = result.matchingLines().contains(clampedAnchor)

            fun centerOnLine(targetLine: Long, col: Int) {
                val halfVisible = (tab.visibleRowCount / 2).toLong()
                if (isTemporal) {
                    val relativeLine = targetLine - tab.temporalFocusStartLine
                    val totalTemporal = maxOf(0L, tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L)
                    val maxTop = maxOf(0L, totalTemporal - tab.visibleRowCount)
                    tab.topVisibleLine = (relativeLine - halfVisible).coerceIn(0L, maxTop)
                } else {
                    val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                    tab.topVisibleLine = (targetLine - halfVisible).coerceIn(0L, maxTop)
                }
                tab.selectedLineIndex = targetLine
                tab.selectedMatchColumn = col
                tab.subLineScrollOffsetPx = 0f
                tab.searchNavigationGeneration++
            }

            if (forward) {
                val currentIntervals = if (isAnchorMatch) getLineIntervals(clampedAnchor) else emptyList()
                val nextInterval = if (currentCol >= 0) {
                    currentIntervals.firstOrNull { it.first > currentCol }
                } else {
                    currentIntervals.firstOrNull()
                }

                if (nextInterval != null) {
                    centerOnLine(clampedAnchor, nextInterval.first)
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = null
                } else {
                    val nextLine = result.findNextLine(clampedAnchor + 1L)
                    if (nextLine >= 0L && nextLine <= maxLine) {
                        val targetIntervals = getLineIntervals(nextLine)
                        centerOnLine(nextLine, targetIntervals.firstOrNull()?.first ?: 0)
                        tab.searchWrapPromptPending = false
                        tab.searchWrapIndicator = null
                    } else {
                        // End of document reached
                        if (!tab.searchWrapPromptPending) {
                            tab.searchWrapPromptPending = true
                            tab.searchWrapIndicator = "Reached end of file. Press Enter to wrap to top."
                            umbrix.ui.toast.ToastManager.info("Reached end of file. Press Enter to wrap to top.")
                        } else {
                            // User confirmed wrap
                            val wrapLine = result.findNextLine(minLine)
                            if (wrapLine >= 0L && wrapLine <= maxLine) {
                                val targetIntervals = getLineIntervals(wrapLine)
                                centerOnLine(wrapLine, targetIntervals.firstOrNull()?.first ?: 0)
                                tab.searchWrapPromptPending = false
                                tab.searchWrapIndicator = "Wrapped to top of file"
                                umbrix.ui.toast.ToastManager.info("Wrapped to top of file (Match 1 of ${result.totalMatches()})")
                            }
                        }
                    }
                }
            } else {
                val currentIntervals = if (isAnchorMatch) getLineIntervals(clampedAnchor) else emptyList()
                val prevInterval = if (currentCol >= 0) {
                    currentIntervals.lastOrNull { it.first < currentCol }
                } else {
                    null
                }

                if (prevInterval != null) {
                    centerOnLine(clampedAnchor, prevInterval.first)
                    tab.searchWrapPromptPending = false
                    tab.searchWrapIndicator = null
                } else {
                    val prevLine = result.findPreviousLine(clampedAnchor - 1L)
                    if (prevLine >= minLine) {
                        val targetIntervals = getLineIntervals(prevLine)
                        centerOnLine(prevLine, targetIntervals.lastOrNull()?.first ?: 0)
                        tab.searchWrapPromptPending = false
                        tab.searchWrapIndicator = null
                    } else {
                        // Start of document reached
                        if (!tab.searchWrapPromptPending) {
                            tab.searchWrapPromptPending = true
                            tab.searchWrapIndicator = "Reached start of file. Press Shift+Enter to wrap to bottom."
                            umbrix.ui.toast.ToastManager.info("Reached start of file. Press Shift+Enter to wrap to bottom.")
                        } else {
                            // User confirmed wrap
                            val wrapLine = result.findPreviousLine(maxLine)
                            if (wrapLine >= minLine) {
                                val targetIntervals = getLineIntervals(wrapLine)
                                centerOnLine(wrapLine, targetIntervals.lastOrNull()?.first ?: 0)
                                tab.searchWrapPromptPending = false
                                tab.searchWrapIndicator = "Wrapped to bottom of file"
                                ToastManager.info("Wrapped to bottom of file (Match ${result.totalMatches()} of ${result.totalMatches()})")
                            }
                        }
                    }
                }
            }
        }
    }

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
            tab.searchWrapPromptPending = false
            tab.searchWrapIndicator = null
            onSearchErrorMessageChange(null)
            return
        }

        tab.searchResult = null
        tab.rawSearchResult = null
        tab.selectedMatchColumn = -1
        tab.isSearching = true
        onSearchErrorMessageChange(null)

        tab.searchJob = coroutineScope.launch {
            try {
                if (!immediate) {
                    delay(200)
                }

                // Verify regex syntax upfront if regex mode is active
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

                        // Anchor on the first occurrence
                        if (intervals.isNotEmpty()) {
                            val firstInterval = intervals.first()
                            val line = findLineIndexForOffset(lineStarts, firstInterval.first).toLong()
                            val col = firstInterval.first - lineStarts[line.toInt()]
                            tab.selectedLineIndex = line
                            tab.selectedMatchColumn = col
                            tab.searchNavigationGeneration++
                        }
                    }
                }
            } catch (cancellationException: CancellationException) {
                // Cooperative coroutine cancellation
            } catch (throwable: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(throwable)) throw throwable
                withContext(Dispatchers.Main) {
                    if (tab.searchQuery == queryText) {
                        tab.isSearching = false
                        tab.searchResult = null
                        tab.rawSearchResult = null
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
            val nextIndex = if (activeMatchIndex >= 0) {
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
            val prevIndex = if (activeMatchIndex >= 0) {
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
