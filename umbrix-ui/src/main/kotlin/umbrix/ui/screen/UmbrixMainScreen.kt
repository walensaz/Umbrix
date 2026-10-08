package umbrix.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import umbrix.ui.ai.AiChatDrawer
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
import androidx.compose.ui.input.key.isAltPressed
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
import umbrix.ui.toast.ToastHost
import umbrix.ui.toast.ToastManager
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.parser.QueryParseError
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.ui.components.ForensicErrorBanner
import umbrix.ui.bookmarks.BookmarkManagerDialog
import umbrix.ui.dialog.AboutDialog
import umbrix.ui.dialog.ExportSubFileDialog
import umbrix.ui.dialog.GoToLineDialog
import umbrix.ui.dialog.GoToTimestampDialog
import umbrix.ui.dialog.KeyboardShortcutsDialog
import umbrix.ui.dialog.RenameScratchpadDialog
import umbrix.ui.dialog.SettingsDialog
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.palette.CommandExecutionContext
import umbrix.ui.palette.CommandPaletteDialog
import umbrix.ui.scratchpad.ScratchpadEditorCanvas
import umbrix.ui.scratchpad.ScratchpadManager
import umbrix.ui.scratchpad.ScratchpadManagerDialog
import umbrix.ui.timeline.EventTag
import umbrix.ui.timeline.InvestigationTimelineDrawer
import umbrix.ui.timeline.InvestigationTimelineManager
import umbrix.ui.timeline.TimelineExportDialog
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
                    tab.session?.close()
                } catch (_: Throwable) {}
            }
        }
    }
    var activeTabIndex by remember { mutableStateOf(0) }

    val timelineManager = remember { InvestigationTimelineManager() }
    val scratchpadManager = remember { ScratchpadManager() }
    var isTimelineDrawerOpen by remember { mutableStateOf(false) }
    var isBookmarkDialogOpen by remember { mutableStateOf(false) }
    var isScratchpadManagerDialogOpen by remember { mutableStateOf(false) }
    var renamingScratchpadTabIndex by remember { mutableStateOf<Int?>(null) }
    var isTimelineExportDialogOpen by remember { mutableStateOf(false) }
    var isAiAssistantOpen by remember { mutableStateOf(false) }

    var isIndexingActive by remember { mutableStateOf(false) }
    var statusMessageText by remember { mutableStateOf("Ready — No log file loaded.") }

    var isSearchBarVisible by remember { mutableStateOf(false) }
    var searchFocusTrigger by remember { mutableStateOf(0) }
    var searchErrorMessage by remember { mutableStateOf<String?>(null) }
    var isGoToLineDialogVisible by remember { mutableStateOf(false) }
    var isAboutDialogVisible by remember { mutableStateOf(false) }
    var isShortcutsDialogVisible by remember { mutableStateOf(false) }
    var isExportDialogVisible by remember { mutableStateOf(false) }
    var isCommandPaletteVisible by remember { mutableStateOf(false) }
    var commandPaletteInitialQuery by remember { mutableStateOf("") }
    var lastShiftPressTimeMs by remember { mutableStateOf(0L) }

    val currentTab: WorkspaceTab? = openTabs.getOrNull(activeTabIndex)
    val activeSession: LogSession? = currentTab?.session

    val toolRegistry = remember {
        umbrix.mcp.tools.ForensicToolRegistry {
            openTabs.getOrNull(activeTabIndex)?.session?.let { scala.Option.apply(it) } ?: scala.Option.empty()
        }
    }
    val aiCoordinator = remember { umbrix.ui.ai.AiAssistantCoordinator(toolRegistry) }

    val currentSettings = SettingsManager.currentSettings

    // Start embedded MCP HTTP Server if enabled in workstation settings
    DisposableEffect(currentSettings.isMcpServerEnabled, currentSettings.mcpServerPort) {
        val server = if (currentSettings.isMcpServerEnabled) {
            try {
                val s = umbrix.mcp.server.McpHttpServer(currentSettings.mcpServerPort, toolRegistry)
                s.start()
                s
            } catch (e: Exception) {
                null
            }
        } else null

        onDispose {
            server?.close()
        }
    }

    fun loadLogFile(selectedPath: String, asNewTab: Boolean = true) {
        val targetPath = selectedPath.trim()
        if (targetPath.isBlank()) return

        val targetFile = File(targetPath)
        if (!targetFile.exists() || !targetFile.isFile) {
            statusMessageText = "Error: File does not exist: $targetPath"
            ToastManager.error("File does not exist: ${targetFile.name}")
            return
        }

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
                        oldTab.session?.close()
                    }
                }

                val allOpenFiles = openTabs.filter { !it.isScratchpad && it.filePath.isNotBlank() && !it.filePath.startsWith("scratch://") }.map { it.filePath }
                SettingsManager.updateSettings(
                    SettingsManager.currentSettings.copy(lastOpenedFiles = allOpenFiles).withRecentFile(targetFile.absolutePath)
                )
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
                    tabToClose.session?.close()
                } catch (e: Throwable) {
                    if (ForensicErrorReportGenerator.isFatal(e)) throw e
                    WorkstationErrorHandler.reportException(e)
                }
            }
            val remainingFiles = openTabs.filter { !it.isScratchpad && it.filePath.isNotBlank() && !it.filePath.startsWith("scratch://") }.map { it.filePath }
            val activeScratchpad = openTabs.getOrNull(activeTabIndex)?.scratchpadSession?.id
            SettingsManager.updateSettings(
                SettingsManager.currentSettings.copy(lastOpenedFiles = remainingFiles, lastActiveScratchpadId = activeScratchpad)
            )

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

    fun openOrCreateTabForScratchpad(session: umbrix.ui.scratchpad.ScratchpadSession) {
        val existingTabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
        if (existingTabIndex >= 0) {
            activeTabIndex = existingTabIndex
            ToastManager.info("Switched to ${session.title}")
        } else {
            val newTab = WorkspaceTab(
                session = null,
                filePath = session.diskPath ?: "scratch://${session.title}",
                scratchpadSession = session
            )
            openTabs.add(newTab)
            activeTabIndex = openTabs.size - 1
            ToastManager.info("Opened ${session.title}")
        }
    }

    fun triggerSearch(queryText: String, immediate: Boolean = false) {
        val tab = currentTab ?: return
        val session = tab.session ?: return
        tab.searchQuery = queryText
        session.abortSearch()
        tab.searchJob?.cancel()

        if (queryText.isBlank()) {
            tab.searchResult = null
            tab.activeSearchQuery = ""
            tab.isSearching = false
            tab.isFilteredView = false
            tab.selectedMatchColumn = -1
            searchErrorMessage = null
            session.trimWorkingSet()
            return
        }

        // Clear stale search result immediately so UI and highlights don't show old cached data
        tab.searchResult = null
        tab.selectedMatchColumn = -1
        tab.isSearching = true
        searchErrorMessage = null

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
                                                tab.selectedLineIndex = 0L
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
                                searchErrorMessage = parseError.message()
                                tab.searchResult = null
                                tab.activeSearchQuery = ""
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
                                            tab.selectedLineIndex = 0L
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
                searchErrorMessage = "Search failed: ${e.message}"
            } finally {
                if (tab.searchQuery == queryText) {
                    tab.isSearching = false
                }
                session.trimWorkingSet()
            }
        }
    }

    fun navigateSearch(forward: Boolean) {
        try {
            val tab = currentTab ?: return
            val session = tab.session ?: return
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
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            WorkstationErrorHandler.reportException(e)
        }
    }

    fun navigateAnomaly(forward: Boolean) {
        try {
            val tab = currentTab ?: return
            val session = tab.session ?: return
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
        val session = tab.session ?: return
        val defaultName = "excerpt_${File(tab.filePath).nameWithoutExtension}.log"
        val destinationPath = showNativeSaveFileDialog(defaultName) ?: return

        coroutineScope.launch {
            try {
                statusMessageText = "Exporting sub-file to $destinationPath..."
                val sliceResult = withContext(Dispatchers.IO) {
                    val targetFile = File(destinationPath)
                    if (tab.isFilteredView && tab.searchResult != null) {
                        session.exportFilteredLines(tab.searchResult!!.matchingLines(), targetFile.toPath())
                    } else {
                        val endLine = (tab.topVisibleLine + 1000L).coerceAtMost((session.totalLines() as Number).toLong() - 1L)
                        session.exportContiguousRange(tab.topVisibleLine, endLine, targetFile.toPath())
                    }
                }

                statusMessageText = "Exported ${sliceResult.linesExported()} lines (${formatByteSize(sliceResult.bytesWritten())}) in ${sliceResult.durationNanos() / 1_000_000L} ms. Opening in new tab..."
                ToastManager.success("Exported sub-file: ${File(destinationPath).name} (${formatByteSize(sliceResult.bytesWritten())})")
                // Automatically open as a new tab
                loadLogFile(destinationPath, asNewTab = true)
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val session = tab.session
                val context = if (session != null) {
                    ForensicReportContext.fromSession(
                        tab.filePath,
                        session.fileSizeBytes(),
                        (session.totalLines() as Number).toLong(),
                        tab.topVisibleLine
                    )
                } else ForensicReportContext.empty()
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
        val session = tab?.session
        val context = if (tab != null && session != null) {
            ForensicReportContext.fromSession(tab.filePath, session.fileSizeBytes(), (session.totalLines() as Number).toLong(), tab.topVisibleLine)
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
            val tab = openTabs.getOrNull(activeTabIndex)
            val session = tab?.session
            val updatedSettings = MenuSettingsReducer.reduce(action, SettingsManager.currentSettings)
            if (updatedSettings != null) {
                SettingsManager.updateSettings(updatedSettings)
            }
            when (action) {
                is MenuAction.OpenFile -> openFileViaNativeDialog()
                is MenuAction.NewScratchpad -> {
                    val s = scratchpadManager.createSession()
                    val newTab = WorkspaceTab(
                        session = null,
                        filePath = "scratch://${s.title}",
                        scratchpadSession = s
                    )
                    openTabs.add(newTab)
                    activeTabIndex = openTabs.size - 1
                    statusMessageText = "Opened new scratchpad ${s.title}"
                    ToastManager.info("Opened new scratchpad ${s.title}")
                }
                is MenuAction.OpenScratchpadManager -> {
                    isScratchpadManagerDialogOpen = true
                }
                is MenuAction.OpenRecentFile -> loadLogFile(action.path, asNewTab = true)
                is MenuAction.ClearRecentFiles -> statusMessageText = "Cleared recent files."
                is MenuAction.ExportSubFile -> {
                    if (session != null && tab != null) {
                        isExportDialogVisible = true
                    } else {
                        ToastManager.warn("Open a log file to export sub-files.")
                        statusMessageText = "Open a log file to export sub-files."
                    }
                }
                is MenuAction.CommandPalette -> {
                    isCommandPaletteVisible = true
                    commandPaletteInitialQuery = ""
                }
                is MenuAction.Find -> {
                    isSearchBarVisible = true
                    searchFocusTrigger++
                }
                is MenuAction.GoToLineTimestamp -> {
                    if (session != null && tab != null) {
                        isGoToLineDialogVisible = true
                    } else {
                        ToastManager.warn("Open a log file to use Go To Line / Timestamp.")
                        statusMessageText = "Open a log file to use Go To Line / Timestamp."
                    }
                }
                is MenuAction.ToggleTimelineDrawer -> {
                    isTimelineDrawerOpen = !isTimelineDrawerOpen
                }
                is MenuAction.ManageBookmarks -> {
                    if (tab != null) {
                        isBookmarkDialogOpen = true
                    } else {
                        ToastManager.warn("Open a log file or scratchpad to view bookmarks.")
                        statusMessageText = "Open a log file to view bookmarks."
                    }
                }
                is MenuAction.ToggleBookmarkFilter -> {
                    if (tab != null) {
                        if (tab.bookmarkManager.bookmarks.isEmpty()) {
                            ToastManager.info("No bookmarks in ${tab.title} to filter")
                        } else {
                            tab.isBookmarkFilterActive = !tab.isBookmarkFilterActive
                            val msg = if (tab.isBookmarkFilterActive) {
                                "Filtered view to ${tab.bookmarkManager.bookmarks.size} bookmarks in ${tab.title}"
                            } else {
                                "Restored full view in ${tab.title}"
                            }
                            statusMessageText = msg
                            ToastManager.info(msg)
                        }
                    } else {
                        ToastManager.warn("Open a log file to use bookmark filtering.")
                    }
                }
                is MenuAction.TemporalRange -> {
                    if (tab != null && session != null) {
                        if (!session.isTemporalDataAvailable) {
                            ToastManager.info("No temporal timestamps detected in ${tab.title}")
                        }
                        tab.isTimeScrubberOpen = !tab.isTimeScrubberOpen
                    } else {
                        ToastManager.warn("Open a log file to use the temporal range scrubber.")
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

    DisposableEffect(isSearchBarVisible, isGoToLineDialogVisible, isSettingsDialogVisible, isAboutDialogVisible, isShortcutsDialogVisible, isExportDialogVisible, isCommandPaletteVisible, currentTab, activeTabIndex) {
        WorkstationKeyDispatcher.onGlobalKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val isCtrlOrCmd = keyEvent.isCtrlPressed || keyEvent.isMetaPressed

                // Track Double Shift for IntelliJ Search Everywhere trigger
                val isDoubleShift = if ((keyEvent.key == Key.ShiftLeft || keyEvent.key == Key.ShiftRight) && !isCtrlOrCmd && !keyEvent.isAltPressed) {
                    if (SettingsManager.currentSettings.enableDoubleShiftTrigger) {
                        val now = System.currentTimeMillis()
                        if (now - lastShiftPressTimeMs in 1..400L) {
                            lastShiftPressTimeMs = 0L
                            true
                        } else {
                            lastShiftPressTimeMs = now
                            false
                        }
                    } else false
                } else {
                    if (keyEvent.key != Key.ShiftLeft && keyEvent.key != Key.ShiftRight) {
                        lastShiftPressTimeMs = 0L
                    }
                    false
                }

                if (isDoubleShift) {
                    isCommandPaletteVisible = true
                    commandPaletteInitialQuery = ""
                    true
                } else {
                    when {
                        // Command Palette: VS Code Ctrl+Shift+P / Cmd+Shift+P (Actions prompt)
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.P -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ">"
                            true
                        }
                        // Command Palette: VS Code Ctrl+P / Cmd+P (Quick Open Files / Omni)
                        isCtrlOrCmd && !keyEvent.isShiftPressed && keyEvent.key == Key.P -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ""
                            true
                        }
                        // Command Palette: IntelliJ Ctrl+Shift+A / Cmd+Shift+A (Find Action)
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.A -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ">"
                            true
                        }
                        // Command Palette: Vim Normal Mode ':' trigger
                        !isCtrlOrCmd && !keyEvent.isAltPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Semicolon &&
                            SettingsManager.currentSettings.enableVimColonTrigger &&
                            !isSearchBarVisible && !isCommandPaletteVisible && !isSettingsDialogVisible && !isGoToLineDialogVisible && !isAboutDialogVisible && !isShortcutsDialogVisible && !isExportDialogVisible -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ":"
                            true
                        }
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
                    isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.B -> {
                        handleMenuAction(MenuAction.ToggleBookmarkFilter)
                        true
                    }
                    isCtrlOrCmd && !keyEvent.isShiftPressed && !keyEvent.isAltPressed && keyEvent.key == Key.N -> {
                        handleMenuAction(MenuAction.NewScratchpad)
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
                        val session = activeSession
                        if (tab != null && session != null) {
                            if (tab.selectionState.hasSelection()) {
                                try {
                                    val filteredView = if (tab.isFilteredView && tab.searchResult != null) {
                                        session.createFilteredView(tab.searchResult!!.matchingLines())
                                    } else null
                                    val temporalStart = if (filteredView == null && tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                                    val virtualLineCount = when {
                                        filteredView != null -> (filteredView.totalFilteredLines() as Number).toLong()
                                        tab.isTemporalFocusActive -> (tab.temporalFocusEndLine - temporalStart + 1L).coerceAtLeast(0L)
                                        else -> (session.totalLines() as Number).toLong()
                                    }
                                    val selectedText = tab.selectionState.extractSelectedText(virtualLineCount, { virtualRow ->
                                        val physicalLine = filteredView?.physicalLineForVirtualIndex(virtualRow) ?: temporalStart + virtualRow
                                        session.getLineText(physicalLine, ViewportState.MAX_SHAPED_CHARS)
                                    })
                                    if (selectedText.isNotEmpty()) {
                                        val selection = StringSelection(selectedText)
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                        statusMessageText = "Copied ${formatNumberWithCommas(selectedText.length.toLong())} characters to clipboard."
                                        ToastManager.success("Copied ${formatNumberWithCommas(selectedText.length.toLong())} characters to clipboard.")
                                    }
                                } catch (e: SelectionTooLargeException) {
                                    statusMessageText = "Selection too large to copy (limit: 1 MiB or 8,192 rows)."
                                    ToastManager.warn("Selection too large to copy (limit: 1 MiB or 8,192 rows).")
                                } catch (e: Exception) {
                                    statusMessageText = "Could not copy the selected log text."
                                    ToastManager.error("Could not copy the selected log text.")
                                }
                                true
                            } else {
                                val selectedLine = tab.selectedLineIndex
                                if (selectedLine != null && selectedLine >= 0L) {
                                    val lineText = try {
                                        session.getLineText(selectedLine, ViewportState.MAX_SHAPED_CHARS)
                                    } catch (e: Exception) { "" }

                                    if (lineText.isNotEmpty()) {
                                        try {
                                            val selection = StringSelection(lineText)
                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                            statusMessageText = "Copied line ${formatNumberWithCommas(selectedLine + 1L)} to clipboard."
                                            ToastManager.success("Copied line ${formatNumberWithCommas(selectedLine + 1L)} to clipboard.")
                                        } catch (_: Throwable) {}
                                    }
                                    true
                                } else false
                            }
                        } else false
                    }
                    isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.T -> {
                        isTimelineDrawerOpen = !isTimelineDrawerOpen
                        true
                    }
                    isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.I -> {
                        isAiAssistantOpen = !isAiAssistantOpen
                        true
                    }
                    isCtrlOrCmd && keyEvent.key == Key.N -> {
                        val s = scratchpadManager.createSession()
                        val newTab = WorkspaceTab(
                            session = null,
                            filePath = "scratch://${s.title}",
                            scratchpadSession = s
                        )
                        openTabs.add(newTab)
                        activeTabIndex = openTabs.size - 1
                        ToastManager.info("Created ${s.title}")
                        true
                    }
                    isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.S -> {
                        isScratchpadManagerDialogOpen = true
                        true
                    }
                    isCtrlOrCmd && keyEvent.key == Key.B -> {
                        val tab = currentTab
                        val session = activeSession
                        if (tab != null && session != null) {
                            val line = tab.selectedLineIndex ?: tab.topVisibleLine
                            val text = try { session.getLineText(line, 4096) } catch (e: Exception) { "" }
                            val ts = if (session.isTemporalDataAvailable) {
                                try {
                                    val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                                    (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                                } catch (e: Exception) { 0L }
                            } else 0L
                            val added = tab.bookmarkManager.toggle(line, ts, text)
                            if (added) ToastManager.info("Bookmarked line ${line + 1}")
                            else ToastManager.info("Removed bookmark on line ${line + 1}")
                            true
                        } else false
                    }
                    keyEvent.key == Key.F2 -> {
                        val tab = currentTab
                        if (tab != null) {
                            val target = if (keyEvent.isShiftPressed) {
                                tab.bookmarkManager.previousBookmark(tab.selectedLineIndex ?: tab.topVisibleLine)
                            } else {
                                tab.bookmarkManager.nextBookmark(tab.selectedLineIndex ?: tab.topVisibleLine)
                            }
                            if (target != null) {
                                tab.selectedLineIndex = target
                                tab.topVisibleLine = (target - 10).coerceAtLeast(0L)
                                ToastManager.info("Jumped to bookmark on line ${target + 1}")
                            } else {
                                ToastManager.info("No bookmarks in this document")
                            }
                            true
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
                        if (isCommandPaletteVisible) {
                            isCommandPaletteVisible = false
                            commandPaletteInitialQuery = ""
                            true
                        } else if (isSettingsDialogVisible) {
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
            }
        } else false
        }
        onDispose {
            WorkstationKeyDispatcher.onGlobalKeyEvent = null
        }
    }

    LaunchedEffect(currentTab, currentTab?.title, currentTab?.session?.totalLines()) {
        onActiveDocTitleChange(currentTab?.title)
    }

    var isInitialSessionRestored by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 1. Asynchronously prune 7+ day old disk index caches on startup
        withContext(Dispatchers.IO) {
            try {
                umbrix.index.PersistentIndexCache.cleanStaleCaches(7L * 24L * 3600L * 1000L)
            } catch (_: Throwable) {}
        }

        // 2. Open file or restore previous workspace session
        if (!initialFilePath.isNullOrBlank()) {
            loadLogFile(initialFilePath, asNewTab = true)
        } else if (SettingsManager.currentSettings.restoreSessionOnStartup) {
            val filesToRestore = SettingsManager.currentSettings.lastOpenedFiles.filter { File(it).exists() }
            for (f in filesToRestore) {
                loadLogFile(f, asNewTab = true)
            }
            val targetScratchId = SettingsManager.currentSettings.lastActiveScratchpadId
            if (targetScratchId != null) {
                scratchpadManager.getSession(targetScratchId)?.let { s ->
                    openOrCreateTabForScratchpad(s)
                }
            }
        }
        delay(250)
        isInitialSessionRestored = true
    }

    LaunchedEffect(openTabs.size, activeTabIndex, openTabs.map { it.filePath }, isInitialSessionRestored) {
        if (isInitialSessionRestored && SettingsManager.currentSettings.restoreSessionOnStartup) {
            val activeFiles = openTabs.filter { !it.isScratchpad && it.filePath.isNotBlank() && !it.filePath.startsWith("scratch://") }.map { it.filePath }
            val activeScratchpadId = openTabs.getOrNull(activeTabIndex)?.scratchpadSession?.id
            val updated = SettingsManager.currentSettings.copy(
                lastOpenedFiles = activeFiles,
                lastActiveScratchpadId = activeScratchpadId
            )
            if (updated != SettingsManager.currentSettings) {
                SettingsManager.updateSettings(updated)
            }
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
                val s = scratchpadManager.createSession()
                openOrCreateTabForScratchpad(s)
            },
            availableScratchpads = scratchpadManager.sessions,
            onOpenScratchpadSession = { s -> openOrCreateTabForScratchpad(s) },
            onRenameScratchpad = { idx -> renamingScratchpadTabIndex = idx },
            onOpenFile = { openFileViaNativeDialog() },
            onOpenScratchpadManager = { isScratchpadManagerDialogOpen = true },
            onCloseOtherTabs = { keepIndex ->
                if (keepIndex in openTabs.indices) {
                    val keepTab = openTabs[keepIndex]
                    val toClose = openTabs.filterIndexed { idx, _ -> idx != keepIndex }
                    openTabs.clear()
                    openTabs.add(keepTab)
                    activeTabIndex = 0
                    coroutineScope.launch(Dispatchers.IO) {
                        toClose.forEach { it.session?.close() }
                    }
                }
            },
            onCloseAllTabs = {
                while (openTabs.isNotEmpty()) {
                    closeTab(0)
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
                isSearching = currentTab?.isSearching ?: false,
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
                isBookmarkFilterActive = currentTab?.isBookmarkFilterActive ?: false,
                bookmarkCount = currentTab?.bookmarkManager?.bookmarks?.size ?: 0,
                onToggleBookmarkFilter = { handleMenuAction(MenuAction.ToggleBookmarkFilter) },
                onToggleFilterView = {
                    currentTab?.let { tab ->
                        val searchResult = tab.searchResult
                        val session = tab.session
                        if (searchResult != null && !searchResult.isEmpty && session != null) {
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
                    if (tab != null && (tab.searchResult == null || tab.isSearching) && tab.searchQuery.isNotBlank()) {
                        triggerSearch(tab.searchQuery, immediate = true)
                    } else {
                        navigateSearch(forward = true)
                    }
                },
                onNavigatePrev = {
                    val tab = currentTab
                    if (tab != null && (tab.searchResult == null || tab.isSearching) && tab.searchQuery.isNotBlank()) {
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

        // --- Center Log Viewport Canvas and AI Chat Drawer ---
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (openTabs.isEmpty() || currentTab == null) {
                    WelcomeScreen(
                        onOpenFileClicked = {
                            val chosenPath = showNativeOpenFileDialog()
                            if (chosenPath != null) {
                                loadLogFile(chosenPath, asNewTab = true)
                            }
                        },
                        onSelectRecentFile = { recentPath ->
                            loadLogFile(recentPath, asNewTab = true)
                        },
                        onClearRecentFiles = {
                            SettingsManager.clearRecentFiles()
                            ToastManager.info("Recent file history cleared.")
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (currentTab.isScratchpad) {
                    ScratchpadEditorCanvas(
                        session = currentTab.scratchpadSession!!,
                        onSaveRequested = {
                            ToastManager.success("Saved ${currentTab.scratchpadSession!!.title}")
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LogViewportCanvas(
                        tab = currentTab,
                        modifier = Modifier.fillMaxSize(),
                onFilterByText = { text ->
                    currentTab?.isFilteredView = true
                    isSearchBarVisible = true
                    triggerSearch(text, immediate = true)
                },
                onSearchText = { text ->
                    isSearchBarVisible = true
                    triggerSearch(text, immediate = true)
                },
                onSeekInScrubber = { line ->
                    if (activeSession != null) {
                        currentTab?.selectedLineIndex = line
                        isGoToLineDialogVisible = true
                    }
                },
                onExportStartingHere = { line ->
                    if (activeSession != null) {
                        currentTab?.selectedLineIndex = line
                        isExportDialogVisible = true
                    }
                },
                onAddToTimeline = { line, text ->
                    val session = activeSession
                    val ts = if (session != null && session.isTemporalDataAvailable) {
                        try {
                            val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                            (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                        } catch (e: Exception) { 0L }
                    } else 0L
                    timelineManager.addEvent(
                        sourceDocumentPath = currentTab.filePath,
                        sourceDocumentTitle = File(currentTab.filePath).name,
                        lineIndex = line,
                        timestampMicros = ts,
                        lineContent = text
                    )
                    ToastManager.success("Pinned Line ${line + 1} to Incident Timeline")
                    isTimelineDrawerOpen = true
                },
                onAppendToScratchpad = { line, text ->
                    val session = activeSession
                    val ts = if (session != null && session.isTemporalDataAvailable) {
                        try {
                            val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                            (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                        } catch (e: Exception) { 0L }
                    } else 0L
                    val scratchSession = scratchpadManager.appendToActiveOrNew(
                        sourceTitle = File(currentTab.filePath).name,
                        lineIndex = line,
                        timestampMicros = ts,
                        snippet = text
                    )
                    ToastManager.info("Appended to ${scratchSession.title}")
                },
                onNewScratchpadFromSelection = { text ->
                    val s = scratchpadManager.createSession(text)
                    val newTab = WorkspaceTab(
                        session = null,
                        filePath = "scratch://${s.title}",
                        scratchpadSession = s
                    )
                    openTabs.add(newTab)
                    activeTabIndex = openTabs.size - 1
                    ToastManager.info("Opened new scratchpad ${s.title}")
                },
                onOpenFile = { openFileViaNativeDialog() },
                onNewScratchpad = { handleMenuAction(MenuAction.NewScratchpad) },
                onToggleBookmarkFilter = { handleMenuAction(MenuAction.ToggleBookmarkFilter) }
            )
        }
            }

            // AI Forensic Assistant Drawer (Ctrl+Shift+I)
            AiChatDrawer(
                isOpen = isAiAssistantOpen,
                onClose = { isAiAssistantOpen = false },
                coordinator = aiCoordinator,
                session = activeSession,
                settings = currentSettings,
                onJumpToLine = { line: Long ->
                    currentTab?.selectedLineIndex = line
                    currentTab?.topVisibleLine = (line - 10).coerceAtLeast(0L)
                    ToastManager.info("Jumped to Line #$line")
                }
            )
        }

        // --- Incident Timeline Drawer (Ctrl+Shift+T) ---
        if (isTimelineDrawerOpen) {
            Divider(Orientation.Horizontal)
            InvestigationTimelineDrawer(
                timelineManager = timelineManager,
                onJumpToEvent = { event ->
                    val targetTabIdx = openTabs.indexOfFirst { it.filePath == event.sourceDocumentPath }
                    if (targetTabIdx >= 0) {
                        activeTabIndex = targetTabIdx
                        openTabs[targetTabIdx].selectedLineIndex = event.lineIndex
                        openTabs[targetTabIdx].topVisibleLine = (event.lineIndex - 10).coerceAtLeast(0L)
                    } else {
                        loadLogFile(event.sourceDocumentPath, asNewTab = true)
                    }
                },
                onOpenExportDialog = { isTimelineExportDialogOpen = true },
                onCloseDrawer = { isTimelineDrawerOpen = false }
            )
        }

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
            documentTitle = currentTab?.title,
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
    if (isExportDialogVisible && activeSession != null && currentTab != null) {
        ExportSubFileDialog(
            session = activeSession,
            currentLine = currentTab.selectedLineIndex ?: currentTab.topVisibleLine,
            sourceFilePath = currentTab.filePath,
            onExportRequested = { startLine, endLine, destinationFile, _ ->
                coroutineScope.launch {
                    try {
                        statusMessageText = "Exporting sub-file to ${destinationFile.absolutePath}..."
                        val sliceResult = withContext(Dispatchers.IO) {
                            activeSession.exportContiguousRange(startLine, endLine, destinationFile.toPath())
                        }
                        statusMessageText = "Exported ${sliceResult.linesExported()} lines (${formatByteSize(sliceResult.bytesWritten())}) in ${sliceResult.durationNanos() / 1_000_000L} ms. Opening in new tab..."
                        loadLogFile(destinationFile.absolutePath, asNewTab = true)
                    } catch (e: Throwable) {
                        if (ForensicErrorReportGenerator.isFatal(e)) throw e
                        val context = ForensicReportContext.fromSession(
                            currentTab.filePath,
                            activeSession.fileSizeBytes(),
                            (activeSession.totalLines() as Number).toLong(),
                            currentTab.topVisibleLine
                        )
                        WorkstationErrorHandler.reportException(e, context)
                    }
                }
            },
            onDismiss = { isExportDialogVisible = false }
        )
    }

    // --- Universal Multi-Persona Command Palette Modal Dialog ---
    if (isCommandPaletteVisible) {
        CommandPaletteDialog(
            isVisible = isCommandPaletteVisible,
            initialQuery = commandPaletteInitialQuery,
            executionContext = CommandExecutionContext(
                onOpenFile = { openFileViaNativeDialog() },
                onOpenRecentFile = { path -> loadLogFile(path, asNewTab = true) },
                onExportSubFile = {
                    if (activeSession != null) {
                        isExportDialogVisible = true
                    } else {
                        statusMessageText = "Open a log file to export sub-files."
                    }
                },
                onGoToLine = {
                    if (activeSession != null) {
                        isGoToLineDialogVisible = true
                    } else {
                        statusMessageText = "Open a log file to use Go To Line."
                    }
                },
                onJumpToDirectLine = { line ->
                    currentTab?.let { tab ->
                        tab.selectedLineIndex = line
                        tab.topVisibleLine = (line - 10).coerceAtLeast(0L)
                    }
                },
                onGoToTimestamp = {
                    if (activeSession != null) {
                        isGoToLineDialogVisible = true
                    } else {
                        statusMessageText = "Open a log file to use Go To Timestamp."
                    }
                },
                onOpenSearch = {
                    isSearchBarVisible = true
                    searchFocusTrigger++
                },
                onClearSearch = {
                    isSearchBarVisible = false
                    triggerSearch("")
                },
                onNavigateSearch = { forward -> navigateSearch(forward) },
                onNavigateAnomaly = { forward -> navigateAnomaly(forward) },
                onOpenSettings = { onSetSettingsDialogVisible(true) },
                onOpenShortcuts = { isShortcutsDialogVisible = true },
                onOpenAbout = { isAboutDialogVisible = true },
                onCaptureDiagnosticReport = { generateDiagnosticReport() },
                onCloseCurrentTab = {
                    if (activeTabIndex in openTabs.indices) {
                        closeTab(activeTabIndex)
                    }
                },
                onCloseAllTabs = {
                    while (openTabs.isNotEmpty()) {
                        closeTab(0)
                    }
                },
                onNextTab = {
                    if (openTabs.isNotEmpty()) {
                        activeTabIndex = (activeTabIndex + 1) % openTabs.size
                    }
                },
                onPrevTab = {
                    if (openTabs.isNotEmpty()) {
                        activeTabIndex = if (activeTabIndex - 1 < 0) openTabs.size - 1 else activeTabIndex - 1
                    }
                },
                onSwitchToTab = { index ->
                    if (index in openTabs.indices) {
                        activeTabIndex = index
                    }
                },
                onZoomIn = {
                    val current = SettingsManager.currentSettings
                    val updatedPt = (current.editorFontSizePt + 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
                    SettingsManager.updateSettings(current.copy(editorFontSizePt = updatedPt))
                },
                onZoomOut = {
                    val current = SettingsManager.currentSettings
                    val updatedPt = (current.editorFontSizePt - 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
                    SettingsManager.updateSettings(current.copy(editorFontSizePt = updatedPt))
                },
                onResetZoom = {
                    val current = SettingsManager.currentSettings
                    SettingsManager.updateSettings(current.copy(editorFontSizePt = FontSpec.DEFAULT_FONT_SIZE))
                },
                openTabTitles = openTabs.map { File(it.filePath).name },
                activeTabIndex = activeTabIndex,
                onToggleTimelineDrawer = { isTimelineDrawerOpen = !isTimelineDrawerOpen },
                onSortTimelineChronologically = {
                    timelineManager.sortChronologically()
                    ToastManager.success("Timeline sorted chronologically")
                },
                onExportTimelineReport = { isTimelineExportDialogOpen = true },
                onToggleBookmark = {
                    val tab = currentTab
                    val session = activeSession
                    if (tab != null && session != null) {
                        val line = tab.selectedLineIndex ?: tab.topVisibleLine
                        val text = try { session.getLineText(line, 4096) } catch (e: Exception) { "" }
                        val ts = if (session.isTemporalDataAvailable) {
                            try {
                                val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                                (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                            } catch (e: Exception) { 0L }
                        } else 0L
                        val added = tab.bookmarkManager.toggle(line, ts, text)
                        if (added) ToastManager.info("Bookmarked line ${line + 1}")
                        else ToastManager.info("Removed bookmark on line ${line + 1}")
                    }
                },
                onNextBookmark = {
                    val tab = currentTab
                    val target = tab?.bookmarkManager?.nextBookmark(tab.selectedLineIndex ?: tab.topVisibleLine)
                    if (target != null) {
                        tab.selectedLineIndex = target
                        tab.topVisibleLine = (target - 10).coerceAtLeast(0L)
                        ToastManager.info("Jumped to bookmark on line ${target + 1}")
                    }
                },
                onPrevBookmark = {
                    val tab = currentTab
                    val target = tab?.bookmarkManager?.previousBookmark(tab.selectedLineIndex ?: tab.topVisibleLine)
                    if (target != null) {
                        tab.selectedLineIndex = target
                        tab.topVisibleLine = (target - 10).coerceAtLeast(0L)
                        ToastManager.info("Jumped to bookmark on line ${target + 1}")
                    }
                },
                onOpenBookmarkManager = { isBookmarkDialogOpen = true },
                onNewScratchpad = {
                    val s = scratchpadManager.createSession()
                    val newTab = WorkspaceTab(
                        session = null,
                        filePath = "scratch://${s.title}",
                        scratchpadSession = s
                    )
                    openTabs.add(newTab)
                    activeTabIndex = openTabs.size - 1
                    ToastManager.info("Created ${s.title}")
                },
                onOpenScratchpadManager = { isScratchpadManagerDialogOpen = true },
                onToggleBookmarkFilter = { handleMenuAction(MenuAction.ToggleBookmarkFilter) },
                onExpandAllEvents = {
                    currentTab?.expandAllEvents()
                    ToastManager.info("Expanded all multi-line events in active tab")
                },
                onCollapseAllEvents = {
                    currentTab?.collapseAllEvents()
                    ToastManager.info("Collapsed all multi-line events in active tab")
                },
                onToggleAiAssistant = {
                    isAiAssistantOpen = !isAiAssistantOpen
                }
            ),
            onDismiss = {
                isCommandPaletteVisible = false
                commandPaletteInitialQuery = ""
            }
        )
    }

    // --- Scratchpad Manager Modal Dialog ---
    if (isScratchpadManagerDialogOpen) {
        val openScratchpadIds = openTabs.mapNotNull { it.scratchpadSession?.id }.toSet()
        ScratchpadManagerDialog(
            scratchpadManager = scratchpadManager,
            openScratchpadIds = openScratchpadIds,
            onOpenScratchpad = { session ->
                val existingTabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
                if (existingTabIndex >= 0) {
                    activeTabIndex = existingTabIndex
                    ToastManager.info("Switched to ${session.title}")
                } else {
                    val newTab = WorkspaceTab(
                        session = null,
                        filePath = session.diskPath ?: "scratch://${session.title}",
                        scratchpadSession = session
                    )
                    openTabs.add(newTab)
                    activeTabIndex = openTabs.size - 1
                    ToastManager.info("Opened ${session.title}")
                }
            },
            onNewScratchpad = {
                val s = scratchpadManager.createSession()
                val newTab = WorkspaceTab(
                    session = null,
                    filePath = "scratch://${s.title}",
                    scratchpadSession = s
                )
                openTabs.add(newTab)
                activeTabIndex = openTabs.size - 1
                ToastManager.info("Created ${s.title}")
            },
            onDeleteScratchpad = { session ->
                val tabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
                if (tabIndex >= 0) {
                    closeTab(tabIndex)
                }
                scratchpadManager.deleteSession(session)
            },
            onDismiss = { isScratchpadManagerDialogOpen = false }
        )
    }

    // --- Bookmarks Manager Modal Dialog ---
    if (isBookmarkDialogOpen && currentTab != null) {
        BookmarkManagerDialog(
            bookmarkManager = currentTab.bookmarkManager,
            documentTitle = currentTab.title,
            onJumpToLine = { targetLine ->
                currentTab.selectedLineIndex = targetLine
                currentTab.topVisibleLine = (targetLine - 10).coerceAtLeast(0L)
            },
            onAddToTimeline = { bm ->
                timelineManager.addEvent(
                    sourceDocumentPath = currentTab.filePath,
                    sourceDocumentTitle = currentTab.title,
                    lineIndex = bm.lineIndex,
                    timestampMicros = bm.timestampMicros,
                    lineContent = bm.lineSnippet,
                    tag = EventTag.fromString(bm.tag.name),
                    userNote = bm.note
                )
                ToastManager.success("Added bookmark to Timeline")
                isTimelineDrawerOpen = true
            },
            onDismiss = { isBookmarkDialogOpen = false },
            isBookmarkFilterActive = currentTab.isBookmarkFilterActive,
            onToggleBookmarkFilter = { handleMenuAction(MenuAction.ToggleBookmarkFilter) }
        )
    }

    // --- Timeline Report Export Modal Dialog ---
    if (isTimelineExportDialogOpen) {
        TimelineExportDialog(
            events = timelineManager.events,
            onCopyToClipboard = { content ->
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val sel = StringSelection(content)
                clipboard.setContents(sel, sel)
                ToastManager.success("Incident Report copied to clipboard")
            },
            onDismiss = { isTimelineExportDialogOpen = false }
        )
    }

    // --- Rename Scratchpad Modal Dialog ---
    renamingScratchpadTabIndex?.let { tabIndex ->
        val tab = openTabs.getOrNull(tabIndex)
        val scratchpad = tab?.scratchpadSession
        if (scratchpad != null) {
            RenameScratchpadDialog(
                currentTitle = scratchpad.title,
                onConfirm = { newTitle ->
                    scratchpad.title = newTitle
                    scratchpadManager.renameSession(scratchpad.id, newTitle)
                    ToastManager.info("Renamed scratchpad to $newTitle")
                    renamingScratchpadTabIndex = null
                },
                onDismiss = { renamingScratchpadTabIndex = null }
            )
        } else {
            renamingScratchpadTabIndex = null
        }
    }

    // --- Floating Toast Notifications Host ---
    ToastHost(modifier = Modifier.fillMaxSize())
}
}

/**
 * Non-fatal marker throwable used to capture an operator-requested forensic snapshot (menu
 * "Diagnostic Report") through the standard [ForensicErrorReportGenerator] pipeline.
 */
private class OperatorDiagnosticSnapshot :
    RuntimeException("Operator-requested diagnostic snapshot (not an error)")
