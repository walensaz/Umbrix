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
import umbrix.ui.dialog.GoToLineDialog
import umbrix.ui.dialog.GoToTimestampDialog
import umbrix.ui.dialog.SettingsDialog
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.search.SearchBar
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.temporal.TemporalRangeScrubber
import umbrix.ui.theme.UmbrixColors
import umbrix.ui.toolbar.BottomStatusBar
import umbrix.ui.toolbar.TabBar
import umbrix.ui.toolbar.TopControlToolbar
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
 * Main workstation layout coordinating multi-document tabs, top controls,
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
    onSetSettingsDialogVisible: (Boolean) -> Unit = {}
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

    var currentFilePathInput by remember { mutableStateOf(initialFilePath ?: "") }
    var isIndexingActive by remember { mutableStateOf(false) }
    var statusMessageText by remember { mutableStateOf("Ready — No log file loaded.") }
    var editorFontSizeSp by remember { mutableStateOf(13.5f) }

    var isSearchBarVisible by remember { mutableStateOf(false) }
    var searchFocusTrigger by remember { mutableStateOf(0) }
    var searchErrorMessage by remember { mutableStateOf<String?>(null) }
    var isGoToLineDialogVisible by remember { mutableStateOf(false) }

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

                currentFilePathInput = targetFile.absolutePath
                val formattedSize = formatByteSize(newSession.fileSizeBytes())
                val initialLines = formatNumberWithCommas(newSession.totalLines())
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
                                    newTab.liveTotalLines = newSession.totalLines()
                                }
                            }
                            withContext(Dispatchers.Main) {
                                newTab.indexingProgress = 100.0
                                newTab.isIndexing = false
                                isIndexingActive = false
                                val finalLines = formatNumberWithCommas(newSession.totalLines())
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
            searchErrorMessage = null
            tab.session.trimWorkingSet()
            return
        }

        tab.searchJob = coroutineScope.launch {
            try {
                if (!immediate) {
                    delay(200) // Debounce rapid keystrokes to prevent concurrent query pile-up
                }

                var hasJumpedToFirstMatch = false

                val parseAndSearchResult: scala.util.Either<QueryParseError, SearchResult> = withContext(Dispatchers.Default) {
                    tab.session.searchProgressive(queryText) { partialResult: SearchResult ->
                        if (tab.searchQuery == queryText) {
                            tab.searchResult = partialResult
                            if (!hasJumpedToFirstMatch && !partialResult.isEmpty()) {
                                val firstLine = partialResult.findNextLine(0L)
                                if (firstLine >= 0L) {
                                    hasJumpedToFirstMatch = true
                                    if (tab.isFilteredView) {
                                        tab.topVisibleLine = 0L
                                        tab.selectedLineIndex = 0L
                                    } else {
                                        tab.topVisibleLine = firstLine
                                        tab.selectedLineIndex = firstLine
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
                    },
                    { searchResult: SearchResult ->
                        searchErrorMessage = null
                        tab.searchResult = searchResult
                        if (!hasJumpedToFirstMatch && !searchResult.isEmpty()) {
                            val firstLine = searchResult.findNextLine(0L)
                            if (firstLine >= 0L) {
                                if (tab.isFilteredView) {
                                    tab.topVisibleLine = 0L
                                    tab.selectedLineIndex = 0L
                                } else {
                                    tab.topVisibleLine = firstLine
                                    tab.selectedLineIndex = firstLine
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
                    tab.session.totalLines(),
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

            if (tab.isFilteredView) {
                val totalFiltered = result.totalMatches()
                if (totalFiltered <= 0L) return
                val targetVirtualLine = if (forward) {
                    if (tab.topVisibleLine + 1L < totalFiltered) tab.topVisibleLine + 1L else 0L
                } else {
                    if (tab.topVisibleLine - 1L >= 0L) tab.topVisibleLine - 1L else totalFiltered - 1L
                }
                tab.topVisibleLine = targetVirtualLine
                tab.selectedLineIndex = targetVirtualLine
            } else {
                val targetLine = if (forward) {
                    val nextLine = result.findNextLine(tab.topVisibleLine + 1L)
                    if (nextLine >= 0L) {
                        nextLine
                    } else {
                        val firstLine = result.findNextLine(0L)
                        if (firstLine >= 0L) firstLine else tab.topVisibleLine
                    }
                } else {
                    val prevLine = result.findPreviousLine(tab.topVisibleLine - 1L)
                    if (prevLine >= 0L) {
                        prevLine
                    } else {
                        val lastLine = result.findPreviousLine(tab.session.totalLines() - 1L)
                        if (lastLine >= 0L) lastLine else tab.topVisibleLine
                    }
                }
                tab.topVisibleLine = targetLine
                tab.selectedLineIndex = targetLine
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
                if (prev >= 0L) prev else session.findPreviousAnomaly(session.totalLines())
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
                        val endLine = (tab.topVisibleLine + 1000L).coerceAtMost(tab.session.totalLines() - 1L)
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
                    tab.session.totalLines(),
                    tab.topVisibleLine
                )
                WorkstationErrorHandler.reportException(e, context)
                statusMessageText = "Export failed: ${e.message}"
            }
        }
    }

    DisposableEffect(isSearchBarVisible, isGoToLineDialogVisible, isSettingsDialogVisible, currentTab, activeTabIndex) {
        WorkstationKeyDispatcher.onGlobalKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val isCtrlOrCmd = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                when {
                    isCtrlOrCmd && keyEvent.key == Key.Comma -> {
                        onSetSettingsDialogVisible(true)
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
                                val selectedText = tab.selectionState.extractSelectedText(tab.session, ViewportState.MAX_SHAPED_CHARS)
                                if (selectedText.isNotEmpty()) {
                                    try {
                                        val selection = StringSelection(selectedText)
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                        statusMessageText = "Copied ${formatNumberWithCommas(selectedText.length.toLong())} characters to clipboard."
                                    } catch (_: Throwable) {}
                                }
                                true
                            } else {
                                val selectedLine = tab.selectedLineIndex
                                if (selectedLine != null && selectedLine >= 0L) {
                                    val lineText = try {
                                        if (tab.isFilteredView && tab.searchResult != null) {
                                            val filteredView = tab.session.createFilteredView(tab.searchResult!!.matchingLines())
                                            filteredView.getVirtualLineText(selectedLine, ViewportState.MAX_SHAPED_CHARS)
                                        } else {
                                            tab.session.getLineText(selectedLine, ViewportState.MAX_SHAPED_CHARS)
                                        }
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
                .background(UmbrixColors.surface)
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

        // --- Top Control Toolbar ---
        TopControlToolbar(
            filePathText = currentFilePathInput,
            onFilePathTextChange = { currentFilePathInput = it },
            onOpenFileClicked = {
                val chosenPath = showNativeOpenFileDialog()
                if (chosenPath != null) {
                    loadLogFile(chosenPath, asNewTab = true)
                }
            },
            onLoadPathClicked = {
                if (currentFilePathInput.isNotBlank()) {
                    loadLogFile(currentFilePathInput, asNewTab = true)
                }
            },
            isIndexing = isIndexingActive,
            session = activeSession,
            fontSizeSp = editorFontSizeSp,
            onIncreaseFontSize = { editorFontSizeSp = (editorFontSizeSp + 1f).coerceIn(10f, 26f) },
            onDecreaseFontSize = { editorFontSizeSp = (editorFontSizeSp - 1f).coerceIn(10f, 26f) },
            isSearchBarOpen = isSearchBarVisible,
            onToggleSearchBar = {
                isSearchBarVisible = !isSearchBarVisible
                if (isSearchBarVisible) searchFocusTrigger++
            },
            isTimeScrubberOpen = currentTab?.isTimeScrubberOpen ?: false,
            onToggleTimeScrubber = {
                currentTab?.let { tab ->
                    tab.isTimeScrubberOpen = !tab.isTimeScrubberOpen
                }
            },
            onOpenSettings = { onSetSettingsDialogVisible(true) }
        )

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
                isFilteredView = currentTab?.isFilteredView ?: false,
                onToggleFilterView = {
                    currentTab?.let { tab ->
                        val searchResult = tab.searchResult
                        if (searchResult != null && !searchResult.isEmpty) {
                            val session = tab.session
                            if (!tab.isFilteredView) {
                                val currentPhysical = tab.selectedLineIndex ?: tab.topVisibleLine
                                val filteredView = session.createFilteredView(searchResult.matchingLines())
                                val virtualIndex = filteredView.virtualIndexForPhysicalLine(currentPhysical)
                                val totalFiltered = filteredView.totalFilteredLines()
                                tab.topVisibleLine = virtualIndex.coerceIn(0L, maxOf(0L, totalFiltered - 1L))
                                tab.selectedLineIndex = tab.topVisibleLine
                                tab.isFilteredView = true
                            } else {
                                val filteredView = session.createFilteredView(searchResult.matchingLines())
                                val currentVirtual = tab.selectedLineIndex ?: tab.topVisibleLine
                                val physical = filteredView.physicalLineForVirtualIndex(currentVirtual)
                                tab.topVisibleLine = physical.coerceIn(0L, maxOf(0L, session.totalLines() - 1L))
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
                onNavigateNextAnomaly = {
                    navigateAnomaly(forward = true)
                },
                onNavigatePrevAnomaly = {
                    navigateAnomaly(forward = false)
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
                    statusMessageText = "Cleared temporal focus. Showing all ${formatNumberWithCommas(activeSession.totalLines())} lines."
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
            editorFontSizeSp = editorFontSizeSp,
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
            activeTab = currentTab
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
                        val clampedLine = targetLine.coerceIn(0L, maxOf(0L, activeSession.totalLines() - 1L))
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
}
}
