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
                                        val firstLine = partialResult.findNextLine(minLine)
                                        if (firstLine >= 0L && (!tab.isTemporalFocusActive || firstLine <= tab.temporalFocusEndLine)) {
                                            hasJumpedToFirstMatch = true
                                            val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                                            if (tab.isFilteredView) {
                                                val filteredView = session.createFilteredView(partialResult.matchingLines())
                                                val lineText = try { filteredView.getVirtualLineText(0L, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                                val intervals = findNeedleIntervals(
                                                    lineText,
                                                    needles,
                                                    isMatchCase = tab.isSearchMatchCase,
                                                    isWholeWord = tab.isSearchWholeWord,
                                                    isRegex = tab.isSearchRegex
                                                )
                                                tab.topVisibleLine = 0L
                                                tab.selectedLineIndex = firstLine
                                                tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                                tab.searchNavigationGeneration++
                                            } else {
                                                val lineText = try { session.getLineText(firstLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                                val intervals = findNeedleIntervals(
                                                    lineText,
                                                    needles,
                                                    isMatchCase = tab.isSearchMatchCase,
                                                    isWholeWord = tab.isSearchWholeWord,
                                                    isRegex = tab.isSearchRegex
                                                )
                                                val halfVisible = (tab.visibleRowCount / 2).toLong()
                                                if (tab.isTemporalFocusActive) {
                                                    val relativeLine = firstLine - tab.temporalFocusStartLine
                                                    val totalTemporal = maxOf(0L, tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L)
                                                    val maxTop = maxOf(0L, totalTemporal - tab.visibleRowCount)
                                                    tab.topVisibleLine = (relativeLine - halfVisible).coerceIn(0L, maxTop)
                                                } else {
                                                    val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                                                    tab.topVisibleLine = (firstLine - halfVisible).coerceIn(0L, maxTop)
                                                }
                                                tab.selectedLineIndex = firstLine
                                                tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                                tab.searchNavigationGeneration++
                                            }
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
                            },
                            { rawSearchResult: SearchResult ->
                                onSearchErrorMessageChange(null)
                                tab.rawSearchResult = rawSearchResult
                                val searchResult = if (tab.isTemporalFocusActive) {
                                    rawSearchResult.constrainToRange(tab.temporalFocusStartLine, tab.temporalFocusEndLine)
                                } else {
                                    rawSearchResult
                                }
                                tab.searchResult = searchResult
                                tab.activeSearchQuery = queryText

                                if (!hasJumpedToFirstMatch && !searchResult.isEmpty()) {
                                    val minLine = if (tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                    val firstLine = searchResult.findNextLine(minLine)
                                    if (firstLine >= 0L && (!tab.isTemporalFocusActive || firstLine <= tab.temporalFocusEndLine)) {
                                        val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                                        if (tab.isFilteredView) {
                                            val filteredView = session.createFilteredView(searchResult.matchingLines())
                                            val lineText = try { filteredView.getVirtualLineText(0L, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                            val intervals = findNeedleIntervals(
                                                lineText,
                                                needles,
                                                isMatchCase = tab.isSearchMatchCase,
                                                isWholeWord = tab.isSearchWholeWord,
                                                isRegex = tab.isSearchRegex
                                            )
                                            tab.topVisibleLine = 0L
                                            tab.selectedLineIndex = firstLine
                                            tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                            tab.searchNavigationGeneration++
                                        } else {
                                            val lineText = try { session.getLineText(firstLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                            val intervals = findNeedleIntervals(
                                                lineText,
                                                needles,
                                                isMatchCase = tab.isSearchMatchCase,
                                                isWholeWord = tab.isSearchWholeWord,
                                                isRegex = tab.isSearchRegex
                                            )
                                            val halfVisible = (tab.visibleRowCount / 2).toLong()
                                            if (tab.isTemporalFocusActive) {
                                                val relativeLine = firstLine - tab.temporalFocusStartLine
                                                val totalTemporal = maxOf(0L, tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L)
                                                val maxTop = maxOf(0L, totalTemporal - tab.visibleRowCount)
                                                tab.topVisibleLine = (relativeLine - halfVisible).coerceIn(0L, maxTop)
                                            } else {
                                                val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                                                tab.topVisibleLine = (firstLine - halfVisible).coerceIn(0L, maxTop)
                                            }
                                            tab.selectedLineIndex = firstLine
                                            tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                            tab.searchNavigationGeneration++
                                        }
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
                } else {
                    var targetVirtual = if (currentVirtual + 1L < totalFiltered) currentVirtual + 1L else 0L
                    var targetIntervals = getFilteredLineIntervals(targetVirtual)
                    var attempts = 0
                    while (targetIntervals.isEmpty() && attempts < totalFiltered) {
                        targetVirtual = if (targetVirtual + 1L < totalFiltered) targetVirtual + 1L else 0L
                        targetIntervals = getFilteredLineIntervals(targetVirtual)
                        attempts++
                    }
                    val targetPhysical = (filteredView.physicalLineForVirtualIndex(targetVirtual) as Number).toLong()
                    tab.topVisibleLine = targetVirtual
                    tab.selectedLineIndex = targetPhysical
                    tab.selectedMatchColumn = targetIntervals.firstOrNull()?.first ?: 0
                    tab.subLineScrollOffsetPx = 0f
                    tab.searchNavigationGeneration++
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
                } else {
                    var targetVirtual = if (currentVirtual - 1L >= 0L) currentVirtual - 1L else totalFiltered - 1L
                    var targetIntervals = getFilteredLineIntervals(targetVirtual)
                    var attempts = 0
                    while (targetIntervals.isEmpty() && attempts < totalFiltered) {
                        targetVirtual = if (targetVirtual - 1L >= 0L) targetVirtual - 1L else totalFiltered - 1L
                        targetIntervals = getFilteredLineIntervals(targetVirtual)
                        attempts++
                    }
                    val targetPhysical = (filteredView.physicalLineForVirtualIndex(targetVirtual) as Number).toLong()
                    tab.topVisibleLine = targetVirtual
                    tab.selectedLineIndex = targetPhysical
                    tab.selectedMatchColumn = targetIntervals.lastOrNull()?.first ?: 0
                    tab.subLineScrollOffsetPx = 0f
                    tab.searchNavigationGeneration++
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
                } else {
                    val nextLine = result.findNextLine(clampedAnchor + 1L)
                    val targetLine = if (nextLine >= 0L && nextLine <= maxLine) {
                        nextLine
                    } else {
                        val wrapLine = result.findNextLine(minLine)
                        if (wrapLine >= 0L && wrapLine <= maxLine) wrapLine else clampedAnchor
                    }
                    val targetIntervals = getLineIntervals(targetLine)
                    centerOnLine(targetLine, targetIntervals.firstOrNull()?.first ?: 0)
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
                } else {
                    val prevLine = result.findPreviousLine(clampedAnchor - 1L)
                    val targetLine = if (prevLine >= minLine) {
                        prevLine
                    } else {
                        val wrapLine = result.findPreviousLine(maxLine)
                        if (wrapLine >= minLine) wrapLine else clampedAnchor
                    }
                    val targetIntervals = getLineIntervals(targetLine)
                    centerOnLine(targetLine, targetIntervals.lastOrNull()?.first ?: 0)
                }
            }
        }
    }
}
