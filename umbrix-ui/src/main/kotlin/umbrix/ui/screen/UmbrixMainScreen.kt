package umbrix.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import umbrix.core.session.LogSession
import umbrix.ui.ai.AiChatDrawer
import umbrix.ui.components.ForensicErrorBanner
import umbrix.ui.docking.DetachedWindowsHost
import umbrix.ui.docking.DockDropZone
import umbrix.ui.docking.DockGhostOverlay
import umbrix.ui.docking.DockLayoutHost
import umbrix.ui.docking.DockNode
import umbrix.ui.docking.DockOrientation
import umbrix.ui.docking.DockTreeManager
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.menu.MenuAction
import umbrix.ui.menu.MenuSettingsReducer
import umbrix.ui.scratchpad.ScratchpadEditorCanvas
import umbrix.ui.scratchpad.ScratchpadManager
import umbrix.ui.search.SearchBar
import umbrix.ui.selection.SelectionTooLargeException
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.temporal.TemporalRangeScrubber
import umbrix.ui.theme.FontSpec
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.timeline.InvestigationTimelineDrawer
import umbrix.ui.timeline.InvestigationTimelineManager
import umbrix.ui.toast.ToastManager
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

    var dockTree by remember {
        mutableStateOf<DockNode>(DockNode.Leaf())
    }
    var activeLeafId by remember {
        mutableStateOf(dockTree.id)
    }
    val detachedTabs = remember { mutableStateListOf<WorkspaceTab>() }

    val activeLeaf = DockTreeManager.findLeafById(dockTree, activeLeafId) ?: DockTreeManager.allLeaves(dockTree).firstOrNull()
    val currentTab: WorkspaceTab? = openTabs.find { it.id == activeLeaf?.activeTabId } ?: openTabs.getOrNull(activeTabIndex)
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
                    dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
                } else {
                    val oldTab = openTabs[activeTabIndex]
                    openTabs[activeTabIndex] = newTab
                    dockTree = DockTreeManager.removeTab(dockTree, oldTab.id) ?: DockNode.Leaf()
                    dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
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
            dockTree = DockTreeManager.removeTab(dockTree, tabToClose.id) ?: DockNode.Leaf()
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val isSessionStillUsed = openTabs.any { it.session === tabToClose.session } ||
                            detachedTabs.any { it.session === tabToClose.session }
                    if (!isSessionStillUsed) {
                        tabToClose.session?.close()
                    }
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

    fun closeTabById(tabId: String) {
        val idx = openTabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            closeTab(idx)
        }
    }

    fun splitActiveRight(sourceLeafId: String = activeLeafId) {
        val targetLeaf = DockTreeManager.findLeafById(dockTree, sourceLeafId) ?: return
        val currentTabId = targetLeaf.activeTabId ?: return
        val currentWorkspaceTab = openTabs.find { it.id == currentTabId } ?: return

        val tabIdToPlaceInNewPane: String = if (targetLeaf.tabIds.size == 1) {
            val clonedTab = WorkspaceTab(
                session = currentWorkspaceTab.session,
                filePath = currentWorkspaceTab.filePath,
                scratchpadSession = currentWorkspaceTab.scratchpadSession,
                bookmarkManager = currentWorkspaceTab.bookmarkManager,
                initialTopVisibleLine = currentWorkspaceTab.topVisibleLine,
                initialSearchQuery = currentWorkspaceTab.searchQuery,
                initialSearchResult = currentWorkspaceTab.searchResult,
                initialIsFilteredView = currentWorkspaceTab.isFilteredView,
                initialSelectedLineIndex = currentWorkspaceTab.selectedLineIndex
            )
            openTabs.add(clonedTab)
            clonedTab.id
        } else {
            currentTabId
        }

        dockTree = DockTreeManager.splitLeaf(dockTree, sourceLeafId, tabIdToPlaceInNewPane, DockDropZone.RIGHT)
        ToastManager.info("Split pane right (Ctrl+\\)")
    }

    fun splitActiveDown(sourceLeafId: String = activeLeafId) {
        val targetLeaf = DockTreeManager.findLeafById(dockTree, sourceLeafId) ?: return
        val currentTabId = targetLeaf.activeTabId ?: return
        val currentWorkspaceTab = openTabs.find { it.id == currentTabId } ?: return

        val tabIdToPlaceInNewPane: String = if (targetLeaf.tabIds.size == 1) {
            val clonedTab = WorkspaceTab(
                session = currentWorkspaceTab.session,
                filePath = currentWorkspaceTab.filePath,
                scratchpadSession = currentWorkspaceTab.scratchpadSession,
                bookmarkManager = currentWorkspaceTab.bookmarkManager,
                initialTopVisibleLine = currentWorkspaceTab.topVisibleLine,
                initialSearchQuery = currentWorkspaceTab.searchQuery,
                initialSearchResult = currentWorkspaceTab.searchResult,
                initialIsFilteredView = currentWorkspaceTab.isFilteredView,
                initialSelectedLineIndex = currentWorkspaceTab.selectedLineIndex
            )
            openTabs.add(clonedTab)
            clonedTab.id
        } else {
            currentTabId
        }

        dockTree = DockTreeManager.splitLeaf(dockTree, sourceLeafId, tabIdToPlaceInNewPane, DockDropZone.BOTTOM)
        ToastManager.info("Split pane down (Ctrl+Alt+\\)")
    }

    fun openOrCreateTabForScratchpad(session: umbrix.ui.scratchpad.ScratchpadSession) {
        val existingTabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
        if (existingTabIndex >= 0) {
            activeTabIndex = existingTabIndex
            val existingTab = openTabs[existingTabIndex]
            val leaf = DockTreeManager.findLeafForTab(dockTree, existingTab.id)
            if (leaf != null) {
                leaf.activeTabId = existingTab.id
                activeLeafId = leaf.id
            }
            ToastManager.info("Switched to ${session.title}")
        } else {
            val newTab = WorkspaceTab(
                session = null,
                filePath = session.diskPath ?: "scratch://${session.title}",
                scratchpadSession = session
            )
            openTabs.add(newTab)
            activeTabIndex = openTabs.size - 1
            dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
            ToastManager.info("Opened ${session.title}")
        }
    }

    fun openOrCreateTimelineTab() {
        val existingTabIndex = openTabs.indexOfFirst { it.isTimelineTab }
        if (existingTabIndex >= 0) {
            activeTabIndex = existingTabIndex
            val existingTab = openTabs[existingTabIndex]
            val leaf = DockTreeManager.findLeafForTab(dockTree, existingTab.id)
            if (leaf != null) {
                leaf.activeTabId = existingTab.id
                activeLeafId = leaf.id
            }
            ToastManager.info("Switched to Incident Timeline")
        } else {
            val newTab = WorkspaceTab(
                session = null,
                filePath = "timeline://incident"
            )
            openTabs.add(newTab)
            activeTabIndex = openTabs.size - 1
            dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
            ToastManager.info("Opened Incident Timeline")
        }
    }

    fun triggerSearch(queryText: String, immediate: Boolean = false) {
        val tab = currentTab ?: return
        val session = tab.session ?: return
        MainScreenSearchController.triggerSearch(
            tab = tab,
            session = session,
            coroutineScope = coroutineScope,
            queryText = queryText,
            immediate = immediate,
            onSearchErrorMessageChange = { searchErrorMessage = it }
        )
    }

    fun navigateSearch(forward: Boolean) {
        val tab = currentTab ?: return
        val session = tab.session ?: return
        MainScreenSearchController.navigateSearch(tab, session, forward)
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
                val context = ForensicReportContext.fromSession(
                    tab.filePath,
                    session.fileSizeBytes(),
                    (session.totalLines() as Number).toLong(),
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
        val session = tab?.session
        val context = if (tab != null && session != null) {
            ForensicReportContext.fromSession(tab.filePath, session.fileSizeBytes(), (session.totalLines() as Number).toLong(), tab.topVisibleLine)
        } else {
            ForensicReportContext.empty()
        }
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
                    if (session != null) {
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
                    if (session != null) {
                        isGoToLineDialogVisible = true
                    } else {
                        ToastManager.warn("Open a log file to use Go To Line / Timestamp.")
                        statusMessageText = "Open a log file to use Go To Line / Timestamp."
                    }
                }
                is MenuAction.ToggleTimelineDrawer -> {
                    openOrCreateTimelineTab()
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
                            val willBeActive = !tab.isBookmarkFilterActive
                            if (willBeActive) {
                                val currentPhysical = tab.selectedLineIndex ?: if (tab.isTemporalFocusActive) {
                                    tab.temporalFocusStartLine + tab.topVisibleLine
                                } else {
                                    tab.topVisibleLine
                                }
                                val bView = session?.createFilteredView(tab.bookmarkManager.toBitmap())
                                if (bView != null) {
                                    val vIdx = (bView.virtualIndexForPhysicalLine(currentPhysical) as Number).toLong()
                                    val maxV = maxOf(0L, (bView.totalFilteredLines() as Number).toLong() - tab.visibleRowCount)
                                    tab.topVisibleLine = vIdx.coerceIn(0L, maxV)
                                }
                            } else {
                                val currentPhysical = tab.selectedLineIndex
                                if (currentPhysical != null && currentPhysical >= 0L) {
                                    if (tab.isTemporalFocusActive) {
                                        val rel = (currentPhysical - tab.temporalFocusStartLine).coerceIn(
                                            0L,
                                            maxOf(0L, (tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L) - tab.visibleRowCount)
                                        )
                                        tab.topVisibleLine = rel
                                    } else if (session != null) {
                                        val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                                        tab.topVisibleLine = currentPhysical.coerceIn(0L, maxTop)
                                    }
                                }
                            }
                            tab.isBookmarkFilterActive = willBeActive
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

    val latestMenuActionHandler by rememberUpdatedState(::handleMenuAction)
    LaunchedEffect(menuActions) {
        menuActions.collect { menuAction -> latestMenuActionHandler(menuAction) }
    }

    DisposableEffect(isSearchBarVisible, isGoToLineDialogVisible, isSettingsDialogVisible, isAboutDialogVisible, isShortcutsDialogVisible, isExportDialogVisible, isCommandPaletteVisible, currentTab, activeTabIndex) {
        WorkstationKeyDispatcher.onGlobalKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val isCtrlOrCmd = keyEvent.isCtrlPressed || keyEvent.isMetaPressed

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
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.P -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ">"
                            true
                        }
                        isCtrlOrCmd && !keyEvent.isShiftPressed && keyEvent.key == Key.P -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ""
                            true
                        }
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.A -> {
                            isCommandPaletteVisible = true
                            commandPaletteInitialQuery = ">"
                            true
                        }
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
                                            val physicalLine = filteredView?.physicalLineForVirtualIndex(virtualRow) ?: (temporalStart + virtualRow)
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
                            dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
                            ToastManager.info("Created ${s.title}")
                            true
                        }
                        isCtrlOrCmd && !keyEvent.isAltPressed && keyEvent.key == Key.Backslash -> {
                            splitActiveRight()
                            true
                        }
                        isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.Backslash -> {
                            splitActiveDown()
                            true
                        }
                        isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.Equals -> {
                            DockTreeManager.equalizeSplits(dockTree)
                            ToastManager.info("Equalized split panes (50/50)")
                            true
                        }
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.W -> {
                            val leaves = DockTreeManager.allLeaves(dockTree)
                            if (leaves.size > 1) {
                                val leaf = DockTreeManager.findLeafById(dockTree, activeLeafId)
                                leaf?.tabIds?.toList()?.forEach { closeTabById(it) }
                                true
                            } else false
                        }
                        keyEvent.key == Key.F6 -> {
                            val leaves = DockTreeManager.allLeaves(dockTree)
                            if (leaves.size > 1) {
                                val currIdx = leaves.indexOfFirst { it.id == activeLeafId }
                                val nextLeaf = leaves[(currIdx + 1) % leaves.size]
                                activeLeafId = nextLeaf.id
                                val idx = openTabs.indexOfFirst { it.id == nextLeaf.activeTabId }
                                if (idx >= 0) activeTabIndex = idx
                                ToastManager.info("Focused next pane")
                                true
                            } else false
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
        withContext(Dispatchers.IO) {
            try {
                umbrix.index.PersistentIndexCache.cleanStaleCaches(7L * 24L * 3600L * 1000L)
            } catch (_: Throwable) {}
        }

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
        val initialTabIds = openTabs.map { it.id }
        if (initialTabIds.isNotEmpty()) {
            dockTree = DockNode.Leaf(initialTabIds = initialTabIds, initialActiveTabId = initialTabIds.firstOrNull())
            activeLeafId = dockTree.id
        }
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
            // --- Active Forensic Error Banner ---
            WorkstationErrorHandler.activeError?.let { activeErrorReport ->
                ForensicErrorBanner(
                    report = activeErrorReport,
                    onDismiss = { WorkstationErrorHandler.activeError = null }
                )
                Divider(Orientation.Horizontal)
            }


            // --- ReDoS-Immune Search Bar (Ctrl+F) ---
            if (isSearchBarVisible) {
                Divider(Orientation.Horizontal)
                SearchBar(
                    queryText = currentTab?.searchQuery ?: "",
                    onQueryChange = { newQuery ->
                        triggerSearch(newQuery)
                    },
                    searchResult = currentTab?.searchResult,
                    errorMessage = searchErrorMessage ?: currentTab?.searchWrapIndicator,
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
                                    val currentPhysical = tab.selectedLineIndex ?: if (tab.isTemporalFocusActive) {
                                        tab.temporalFocusStartLine + tab.topVisibleLine
                                    } else {
                                        tab.topVisibleLine
                                    }
                                    val filteredView = session.createFilteredView(searchResult.matchingLines())
                                    val virtualIndex = (filteredView.virtualIndexForPhysicalLine(currentPhysical) as Number).toLong()
                                    val totalFiltered = (filteredView.totalFilteredLines() as Number).toLong()
                                    val maxVirtualTop = maxOf(0L, totalFiltered - tab.visibleRowCount)
                                    tab.topVisibleLine = virtualIndex.coerceIn(0L, maxVirtualTop)
                                    val resolvedPhysical = (filteredView.physicalLineForVirtualIndex(tab.topVisibleLine) as Number).toLong()
                                    tab.selectedLineIndex = resolvedPhysical
                                    tab.isFilteredView = true
                                } else {
                                    val filteredView = session.createFilteredView(searchResult.matchingLines())
                                    val currentPhysical = tab.selectedLineIndex ?: (filteredView.physicalLineForVirtualIndex(tab.topVisibleLine) as Number).toLong()
                                    if (tab.isTemporalFocusActive) {
                                        val relativeTop = (currentPhysical - tab.temporalFocusStartLine).coerceIn(
                                            0L,
                                            maxOf(0L, (tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L) - tab.visibleRowCount)
                                        )
                                        tab.topVisibleLine = relativeTop
                                    } else {
                                        val maxTop = maxOf(0L, (session.totalLines() as Number).toLong() - tab.visibleRowCount)
                                        tab.topVisibleLine = currentPhysical.coerceIn(0L, maxTop)
                                    }
                                    tab.selectedLineIndex = currentPhysical
                                    tab.isFilteredView = false
                                }
                            }
                        }
                    },
                    onNavigateNext = {
                        val tab = currentTab
                        if (tab != null) {
                            if (tab.searchResult != null && !tab.searchResult!!.isEmpty) {
                                navigateSearch(forward = true)
                            } else if (!tab.isSearching && tab.searchQuery.isNotBlank()) {
                                triggerSearch(tab.searchQuery, immediate = true)
                            }
                        }
                    },
                    onNavigatePrev = {
                        val tab = currentTab
                        if (tab != null) {
                            if (tab.searchResult != null && !tab.searchResult!!.isEmpty) {
                                navigateSearch(forward = false)
                            } else if (!tab.isSearching && tab.searchQuery.isNotBlank()) {
                                triggerSearch(tab.searchQuery, immediate = true)
                            }
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
                        val raw = currentTab.rawSearchResult ?: currentTab.searchResult
                        if (raw != null) {
                            currentTab.rawSearchResult = raw
                            currentTab.searchResult = raw.constrainToRange(startLine, endLine)
                        }
                        if (currentTab.isFilteredView) {
                            val res = currentTab.searchResult
                            if (res != null && !res.isEmpty) {
                                currentTab.topVisibleLine = 0L
                                currentTab.selectedLineIndex = res.findNextLine(startLine)
                            }
                        } else {
                            currentTab.topVisibleLine = 0L
                            currentTab.selectedLineIndex = startLine
                        }
                        val trimmedCount = maxOf(0L, endLine - startLine + 1L)
                        statusMessageText = "Trimmed view to ${formatNumberWithCommas(trimmedCount)} lines."
                    },
                    onClearTrim = {
                        val currentPhysical = currentTab.selectedLineIndex ?: (currentTab.temporalFocusStartLine + currentTab.topVisibleLine)
                        currentTab.isTemporalFocusActive = false
                        currentTab.temporalFocusStartMicros = -1L
                        currentTab.temporalFocusEndMicros = -1L
                        currentTab.temporalFocusStartLine = -1L
                        currentTab.temporalFocusEndLine = -1L
                        if (currentTab.rawSearchResult != null) {
                            currentTab.searchResult = currentTab.rawSearchResult
                        }
                        if (!currentTab.isFilteredView) {
                            val maxTop = maxOf(0L, (activeSession.totalLines() as Number).toLong() - currentTab.visibleRowCount)
                            currentTab.topVisibleLine = currentPhysical.coerceIn(0L, maxTop)
                            currentTab.selectedLineIndex = currentPhysical
                        }
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
                    if (openTabs.isEmpty() && detachedTabs.isEmpty()) {
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
                    } else {
                        DockLayoutHost(
                            node = dockTree,
                            openTabs = openTabs,
                            activeLeafId = activeLeafId,
                            totalLeavesCount = DockTreeManager.allLeaves(dockTree).size,
                            onFocusLeaf = { leafId ->
                                activeLeafId = leafId
                                val leaf = DockTreeManager.findLeafById(dockTree, leafId)
                                val tabId = leaf?.activeTabId
                                val idx = openTabs.indexOfFirst { it.id == tabId }
                                if (idx >= 0) activeTabIndex = idx
                            },
                            onCloseTab = { tabId -> closeTabById(tabId) },
                            onSplitRight = { leafId -> splitActiveRight(leafId) },
                            onSplitDown = { leafId -> splitActiveDown(leafId) },
                            onDetachTab = { tabId ->
                                openTabs.find { it.id == tabId }?.let { tab ->
                                    dockTree = DockTreeManager.removeTab(dockTree, tabId) ?: DockNode.Leaf()
                                    openTabs.remove(tab)
                                    detachedTabs.add(tab)
                                    ToastManager.info("Moved ${tab.title} to separate window")
                                }
                            },
                            onClosePane = { leafId ->
                                val leaf = DockTreeManager.findLeafById(dockTree, leafId)
                                leaf?.tabIds?.toList()?.forEach { closeTabById(it) }
                            },
                            onDropTab = { tabId, targetLeafId, dropZone, insertIndex ->
                                dockTree = if (dropZone == DockDropZone.CENTER_TAB) {
                                    DockTreeManager.moveTab(dockTree, tabId, targetLeafId, insertIndex)
                                } else {
                                    DockTreeManager.splitLeaf(dockTree, targetLeafId, tabId, dropZone)
                                }
                                val leaf = DockTreeManager.findLeafForTab(dockTree, tabId)
                                if (leaf != null) {
                                    activeLeafId = leaf.id
                                    leaf.activeTabId = tabId
                                    val idx = openTabs.indexOfFirst { it.id == tabId }
                                    if (idx >= 0) activeTabIndex = idx
                                }
                            },
                            availableScratchpads = scratchpadManager.sessions,
                            onOpenScratchpadSession = { s -> openOrCreateTabForScratchpad(s) },
                            onRenameScratchpad = { tabId ->
                                val idx = openTabs.indexOfFirst { it.id == tabId }
                                if (idx >= 0) renamingScratchpadTabIndex = idx
                            },
                            onCloseOtherTabs = { tabId ->
                                val toClose = openTabs.filter { it.id != tabId }
                                toClose.forEach { closeTabById(it.id) }
                            },
                            onCloseAllTabs = {
                                openTabs.toList().forEach { closeTabById(it.id) }
                            },
                            onOpenScratchpadManager = { isScratchpadManagerDialogOpen = true },
                            onOpenFile = { openFileViaNativeDialog() },
                            onNewScratchpad = { handleMenuAction(MenuAction.NewScratchpad) },
                            onOpenTimeline = { openOrCreateTimelineTab() },
                            timelineManager = timelineManager,
                            onJumpToTimelineEvent = { event ->
                                val targetTabIdx = openTabs.indexOfFirst { it.filePath == event.sourceDocumentPath }
                                if (targetTabIdx >= 0) {
                                    activeTabIndex = targetTabIdx
                                    val targetTab = openTabs[targetTabIdx]
                                    val leaf = DockTreeManager.findLeafForTab(dockTree, targetTab.id)
                                    if (leaf != null) {
                                        activeLeafId = leaf.id
                                        leaf.activeTabId = targetTab.id
                                    }
                                    targetTab.selectedLineIndex = event.lineIndex
                                    targetTab.topVisibleLine = (event.lineIndex - 10).coerceAtLeast(0L)
                                } else {
                                    loadLogFile(event.sourceDocumentPath, asNewTab = true)
                                }
                            },
                            onOpenTimelineExportDialog = { isTimelineExportDialogOpen = true },
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
                                val tab = currentTab ?: return@DockLayoutHost
                                val session = tab.session
                                val ts = if (session != null && session.isTemporalDataAvailable) {
                                    try {
                                        val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                                        (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                                    } catch (_: Exception) { 0L }
                                } else 0L
                                timelineManager.addEvent(
                                    sourceDocumentPath = tab.filePath,
                                    sourceDocumentTitle = File(tab.filePath).name,
                                    lineIndex = line,
                                    timestampMicros = ts,
                                    lineContent = text
                                )
                                ToastManager.success("Pinned Line ${line + 1} to Incident Timeline")
                            },
                            onAppendToScratchpad = { line, text ->
                                val tab = currentTab ?: return@DockLayoutHost
                                val session = tab.session
                                val ts = if (session != null && session.isTemporalDataAvailable) {
                                    try {
                                        val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                                        (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                                    } catch (_: Exception) { 0L }
                                } else 0L
                                val scratchSession = scratchpadManager.appendToActiveOrNew(
                                    sourceTitle = File(tab.filePath).name,
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
                                dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
                                ToastManager.info("Opened new scratchpad ${s.title}")
                            },
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

        // --- Standardized Modals & Dialogs Host ---
        MainScreenModalHost(
            activeSession = activeSession,
            currentTab = currentTab,
            openTabs = openTabs,
            activeTabIndex = activeTabIndex,
            onActiveTabIndexChange = { activeTabIndex = it },
            timelineManager = timelineManager,
            scratchpadManager = scratchpadManager,
            coroutineScope = coroutineScope,
            isSettingsDialogVisible = isSettingsDialogVisible,
            onSetSettingsDialogVisible = onSetSettingsDialogVisible,
            isGoToLineDialogVisible = isGoToLineDialogVisible,
            onSetGoToLineDialogVisible = { isGoToLineDialogVisible = it },
            isAboutDialogVisible = isAboutDialogVisible,
            onSetAboutDialogVisible = { isAboutDialogVisible = it },
            isShortcutsDialogVisible = isShortcutsDialogVisible,
            onSetShortcutsDialogVisible = { isShortcutsDialogVisible = it },
            isExportDialogVisible = isExportDialogVisible,
            onSetExportDialogVisible = { isExportDialogVisible = it },
            isCommandPaletteVisible = isCommandPaletteVisible,
            onSetCommandPaletteVisible = { isCommandPaletteVisible = it },
            commandPaletteInitialQuery = commandPaletteInitialQuery,
            onSetCommandPaletteInitialQuery = { commandPaletteInitialQuery = it },
            isScratchpadManagerDialogOpen = isScratchpadManagerDialogOpen,
            onSetScratchpadManagerDialogOpen = { isScratchpadManagerDialogOpen = it },
            isBookmarkDialogOpen = isBookmarkDialogOpen,
            onSetBookmarkDialogOpen = { isBookmarkDialogOpen = it },
            isTimelineExportDialogOpen = isTimelineExportDialogOpen,
            onSetTimelineExportDialogOpen = { isTimelineExportDialogOpen = it },
            renamingScratchpadTabIndex = renamingScratchpadTabIndex,
            onSetRenamingScratchpadTabIndex = { renamingScratchpadTabIndex = it },
            isSearchBarVisible = isSearchBarVisible,
            onSetSearchBarVisible = { isSearchBarVisible = it },
            searchFocusTrigger = searchFocusTrigger,
            onSetSearchFocusTrigger = { searchFocusTrigger = it },
            isTimelineDrawerOpen = isTimelineDrawerOpen,
            onSetTimelineDrawerOpen = { isTimelineDrawerOpen = it },
            isAiAssistantOpen = isAiAssistantOpen,
            onSetAiAssistantOpen = { isAiAssistantOpen = it },
            onStatusMessageTextChange = { statusMessageText = it },
            onOpenFileViaNativeDialog = { openFileViaNativeDialog() },
            onLoadLogFile = { path, asNewTab -> loadLogFile(path, asNewTab) },
            onCloseTab = { closeTab(it) },
            onGenerateDiagnosticReport = { generateDiagnosticReport() },
            onHandleMenuAction = { handleMenuAction(it) }
        )

        // --- Detached Floating Windows Host ---
        DetachedWindowsHost(
            detachedTabs = detachedTabs,
            onReDockTab = { tab ->
                detachedTabs.remove(tab)
                openTabs.add(tab)
                dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, tab.id)
                activeTabIndex = openTabs.size - 1
                ToastManager.info("Re-docked ${tab.title} into main window")
            },
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
                val tab = currentTab ?: return@DetachedWindowsHost
                val session = tab.session
                val ts = if (session != null && session.isTemporalDataAvailable) {
                    try {
                        val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                        (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                    } catch (_: Exception) { 0L }
                } else 0L
                timelineManager.addEvent(
                    sourceDocumentPath = tab.filePath,
                    sourceDocumentTitle = File(tab.filePath).name,
                    lineIndex = line,
                    timestampMicros = ts,
                    lineContent = text
                )
                ToastManager.success("Pinned Line ${line + 1} to Incident Timeline")
                isTimelineDrawerOpen = true
            },
            onAppendToScratchpad = { line, text ->
                val tab = currentTab ?: return@DetachedWindowsHost
                val session = tab.session
                val ts = if (session != null && session.isTemporalDataAvailable) {
                    try {
                        val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                        (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                    } catch (_: Exception) { 0L }
                } else 0L
                val scratchSession = scratchpadManager.appendToActiveOrNew(
                    sourceTitle = File(tab.filePath).name,
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
                dockTree = DockTreeManager.addTabToLeaf(dockTree, activeLeafId, newTab.id)
                ToastManager.info("Opened new scratchpad ${s.title}")
            },
            onOpenFile = { openFileViaNativeDialog() },
            onNewScratchpad = { handleMenuAction(MenuAction.NewScratchpad) }
        )

        // --- Dock Ghost Drop Indicator Overlay (floating at window root) ---
        DockGhostOverlay(modifier = Modifier.fillMaxSize())
    }
}

/**
 * Non-fatal marker throwable used to capture an operator-requested forensic snapshot (menu
 * "Diagnostic Report") through the standard [ForensicErrorReportGenerator] pipeline.
 */
private class OperatorDiagnosticSnapshot :
    RuntimeException("Operator-requested diagnostic snapshot (not an error)")
