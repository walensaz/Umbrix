package umbrix.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.parser.QueryParseError
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.ui.components.ForensicErrorBanner
import umbrix.ui.dialog.AboutDialog
import umbrix.ui.dialog.ExportSubFileDialog
import umbrix.ui.dialog.GoToLineDialog
import umbrix.ui.dialog.GoToTimestampDialog
import umbrix.ui.dialog.KeyboardShortcutsDialog
import umbrix.ui.dialog.SettingsDialog
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.menu.MenuAction
import umbrix.ui.menu.MenuSettingsReducer
import umbrix.ui.search.SearchBar
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.selection.SelectionTooLargeException
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.temporal.TemporalRangeScrubber
import umbrix.ui.theme.FontSpec
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.toolbar.BottomStatusBar
import umbrix.ui.toolbar.TabBar
import umbrix.ui.util.formatByteSize
import umbrix.ui.util.formatNumberWithCommas
import umbrix.ui.util.showNativeOpenFileDialog
import umbrix.ui.util.showNativeSaveFileDialog
import umbrix.ui.viewport.LogViewportCanvas
import umbrix.ui.viewport.ViewportState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Main workstation layout coordinating multi-document tabs, hamburger actions,
 * hardware SIMD search bar, forensic error reporting, and center log viewport.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UmbrixMainScreen(
    initialFilePath: String?,
    droppedFilePath: String? = null,
    onClearDroppedFilePath: () -> Unit = {},
    onActiveDocTitleChange: (String?) -> Unit = {},
    isSettingsDialogVisible: Boolean = false,
    onSetSettingsDialogVisible: (Boolean) -> Unit = {},
    menuActions: Flow<MenuAction> = emptyFlow(),
    onExitRequest: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    val openTabs = remember { mutableStateListOf<WorkspaceTab>() }

    // Cleanly close all open log sessions when the workstation screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            for (tab in openTabs) {
                try {
                    tab.session.close()
                } catch (_: Throwable) {}
            }
        }
    }
    var activeTabIndex by remember { mutableStateOf(0) }

    var isIndexingActive by remember { mutableStateOf(false) }
    var statusMessageText by remember { mutableStateOf("Ready — No log file loaded.") }

    var isSearchBarVisible by remember { mutableStateOf(false) }
    var searchFocusTrigger by remember { mutableStateOf(0) }
    var searchErrorMessage by remember { mutableStateOf<String?>(null) }
    var isGoToLineDialogVisible by remember { mutableStateOf(false) }
    var isAboutDialogVisible by remember { mutableStateOf(false) }
    var isShortcutsDialogVisible by remember { mutableStateOf(false) }
    var isExportDialogVisible by remember { mutableStateOf(false) }

    val currentTab: WorkspaceTab? = openTabs.getOrNull(activeTabIndex)
    val activeSession: LogSession? = currentTab?.session

    fun loadLogFile(selectedPath: String, asNewTab: Boolean = true) {
        val targetPath = selectedPath.trim()
        if (targetPath.isBlank()) return

        val targetFile = File(targetPath)
        if (!targetFile.exists() || !targetFile.isFile) {
            statusMessageText = "Error: File does not exist: $targetPath"
            return
        }

        if (isIndexingActive) return

        isIndexingActive = true
        statusMessageText = "Opening ${targetFile.name}..."

        coroutineScope.launch {
            try {
                val newSession = withContext(Dispatchers.IO) {
                    LogSession.open(targetFile.toPath(), false)
                }

                val newTab = WorkspaceTab(
                    session = newSession,
                    filePath = targetFile.absolutePath,
                    initialTopVisibleLine = 0L
                )

                if (asNewTab || openTabs.isEmpty()) {
                    openTabs.add(newTab)
                    activeTabIndex = openTabs.size - 1
                } else {
                    val oldTab = openTabs[activeTabIndex]
                    openTabs[activeTabIndex] = newTab
                    withContext(Dispatchers.IO) {
                        oldTab.session.close()
                    }
                }

                SettingsManager.recordRecentFile(targetFile.absolutePath)
                val formattedSize = formatByteSize(newSession.fileSizeBytes())
                val initialLines = formatNumberWithCommas((newSession.totalLines() as Number).toLong())
                statusMessageText = "Opened ${targetFile.name} ($formattedSize, $initialLines initial lines preview) in ${newSession.indexingDurationMs()} ms"

                // Launch non-blocking background progress monitor for physical SIMD + semantic Tier 2 indexing
                if (!newSession.isIndexingComplete()) {
                    newTab.isIndexing = true
                    coroutineScope.launch(Dispatchers.Default) {
                        try {
                            while (!newSession.isIndexingComplete() && openTabs.contains(newTab)) {
                                kotlinx.coroutines.delay(50)
                                withContext(Dispatchers.Main) {
                                    newTab.indexingProgress = newSession.indexingProgressPercent()
                                    newTab.indexingStatus = newSession.indexingStatusText()
                                    newTab.liveTotalLines = (newSession.totalLines() as Number).toLong()
                                }
                            }
                            withContext(Dispatchers.Main) {
                                newTab.indexingProgress = 100.0
                                newTab.isIndexing = false
                                isIndexingActive = false
                                val finalLines = formatNumberWithCommas((newSession.totalLines() as Number).toLong())
                                val eventsFormatted = formatNumberWithCommas(newSession.totalEvents())
                                statusMessageText = "Ready — ${targetFile.name} ($formattedSize, $finalLines lines, $eventsFormatted logical events)"
                            }
                        } catch (_: Throwable) {
                            // Tab may have closed
                        }
                    }
                } else {
                    newTab.isIndexing = false
                    isIndexingActive = false
                }
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext(
                    scala.Option.apply(targetFile.absolutePath),
                    scala.Option.empty(),
                    scala.Option.empty(),
                    scala.Option.empty()
                )
                WorkstationErrorHandler.reportException(e, context)
                statusMessageText = "Failed to index file: ${e.message}"
                isIndexingActive = false
            }
        }
    }

    fun closeTab(tabIndex: Int) {
        try {
            if (tabIndex < 0 || tabIndex >= openTabs.size) return
            val tabToClose = openTabs.removeAt(tabIndex)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    tabToClose.session.close()
                } catch (e: Throwable) {
                    if (ForensicErrorReportGenerator.isFatal(e)) throw e
                    WorkstationErrorHandler.reportException(e)
                }
            }
            if (openTabs.isEmpty()) {
                activeTabIndex = 0
                statusMessageText = "All tabs closed. Ready."
            } else if (activeTabIndex >= openTabs.size) {
                activeTabIndex = openTabs.size - 1
            }
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            WorkstationErrorHandler.reportException(e)
        }
    }

    fun triggerSearch(queryText: String, immediate: Boolean = false) {
        val tab = currentTab ?: return
        tab.searchQuery = queryText
        tab.searchJob?.cancel()

        if (queryText.isBlank()) {
            tab.searchResult = null
            tab.isFilteredView = false
            tab.selectedMatchColumn = -1
            searchErrorMessage = null
            tab.session.trimWorkingSet()
            return
        }

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
                "case:sensitive \"${queryText.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            else -> queryText
        }

        tab.searchJob = coroutineScope.launch {
            try {
                if (!immediate) {
                    delay(200) // Debounce rapid keystrokes to prevent concurrent query pile-up
                }

                var hasJumpedToFirstMatch = false

                val parseAndSearchResult: scala.util.Either<QueryParseError, SearchResult> = withContext(Dispatchers.Default) {
                    tab.session.searchProgressive(effectiveQuery) { rawPartialResult: SearchResult ->
                        if (tab.searchQuery == queryText) {
                            val partialResult = if (tab.isTemporalFocusActive) {
                                rawPartialResult.constrainToRange(tab.temporalFocusStartLine, tab.temporalFocusEndLine)
                            } else {
                                rawPartialResult
                            }
                            tab.searchResult = partialResult

                            if (!hasJumpedToFirstMatch && !partialResult.isEmpty()) {
                                val minLine = if (tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                val firstLine = partialResult.findNextLine(minLine)
                                if (firstLine >= 0L && (!tab.isTemporalFocusActive || firstLine <= tab.temporalFocusEndLine)) {
                                    hasJumpedToFirstMatch = true
                                    val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                                    if (tab.isFilteredView) {
                                        val filteredView = tab.session.createFilteredView(partialResult.matchingLines())
                                        val lineText = try { filteredView.getVirtualLineText(0L, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                        val intervals = findNeedleIntervals(
                                            lineText,
                                            needles,
                                            isMatchCase = tab.isSearchMatchCase,
                                            isWholeWord = tab.isSearchWholeWord,
                                            isRegex = tab.isSearchRegex
                                        )
                                        tab.topVisibleLine = 0L
                                        tab.selectedLineIndex = 0L
                                        tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                        tab.searchNavigationGeneration++
                                    } else {
                                        val lineText = try { tab.session.getLineText(firstLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
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
                                            val maxTop = maxOf(0L, (tab.session.totalLines() as Number).toLong() - tab.visibleRowCount)
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

                // Guard: Discard stale result if query was superseded while searching
                if (tab.searchQuery != queryText) return@launch

                parseAndSearchResult.fold(
                    { parseError: QueryParseError ->
                        searchErrorMessage = parseError.message()
                        tab.searchResult = null
                        tab.selectedMatchColumn = -1
                    },
                    { rawSearchResult: SearchResult ->
                        searchErrorMessage = null
                        val searchResult = if (tab.isTemporalFocusActive) {
                            rawSearchResult.constrainToRange(tab.temporalFocusStartLine, tab.temporalFocusEndLine)
                        } else {
                            rawSearchResult
                        }
                        tab.searchResult = searchResult

                        if (!hasJumpedToFirstMatch && !searchResult.isEmpty()) {
                            val minLine = if (tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                            val firstLine = searchResult.findNextLine(minLine)
                            if (firstLine >= 0L && (!tab.isTemporalFocusActive || firstLine <= tab.temporalFocusEndLine)) {
                                val needles = extractSearchNeedles(queryText, isRegex = tab.isSearchRegex)
                                if (tab.isFilteredView) {
                                    val filteredView = tab.session.createFilteredView(searchResult.matchingLines())
                                    val lineText = try { filteredView.getVirtualLineText(0L, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
                                    val intervals = findNeedleIntervals(
                                        lineText,
                                        needles,
                                        isMatchCase = tab.isSearchMatchCase,
                                        isWholeWord = tab.isSearchWholeWord,
                                        isRegex = tab.isSearchRegex
                                    )
                                    tab.topVisibleLine = 0L
                                    tab.selectedLineIndex = 0L
                                    tab.selectedMatchColumn = intervals.firstOrNull()?.first ?: 0
                                    tab.searchNavigationGeneration++
                                } else {
                                    val lineText = try { tab.session.getLineText(firstLine, ViewportState.MAX_SHAPED_CHARS) } catch (_: Exception) { "" }
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
                                        val maxTop = maxOf(0L, (tab.session.totalLines() as Number).toLong() - tab.visibleRowCount)
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
            } catch (e: CancellationException) {
                // Normal coroutine cancellation when user continues typing
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext.fromSession(
                    tab.filePath,
                    tab.session.fileSizeBytes(),
                    (tab.session.totalLines() as Number).toLong(),
                    tab.topVisibleLine
                )
                WorkstationErrorHandler.reportException(e, context)
                searchErrorMessage = "Search failed: ${e.message}"
            } finally {
                tab.session.trimWorkingSet()
            }
        }
    }

    fun navigateSearch(forward: Boolean) {
        try {
            val tab = currentTab ?: return
            val result = tab.searchResult ?: return
            if (result.isEmpty()) return

            val needles = extractSearchNeedles(tab.searchQuery, isRegex = tab.isSearchRegex)
            if (needles.isEmpty()) return

            if (tab.isFilteredView) {
                val totalFiltered = result.totalMatches()
                if (totalFiltered <= 0L) return
                val filteredView = tab.session.createFilteredView(result.matchingLines())

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

                val currentVirtual = (tab.selectedLineIndex ?: tab.topVisibleLine).coerceIn(0L, totalFiltered - 1L)
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
                        tab.selectedLineIndex = currentVirtual
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
                        tab.topVisibleLine = targetVirtual
                        tab.selectedLineIndex = targetVirtual
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
                        tab.selectedLineIndex = currentVirtual
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
                        tab.topVisibleLine = targetVirtual
                        tab.selectedLineIndex = targetVirtual
                        tab.selectedMatchColumn = targetIntervals.lastOrNull()?.first ?: 0
                        tab.subLineScrollOffsetPx = 0f
                        tab.searchNavigationGeneration++
                    }
                }
            } else {
                val isTemporal = tab.isTemporalFocusActive
                val minLine = if (isTemporal) tab.temporalFocusStartLine else 0L
                val maxLine = if (isTemporal) tab.temporalFocusEndLine else ((tab.session.totalLines() as Number).toLong() - 1L)

                fun getLineIntervals(line: Long): List<Pair<Int, Int>> {
                    if (line < 0L || line >= (tab.session.totalLines() as Number).toLong()) return emptyList()
                    val lineText = try {
                        tab.session.getLineText(line, ViewportState.MAX_SHAPED_CHARS)
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
                        val maxTop = maxOf(0L, (tab.session.totalLines() as Number).toLong() - tab.visibleRowCount)
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
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            WorkstationErrorHandler.reportException(e)
        }
    }

    fun navigateAnomaly(forward: Boolean) {
        try {
            val tab = currentTab ?: return
            val session = tab.session
            val currentLine = tab.topVisibleLine
            val targetLine = if (forward) {
                val next = session.findNextAnomaly(currentLine)
                if (next >= 0L) next else session.findNextAnomaly(-1L)
            } else {
                val prev = session.findPreviousAnomaly(currentLine)
                if (prev >= 0L) prev else session.findPreviousAnomaly((session.totalLines() as Number).toLong())
            }
            if (targetLine >= 0L) {
                tab.topVisibleLine = targetLine
                tab.selectedLineIndex = targetLine
                tab.subLineScrollOffsetPx = 0f
                statusMessageText = "Jumped to anomaly at line ${formatNumberWithCommas(targetLine + 1L)}"
            } else {
                statusMessageText = "No errors or warnings found in document."
            }
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            WorkstationErrorHandler.reportException(e)
        }
    }

    fun exportSubFile() {
        val tab = currentTab ?: return
        val defaultName = "excerpt_${File(tab.filePath).nameWithoutExtension}.log"
        val destinationPath = showNativeSaveFileDialog(defaultName) ?: return

        coroutineScope.launch {
            try {
                statusMessageText = "Exporting sub-file to $destinationPath..."
                val sliceResult = withContext(Dispatchers.IO) {
                    val targetFile = File(destinationPath)
                    if (tab.isFilteredView && tab.searchResult != null) {
                        tab.session.exportFilteredLines(tab.searchResult!!.matchingLines(), targetFile.toPath())
                    } else {
                        val endLine = (tab.topVisibleLine + 1000L).coerceAtMost((tab.session.totalLines() as Number).toLong() - 1L)
                        tab.session.exportContiguousRange(tab.topVisibleLine, endLine, targetFile.toPath())
                    }
                }

                statusMessageText = "Exported ${sliceResult.linesExported()} lines (${formatByteSize(sliceResult.bytesWritten())}) in ${sliceResult.durationNanos() / 1_000_000L} ms. Opening in new tab..."
                // Automatically open as a new tab
                loadLogFile(destinationPath, asNewTab = true)
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext.fromSession(
                    tab.filePath,
                    tab.session.fileSizeBytes(),
                    (tab.session.totalLines() as Number).toLong(),
                    tab.topVisibleLine
                )
                WorkstationErrorHandler.reportException(e, context)
                statusMessageText = "Export failed: ${e.message}"
            }
        }
    }

    fun openFileViaNativeDialog() {
        val chosenPath = showNativeOpenFileDialog()
        if (chosenPath != null) {
            loadLogFile(chosenPath, asNewTab = true)
        }
    }

    fun generateDiagnosticReport() {
        val tab = currentTab
        val context = if (tab != null) {
            ForensicReportContext.fromSession(tab.filePath, tab.session.fileSizeBytes(), (tab.session.totalLines() as Number).toLong(), tab.topVisibleLine)
        } else {
            ForensicReportContext.empty()
        }
        // Reuses the forensic pipeline (disk persistence + banner) with a non-fatal marker throwable
        val report = WorkstationErrorHandler.reportException(OperatorDiagnosticSnapshot(), context)
        statusMessageText = "Diagnostic report ${report.reportId()} generated — ${report.reportFilePathString()}"
    }

    /** Central dispatcher for every action emitted by the title bar hamburger menu. */
    fun handleMenuAction(action: MenuAction) {
        try {
            val updatedSettings = MenuSettingsReducer.reduce(action, SettingsManager.currentSettings)
            if (updatedSettings != null) {
                SettingsManager.updateSettings(updatedSettings)
            }
            when (action) {
                is MenuAction.OpenFile -> openFileViaNativeDialog()
                is MenuAction.OpenRecentFile -> loadLogFile(action.path, asNewTab = true)
                is MenuAction.ClearRecentFiles -> statusMessageText = "Cleared recent files."
                is MenuAction.ExportSubFile -> {
                    if (activeSession != null) {
                        isExportDialogVisible = true
                    } else {
                        statusMessageText = "Open a log file to export sub-files."
                    }
                }
                is MenuAction.Find -> {
                    isSearchBarVisible = true
                    searchFocusTrigger++
                }
                is MenuAction.GoToLineTimestamp -> {
                    if (activeSession != null) {
                        isGoToLineDialogVisible = true
                    } else {
                        statusMessageText = "Open a log file to use Go To Line / Timestamp."
                    }
                }
                is MenuAction.TemporalRange -> {
                    val tab = currentTab
                    if (tab != null) {
                        tab.isTimeScrubberOpen = !tab.isTimeScrubberOpen
                    } else {
                        statusMessageText = "Open a log file to use the temporal range scrubber."
                    }
                }
                is MenuAction.SetViewportMode -> statusMessageText = "Viewport layout: ${action.mode.displayLabel}"
                is MenuAction.ToggleView -> Unit
                is MenuAction.SetTheme -> Unit
                is MenuAction.OpenSettings -> onSetSettingsDialogVisible(true)
                is MenuAction.DiagnosticReport -> generateDiagnosticReport()
                is MenuAction.KeyboardShortcuts -> isShortcutsDialogVisible = true
                is MenuAction.About -> isAboutDialogVisible = true
                is MenuAction.Exit -> onExitRequest()
            }
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            WorkstationErrorHandler.reportException(e)
        }
    }

    // rememberUpdatedState keeps the long-lived collector bound to the latest tab/session closures
    val latestMenuActionHandler by rememberUpdatedState(::handleMenuAction)
    LaunchedEffect(menuActions) {
        menuActions.collect { menuAction -> latestMenuActionHandler(menuAction) }
    }

    DisposableEffect(isSearchBarVisible, isGoToLineDialogVisible, isSettingsDialogVisible, isAboutDialogVisible, isShortcutsDialogVisible, isExportDialogVisible, currentTab, activeTabIndex) {
        WorkstationKeyDispatcher.onGlobalKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val isCtrlOrCmd = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                when {
                    keyEvent.key == Key.F1 || (isCtrlOrCmd && keyEvent.key == Key.Slash) -> {
                        isShortcutsDialogVisible = true
                        true
                    }
                    isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.S -> {
                        if (activeSession != null) {
                            isExportDialogVisible = true
                            true
                        } else false
                    }
                    isCtrlOrCmd && keyEvent.key == Key.Comma -> {
                        onSetSettingsDialogVisible(true)
                        true
                    }
                    isCtrlOrCmd && (keyEvent.key == Key.Plus || keyEvent.key == Key.Equals || keyEvent.key == Key.NumPadAdd) -> {
                        val current = SettingsManager.currentSettings
                        val updatedPt = (current.editorFontSizePt + 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
                        SettingsManager.updateSettings(current.copy(editorFontSizePt = updatedPt))
                        true
                    }
                    isCtrlOrCmd && (keyEvent.key == Key.Minus || keyEvent.key == Key.NumPadSubtract) -> {
                        val current = SettingsManager.currentSettings
                        val updatedPt = (current.editorFontSizePt - 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
                        SettingsManager.updateSettings(current.copy(editorFontSizePt = updatedPt))
                        true
                    }
                    isCtrlOrCmd && (keyEvent.key == Key.Zero || keyEvent.key == Key.NumPad0) -> {
                        val current = SettingsManager.currentSettings
                        SettingsManager.updateSettings(current.copy(editorFontSizePt = FontSpec.DEFAULT_FONT_SIZE))
                        true
                    }
                    isCtrlOrCmd && keyEvent.key == Key.O -> {
                        openFileViaNativeDialog()
                        true
                    }
                    isCtrlOrCmd && keyEvent.key == Key.F -> {
                        isSearchBarVisible = true
                        searchFocusTrigger++
                        true
                    }
                    isCtrlOrCmd && keyEvent.key == Key.G -> {
                        if (activeSession != null) {
                            isGoToLineDialogVisible = true
                            true
                        } else false
                    }
                    isCtrlOrCmd && keyEvent.key == Key.T -> {
                        val tab = currentTab
                        if (tab != null && activeSession != null) {
                            tab.isTimeScrubberOpen = !tab.isTimeScrubberOpen
                            true
                        } else false
                    }
                    isCtrlOrCmd && keyEvent.key == Key.C -> {
                        val tab = currentTab
                        if (tab != null) {
                            if (tab.selectionState.hasSelection()) {
                                try {
                                    val filteredView = if (tab.isFilteredView && tab.searchResult != null) {
                                        tab.session.createFilteredView(tab.searchResult!!.matchingLines())
                                    } else null
                                    val temporalStart = if (filteredView == null && tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                    val virtualLineCount = when {
                                        filteredView != null -> filteredView.totalFilteredLines()
                                        tab.isTemporalFocusActive -> (tab.temporalFocusEndLine - temporalStart + 1L).coerceAtLeast(0L)
                                        else -> tab.session.totalLines()
                                    }
                                    val selectedText = tab.selectionState.extractSelectedText(virtualLineCount, { virtualRow ->
                                        val physicalLine = filteredView?.physicalLineForVirtualIndex(virtualRow) ?: temporalStart + virtualRow
                                        tab.session.getLineText(physicalLine, ViewportState.MAX_SHAPED_CHARS)
                                    })
                                    if (selectedText.isNotEmpty()) {
                                        val selection = StringSelection(selectedText)
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                        statusMessageText = "Copied ${formatNumberWithCommas(selectedText.length.toLong())} characters to clipboard."
                                    }
                                } catch (_: SelectionTooLargeException) {
                                    statusMessageText = "Selection too large to copy (limit: 1 MiB or 8,192 rows)."
                                } catch (_: Exception) {
                                    statusMessageText = "Could not copy the selected log text."
                                }
                                true
                            } else {
                                val selectedLine = tab.selectedLineIndex
                                if (selectedLine != null && selectedLine >= 0L) {
                                    val lineText = try {
                                        tab.session.getLineText(selectedLine, ViewportState.MAX_SHAPED_CHARS)
                                    } catch (_: Exception) { "" }

                                    if (lineText.isNotEmpty()) {
                                        try {
                                            val selection = StringSelection(lineText)
                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                            statusMessageText = "Copied line ${formatNumberWithCommas(selectedLine + 1L)} to clipboard."
                                        } catch (_: Throwable) {}
                                    }
                                    true
                                } else false
                            }
                        } else false
                    }
                    keyEvent.key == Key.F3 -> {
                        navigateSearch(forward = !keyEvent.isShiftPressed)
                        true
                    }
                    keyEvent.key == Key.F4 -> {
                        navigateAnomaly(forward = !keyEvent.isShiftPressed)
                        true
                    }
                    keyEvent.key == Key.Escape -> {
                        if (isSettingsDialogVisible) {
                            onSetSettingsDialogVisible(false)
                            true
                        } else if (isGoToLineDialogVisible) {
                            isGoToLineDialogVisible = false
                            true
                        } else if (isSearchBarVisible) {
                            isSearchBarVisible = false
                            focusManager.clearFocus()
                            triggerSearch("")
                            true
                        } else if (currentTab?.isTimeScrubberOpen == true) {
                            currentTab.isTimeScrubberOpen = false
                            true
                        } else if (currentTab?.selectionState?.hasSelection() == true) {
                            currentTab.selectionState.clearSelection()
                            true
                        } else if (currentTab?.selectedLineIndex != null) {
                            currentTab.selectedLineIndex = null
                            focusManager.clearFocus()
                            true
                        } else false
                    }
                    else -> false
                }
            } else false
        }
        onDispose {
            WorkstationKeyDispatcher.onGlobalKeyEvent = null
        }
    }

    LaunchedEffect(currentTab, currentTab?.session?.totalLines()) {
        onActiveDocTitleChange(currentTab?.let { File(it.filePath).name })
    }

    LaunchedEffect(Unit) {
        if (!initialFilePath.isNullOrBlank()) {
            loadLogFile(initialFilePath, asNewTab = true)
        }
    }

    LaunchedEffect(droppedFilePath) {
        if (!droppedFilePath.isNullOrBlank()) {
            loadLogFile(droppedFilePath, asNewTab = true)
            onClearDroppedFilePath()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(UmbrixTheme.colors.surface)
                .onPreviewKeyEvent { keyEvent ->
                WorkstationKeyDispatcher.handleKeyEvent(keyEvent)
            }
    ) {
        // --- Active Forensic Error Banner (Non-Crashing Exception Recovery) ---
        WorkstationErrorHandler.activeError?.let { activeErrorReport ->
            ForensicErrorBanner(
                report = activeErrorReport,
                onDismiss = { WorkstationErrorHandler.activeError = null }
            )
            Divider(Orientation.Horizontal)
        }

        // --- Multi-Tab Bar ---
        TabBar(
            tabs = openTabs,
            activeTabIndex = activeTabIndex,
            onSelectTab = { index -> activeTabIndex = index },
            onCloseTab = { index -> closeTab(index) },
            onNewTabClicked = {
                val chosenPath = showNativeOpenFileDialog()
                if (chosenPath != null) {
                    loadLogFile(chosenPath, asNewTab = true)
                }
            }
        )

        Divider(Orientation.Horizontal)

        // --- ReDoS-Immune Search Bar (Ctrl+F) ---
        if (isSearchBarVisible) {
            Divider(Orientation.Horizontal)
            SearchBar(
                queryText = currentTab?.searchQuery ?: "",
                onQueryChange = { newQuery ->
                    triggerSearch(newQuery)
                },
                searchResult = currentTab?.searchResult,
                errorMessage = searchErrorMessage,
                focusRequester = searchFocusRequester,
                focusTrigger = searchFocusTrigger,
                isMatchCase = currentTab?.isSearchMatchCase ?: false,
                onToggleMatchCase = {
                    currentTab?.let { tab ->
                        tab.isSearchMatchCase = !tab.isSearchMatchCase
                        triggerSearch(tab.searchQuery, immediate = true)
                    }
                },
                isWholeWord = currentTab?.isSearchWholeWord ?: false,
                onToggleWholeWord = {
                    currentTab?.let { tab ->
                        tab.isSearchWholeWord = !tab.isSearchWholeWord
                        triggerSearch(tab.searchQuery, immediate = true)
                    }
                },
                isRegex = currentTab?.isSearchRegex ?: false,
                onToggleRegex = {
                    currentTab?.let { tab ->
                        tab.isSearchRegex = !tab.isSearchRegex
                        triggerSearch(tab.searchQuery, immediate = true)
                    }
                },
                isFilteredView = currentTab?.isFilteredView ?: false,
                onToggleFilterView = {
                    currentTab?.let { tab ->
                        val searchResult = tab.searchResult
                        if (searchResult != null && !searchResult.isEmpty) {
                            val session = tab.session
                            if (!tab.isFilteredView) {
                                val currentPhysical = tab.selectedLineIndex ?: tab.topVisibleLine
                                val filteredView = session.createFilteredView(searchResult.matchingLines())
                                val virtualIndex = (filteredView.virtualIndexForPhysicalLine(currentPhysical) as Number).toLong()
                                val totalFiltered = (filteredView.totalFilteredLines() as Number).toLong()
                                tab.topVisibleLine = virtualIndex.coerceIn(0L, maxOf(0L, totalFiltered - 1L))
                                tab.selectedLineIndex = tab.topVisibleLine
                                tab.isFilteredView = true
                            } else {
                                val filteredView = session.createFilteredView(searchResult.matchingLines())
                                val currentVirtual = tab.selectedLineIndex ?: tab.topVisibleLine
                                val physical = (filteredView.physicalLineForVirtualIndex(currentVirtual) as Number).toLong()
                                tab.topVisibleLine = physical.coerceIn(0L, maxOf(0L, (session.totalLines() as Number).toLong() - 1L))
                                tab.selectedLineIndex = physical
                                tab.isFilteredView = false
                            }
                        }
                    }
                },
                onNavigateNext = {
                    val tab = currentTab
                    if (tab != null && tab.searchResult == null && tab.searchQuery.isNotBlank()) {
                        triggerSearch(tab.searchQuery, immediate = true)
                    } else {
                        navigateSearch(forward = true)
                    }
                },
                onNavigatePrev = {
                    val tab = currentTab
                    if (tab != null && tab.searchResult == null && tab.searchQuery.isNotBlank()) {
                        triggerSearch(tab.searchQuery, immediate = true)
                    } else {
                        navigateSearch(forward = false)
                    }
                },
                onExportSubFile = { exportSubFile() },
                onClose = {
                    isSearchBarVisible = false
                    focusManager.clearFocus()
                    triggerSearch("")
                }
            )
        }

        // --- Dual-Handle Time Range Scrubber (Ctrl+T) ---
        if (currentTab != null && currentTab.isTimeScrubberOpen && activeSession != null) {
            Divider(Orientation.Horizontal)
            TemporalRangeScrubber(
                tab = currentTab,
                session = activeSession,
                onTrimView = { startLine, endLine, startMicros, endMicros ->
                    currentTab.temporalFocusStartLine = startLine
                    currentTab.temporalFocusEndLine = endLine
                    currentTab.temporalFocusStartMicros = startMicros
                    currentTab.temporalFocusEndMicros = endMicros
                    currentTab.isTemporalFocusActive = true
                    currentTab.topVisibleLine = 0L
                    currentTab.selectedLineIndex = 0L
                    val trimmedCount = maxOf(0L, endLine - startLine + 1L)
                    statusMessageText = "Trimmed view to ${formatNumberWithCommas(trimmedCount)} lines."
                },
                onClearTrim = {
                    currentTab.isTemporalFocusActive = false
                    statusMessageText = "Cleared temporal focus. Showing all ${formatNumberWithCommas((activeSession.totalLines() as Number).toLong())} lines."
                },
                onClose = {
                    currentTab.isTimeScrubberOpen = false
                }
            )
        }

        Divider(Orientation.Horizontal)

        // --- Center Log Viewport Canvas ---
        LogViewportCanvas(
            tab = currentTab,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Divider(Orientation.Horizontal)

        // --- Bottom Status Bar ---
        BottomStatusBar(
            statusMessage = statusMessageText,
            activeSession = activeSession,
            topVisibleLine = currentTab?.topVisibleLine ?: 0L,
            activeTab = currentTab,
            onNavigateNextAnomaly = { navigateAnomaly(forward = true) },
            onNavigatePrevAnomaly = { navigateAnomaly(forward = false) }
        )
    }

    // --- Go to Line or Timestamp Modal Dialog (Ctrl+G / Cmd+G) ---
    if (isGoToLineDialogVisible && activeSession != null) {
        GoToTimestampDialog(
            session = activeSession,
            currentLine = currentTab?.selectedLineIndex ?: currentTab?.topVisibleLine ?: 0L,
            onGoToLine = { targetLine ->
                currentTab?.let { tab ->
                    if (tab.isTemporalFocusActive) {
                        val clampedLine = targetLine.coerceIn(0L, maxOf(0L, (activeSession.totalLines() as Number).toLong() - 1L))
                        if (clampedLine in tab.temporalFocusStartLine..tab.temporalFocusEndLine) {
                            tab.topVisibleLine = clampedLine - tab.temporalFocusStartLine
                            tab.selectedLineIndex = clampedLine
                        } else {
                            // If target line is outside the active focus slice, clear focus to reveal it
                            tab.isTemporalFocusActive = false
                            tab.topVisibleLine = clampedLine
                            tab.selectedLineIndex = clampedLine
                        }
                    } else {
                        tab.topVisibleLine = targetLine
                        tab.selectedLineIndex = targetLine
                    }
                }
                isGoToLineDialogVisible = false
            },
            onDismiss = {
                isGoToLineDialogVisible = false
            }
        )
    }

    // --- IntelliJ-Style Settings Modal Dialog (Ctrl+,) ---
    if (isSettingsDialogVisible) {
        SettingsDialog(
            initialSettings = SettingsManager.currentSettings,
            onSaveSettings = { updatedSettings ->
                SettingsManager.updateSettings(updatedSettings)
            },
            onDismiss = {
                onSetSettingsDialogVisible(false)
            }
        )
    }

    // --- Standardized About Dialog ---
    if (isAboutDialogVisible) {
        AboutDialog(
            onDismiss = { isAboutDialogVisible = false }
        )
    }

    // --- Standardized Keyboard Shortcuts Cheatsheet Dialog (F1 / Ctrl+/) ---
    if (isShortcutsDialogVisible) {
        KeyboardShortcutsDialog(
            onDismiss = { isShortcutsDialogVisible = false }
        )
    }

    // --- Standardized Export Sub-File Dialog (Ctrl+Shift+S) ---
    if (isExportDialogVisible && activeSession != null) {
        ExportSubFileDialog(
            session = activeSession,
            currentLine = currentTab.selectedLineIndex ?: currentTab.topVisibleLine,
            onExportRequested = { startLine, endLine, destinationFile, _ ->
                coroutineScope.launch {
                    try {
                        statusMessageText = "Exporting sub-file to ${destinationFile.absolutePath}..."
                        val sliceResult = withContext(Dispatchers.IO) {
                            currentTab.session.exportContiguousRange(startLine, endLine, destinationFile.toPath())
                        }
                        statusMessageText = "Exported ${sliceResult.linesExported()} lines (${formatByteSize(sliceResult.bytesWritten())}) in ${sliceResult.durationNanos() / 1_000_000L} ms. Opening in new tab..."
                        loadLogFile(destinationFile.absolutePath, asNewTab = true)
                    } catch (e: Throwable) {
                        if (ForensicErrorReportGenerator.isFatal(e)) throw e
                        val context = ForensicReportContext.fromSession(
                            currentTab.filePath,
                            currentTab.session.fileSizeBytes(),
                            (currentTab.session.totalLines() as Number).toLong(),
                            currentTab.topVisibleLine
                        )
                        WorkstationErrorHandler.reportException(e, context)
                    }
                }
            },
            onDismiss = { isExportDialogVisible = false }
        )
    }
}
}

/**
 * Non-fatal marker throwable used to capture an operator-requested forensic snapshot (menu
 * "Diagnostic Report") through the standard [ForensicErrorReportGenerator] pipeline.
 */
private class OperatorDiagnosticSnapshot :
    RuntimeException("Operator-requested diagnostic snapshot (not an error)")
