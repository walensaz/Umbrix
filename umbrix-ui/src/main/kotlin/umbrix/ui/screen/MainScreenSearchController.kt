package umbrix.ui.screen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.parser.QueryParseError
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.viewport.ViewportState

/**
 * Coordinates asynchronous search indexing, needle interval resolution, and bi-directional
 * search navigation across full, filtered, and temporal slice views.
 */
object MainScreenSearchController {

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

                val parseAndSearchResult: scala.util.Either<QueryParseError, SearchResult> = kotlinx.coroutines.runInterruptible(Dispatchers.Default) {
                    session.searchProgressive(effectiveQuery) { rawPartialResult: SearchResult ->
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
                                        val maxLine = if (tab.isTemporalFocusActive) tab.temporalFocusEndLine else ((session.totalLines() as Number).toLong() - 1L)
                                        val anchorLine = tab.selectedLineIndex ?: if (tab.isTemporalFocusActive) (tab.temporalFocusStartLine + tab.topVisibleLine) else tab.topVisibleLine
                                        val clampedAnchor = anchorLine.coerceIn(minLine, maxLine)
                                        val candidateLine = partialResult.findNextLine(clampedAnchor)

                                        if (candidateLine >= 0L && candidateLine <= maxLine) {
                                            hasJumpedToFirstMatch = true
                                            tab.searchWrapPromptPending = false
                                            tab.searchWrapIndicator = null
                                            val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                                            if (tab.isFilteredView) {
                                                val filteredView = session.createFilteredView(partialResult.matchingLines())
                                                val vIdx = (filteredView.virtualIndexForPhysicalLine(candidateLine) as Number).toLong()
                                                val lineText = try { filteredView.getVirtualLineText(vIdx, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                                val intervals = findNeedleIntervals(
                                                    lineText,
                                                    needles,
                                                    isMatchCase = tab.isSearchMatchCase,
                                                    isWholeWord = tab.isSearchWholeWord,
                                                    isRegex = tab.isSearchRegex
                                                )
                                                tab.topVisibleLine = vIdx
                                                tab.selectedLineIndex = candidateLine
                                                tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                                tab.searchNavigationGeneration++
                                            } else {
                                                val lineText = try { session.getLineText(candidateLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                                val intervals = findNeedleIntervals(
                                                    lineText,
                                                    needles,
                                                    isMatchCase = tab.isSearchMatchCase,
                                                    isWholeWord = tab.isSearchWholeWord,
                                                    isRegex = tab.isSearchRegex
                                                )
                                                val halfVisible = (tab.visibleRowCount / 2).toLong()
                                                if (tab.isTemporalFocusActive) {
                                                    val relativeLine = candidateLine - tab.temporalFocusStartLine
                                                    val totalTemporal = maxOf(0L, tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L)
                                                    val maxTop = maxOf(0L, totalTemporal - tab.visibleRowCount)
                                                    tab.topVisibleLine = (relativeLine - halfVisible).coerceIn(0L, maxTop)
                                                } else {
                                                    val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                                                    tab.topVisibleLine = (candidateLine - halfVisible).coerceIn(0L, maxTop)
                                                }
                                                tab.selectedLineIndex = candidateLine
                                                tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                                tab.searchNavigationGeneration++
                                            }
                                        } else {
                                            // Matches exist in the document, but none below current position
                                            tab.searchWrapPromptPending = true
                                            tab.searchWrapIndicator = "No matches below cursor. Press Enter to wrap."
                                            onSearchErrorMessageChange("No matches below cursor. Press Enter to wrap.")
                                        }
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

                                if (searchResult.isEmpty()) {
                                    onSearchErrorMessageChange(null)
                                    tab.searchWrapPromptPending = false
                                    tab.searchWrapIndicator = null
                                } else if (!hasJumpedToFirstMatch) {
                                    val minLine = if (tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                    val maxLine = if (tab.isTemporalFocusActive) tab.temporalFocusEndLine else ((session.totalLines() as Number).toLong() - 1L)
                                    val anchorLine = tab.selectedLineIndex ?: if (tab.isTemporalFocusActive) (tab.temporalFocusStartLine + tab.topVisibleLine) else tab.topVisibleLine
                                    val clampedAnchor = anchorLine.coerceIn(minLine, maxLine)
                                    val candidateLine = searchResult.findNextLine(clampedAnchor)

                                    if (candidateLine >= 0L && candidateLine <= maxLine) {
                                        onSearchErrorMessageChange(null)
                                        tab.searchWrapPromptPending = false
                                        tab.searchWrapIndicator = null
                                        val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                                        if (tab.isFilteredView) {
                                            val filteredView = session.createFilteredView(searchResult.matchingLines())
                                            val vIdx = (filteredView.virtualIndexForPhysicalLine(candidateLine) as Number).toLong()
                                            val lineText = try { filteredView.getVirtualLineText(vIdx, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                            val intervals = findNeedleIntervals(
                                                lineText,
                                                needles,
                                                isMatchCase = tab.isSearchMatchCase,
                                                isWholeWord = tab.isSearchWholeWord,
                                                isRegex = tab.isSearchRegex
                                            )
                                            tab.topVisibleLine = vIdx
                                            tab.selectedLineIndex = candidateLine
                                            tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                            tab.searchNavigationGeneration++
                                        } else {
                                            val lineText = try { session.getLineText(candidateLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                            val intervals = findNeedleIntervals(
                                                lineText,
                                                needles,
                                                isMatchCase = tab.isSearchMatchCase,
                                                isWholeWord = tab.isSearchWholeWord,
                                                isRegex = tab.isSearchRegex
                                            )
                                            val halfVisible = (tab.visibleRowCount / 2).toLong()
                                            if (tab.isTemporalFocusActive) {
                                                val relativeLine = candidateLine - tab.temporalFocusStartLine
                                                val totalTemporal = maxOf(0L, tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L)
                                                val maxTop = maxOf(0L, totalTemporal - tab.visibleRowCount)
                                                tab.topVisibleLine = (relativeLine - halfVisible).coerceIn(0L, maxTop)
                                            } else {
                                                val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                                                tab.topVisibleLine = (candidateLine - halfVisible).coerceIn(0L, maxTop)
                                            }
                                            tab.selectedLineIndex = candidateLine
                                            tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                            tab.searchNavigationGeneration++
                                        }
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

            val currentPhysical = tab.selectedLineIndex ?: run {
                (filteredView.physicalLineForVirtualIndex(tab.topVisibleLine) as Number).toLong()
            }
            val currentVirtual = (filteredView.virtualIndexForPhysicalLine(currentPhysical) as Number).toLong().coerceIn(0L, totalFiltered - 1L)
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

            val anchorLine = tab.selectedLineIndex ?: if (isTemporal) (tab.temporalFocusStartLine + tab.topVisibleLine) else tab.topVisibleLine
            val clampedAnchor = anchorLine.coerceIn(minLine, maxLine)
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
                                umbrix.ui.toast.ToastManager.info("Wrapped to bottom of file (Match ${result.totalMatches()} of ${result.totalMatches()})")
                            }
                        }
                    }
                }
            }
        }
    }
}
