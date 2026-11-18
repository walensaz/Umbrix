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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import umbrix.ui.ai.AiChatDrawer
import umbrix.ui.components.ForensicErrorBanner
import umbrix.ui.dialog.ActiveDialog
import umbrix.ui.dialog.FormatOpenPolicy
import umbrix.ui.docking.DetachedWindowsHost
import umbrix.ui.docking.DockDropZone
import umbrix.ui.docking.DockGhostOverlay
import umbrix.ui.docking.DockLayoutHost
import umbrix.ui.docking.DockNode
import umbrix.ui.docking.DockTreeManager
import umbrix.ui.docking.DockViewportCallbacks
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.menu.MenuAction
import umbrix.ui.scratchpad.ScratchpadManager
import umbrix.ui.search.SearchBar
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.temporal.TemporalRangeScrubber
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.timeline.InvestigationTimelineManager
import umbrix.ui.toolbar.BottomStatusBar
import umbrix.ui.util.formatNumberWithCommas
import umbrix.ui.util.showNativeOpenFileDialog
import java.io.File

/**
 * Main workstation layout coordinating multi-document tabs, hamburger actions,
 * hardware SIMD search bar, forensic error reporting, and center log viewport.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UmbrixMainScreen(
    initialFilePath: String? = null,
    initialFilePaths: List<String> = emptyList(),
    incomingFiles: Flow<List<String>> = emptyFlow(),
    droppedFilePath: String? = null,
    onClearDroppedFilePath: () -> Unit = {},
    onActiveDocTitleChange: (String?) -> Unit = {},
    menuActions: Flow<MenuAction> = emptyFlow(),
    onExitRequest: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    val openTabs = remember { mutableStateListOf<WorkspaceTab>() }
    val detachedTabs = remember { mutableStateListOf<WorkspaceTab>() }
    var activeTabIndex by remember { mutableStateOf(0) }

    var dockTree by remember { mutableStateOf<DockNode>(DockNode.Leaf()) }
    var activeLeafId by remember { mutableStateOf(dockTree.id) }

    val timelineManager = remember { InvestigationTimelineManager() }
    val scratchpadManager = remember { ScratchpadManager() }

    var activeDialog by remember { mutableStateOf<ActiveDialog>(ActiveDialog.None) }
    var isAiAssistantOpen by remember { mutableStateOf(false) }

    var isIndexingActive by remember { mutableStateOf(false) }
    var statusMessageText by remember { mutableStateOf("Ready — No log file loaded.") }

    var isSearchBarVisible by remember { mutableStateOf(false) }
    var searchFocusTrigger by remember { mutableStateOf(0) }
    var searchErrorMessage by remember { mutableStateOf<String?>(null) }

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

    val tabCoordinator = remember {
        MainScreenTabCoordinator(
            openTabs = openTabs,
            detachedTabs = detachedTabs,
            scratchpadManager = scratchpadManager,
            timelineManager = timelineManager,
            coroutineScope = coroutineScope,
            getActiveTabIndex = { activeTabIndex },
            setActiveTabIndex = { activeTabIndex = it },
            getDockTree = { dockTree },
            setDockTree = { dockTree = it },
            getActiveLeafId = { activeLeafId },
            setActiveLeafId = { activeLeafId = it },
            setStatusMessage = { statusMessageText = it },
            setIsIndexingActive = { isIndexingActive = it },
            onOpenDialog = { activeDialog = it }
        )
    }

    LaunchedEffect(tabCoordinator) {
        val desktopWorkspaceController = object : umbrix.plugin.api.PluginWorkspaceController {
            override fun activeSession(): umbrix.plugin.api.PluginSessionView? =
                tabCoordinator.currentTab()?.session?.pluginSessionView()

            override fun navigateToRecord(recordIndex: Long) {
                val tab = tabCoordinator.currentTab() ?: return
                tab.topVisibleLine = recordIndex
                tab.selectedLineIndex = recordIndex
            }

            override fun navigateToTimestampMicros(epochMicros: Long) {}

            override fun executeSearchQuery(queryText: String, filterMode: Boolean) {
                val tab = tabCoordinator.currentTab() ?: return
                tab.searchQuery = queryText
                tab.isSearching = true
            }

            override fun openScratchpadWithText(title: String, content: String) {
                tabCoordinator.createNewScratchpadTab(content)
            }

            override fun appendToActiveScratchpad(text: String) {
                val tab = tabCoordinator.currentTab()
                if (tab != null && tab.isScratchpad) {
                    val session = tab.scratchpadSession
                    if (session != null) {
                        session.content = session.content + text
                    }
                }
            }

            override fun openDockView(viewId: String, dropZone: String) {
                val factoryOpt = umbrix.core.plugin.PluginManager.instance().getDockViewFactory(viewId)
                if (factoryOpt.isDefined) {
                    val zone = when (dropZone.uppercase()) {
                        "LEFT" -> DockDropZone.LEFT
                        "RIGHT" -> DockDropZone.RIGHT
                        "TOP" -> DockDropZone.TOP
                        "BOTTOM" -> DockDropZone.BOTTOM
                        else -> DockDropZone.CENTER_TAB
                    }
                    tabCoordinator.openCustomDockView(factoryOpt.get(), zone)
                } else {
                    umbrix.ui.toast.ToastManager.warn("Dock view '$viewId' is not registered or unavailable.")
                }
            }

            override fun showToast(message: String, severity: String) {
                when (severity.uppercase()) {
                    "ERROR" -> umbrix.ui.toast.ToastManager.error(message)
                    "WARN", "WARNING" -> umbrix.ui.toast.ToastManager.warn(message)
                    "SUCCESS" -> umbrix.ui.toast.ToastManager.success(message)
                    else -> umbrix.ui.toast.ToastManager.info(message)
                }
            }

            override fun openFile(path: java.nio.file.Path) {
                tabCoordinator.loadLogFile(path.toAbsolutePath().toString(), asNewTab = true)
            }

            override fun openFileDialog(dialogTitle: String, fileExtensionFilter: String) {
                val fileDialog = java.awt.FileDialog(null as java.awt.Frame?, dialogTitle, java.awt.FileDialog.LOAD)
                if (fileExtensionFilter.isNotBlank()) {
                    fileDialog.file = fileExtensionFilter
                }
                fileDialog.isVisible = true
                val directory = fileDialog.directory
                val file = fileDialog.file
                if (directory != null && file != null) {
                    tabCoordinator.loadLogFile(java.io.File(directory, file).absolutePath, asNewTab = true)
                }
            }
        }
        umbrix.core.plugin.PluginManager.instance().setWorkspaceController(desktopWorkspaceController)
    }

    val menuCoordinator = remember(tabCoordinator) {
        MainScreenMenuCoordinator(
            tabCoordinator = tabCoordinator,
            onOpenDialog = { activeDialog = it },
            onOpenSearchBar = {
                isSearchBarVisible = true
                searchFocusTrigger++
            },
            onExitRequest = onExitRequest,
            setStatusMessage = { statusMessageText = it }
        )
    }

    val toolRegistry = remember {
        umbrix.mcp.tools.ForensicToolRegistry {
            tabCoordinator.currentTab()?.session?.let { scala.Option.apply(it) } ?: scala.Option.empty()
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

    fun triggerSearch(queryText: String, immediate: Boolean = false) {
        val tab = tabCoordinator.currentTab() ?: return
        if (tab.isScratchpad) {
            MainScreenSearchController.triggerScratchpadSearch(
                tab = tab,
                coroutineScope = coroutineScope,
                queryText = queryText,
                immediate = immediate,
                onSearchErrorMessageChange = { searchErrorMessage = it }
            )
            return
        }
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
        val tab = tabCoordinator.currentTab() ?: return
        if (tab.isScratchpad) {
            MainScreenSearchController.navigateScratchpadSearch(tab, forward)
            return
        }
        val session = tab.session ?: return
        MainScreenSearchController.navigateSearch(tab, session, forward)
    }

    val latestMenuActionHandler by rememberUpdatedState(menuCoordinator::handleMenuAction)
    LaunchedEffect(menuActions) {
        menuActions.collect { menuAction -> latestMenuActionHandler(menuAction) }
    }

    BindWorkstationKeyHandler(
        activeDialog = activeDialog,
        onActiveDialogChange = { activeDialog = it },
        tabCoordinator = tabCoordinator,
        menuCoordinator = menuCoordinator,
        isSearchBarVisible = isSearchBarVisible,
        onSetSearchBarVisible = { isSearchBarVisible = it },
        onTriggerSearchFocus = { searchFocusTrigger++ },
        onTriggerSearch = { query, immediate -> triggerSearch(query, immediate) },
        onNavigateSearch = { forward -> navigateSearch(forward) },
        isAiAssistantOpen = isAiAssistantOpen,
        onSetAiAssistantOpen = { isAiAssistantOpen = it },
        focusManager = focusManager
    )

    val currentTab = tabCoordinator.currentTab()
    val activeSession = tabCoordinator.activeSession()

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

        if (initialFilePaths.isNotEmpty()) {
            for (filePath in initialFilePaths) {
                if (filePath.isNotBlank()) {
                    tabCoordinator.loadLogFile(filePath, asNewTab = true)
                }
            }
        } else if (!initialFilePath.isNullOrBlank()) {
            tabCoordinator.loadLogFile(initialFilePath, asNewTab = true)
        } else if (SettingsManager.currentSettings.restoreSessionOnStartup) {
            val filesToRestore = SettingsManager.currentSettings.lastOpenedFiles.filter { File(it).exists() }
            for (f in filesToRestore) {
                tabCoordinator.loadLogFile(f, asNewTab = true, formatPolicy = FormatOpenPolicy.AUTO_PLAIN_IF_AMBIGUOUS)
            }
            val targetScratchId = SettingsManager.currentSettings.lastActiveScratchpadId
            if (targetScratchId != null) {
                scratchpadManager.getSession(targetScratchId)?.let { s ->
                    tabCoordinator.openOrCreateTabForScratchpad(s)
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
            tabCoordinator.loadLogFile(droppedFilePath, asNewTab = true)
            onClearDroppedFilePath()
        }
    }

    LaunchedEffect(incomingFiles) {
        incomingFiles.collect { files ->
            for (filePath in files) {
                if (filePath.isNotBlank() && filePath != "__BRING_TO_FRONT__") {
                    tabCoordinator.loadLogFile(filePath, asNewTab = true)
                }
            }
        }
    }

    val dockCallbacks = remember(tabCoordinator, menuCoordinator) {
        DockViewportCallbacks(
            onFocusLeaf = { leafId -> tabCoordinator.focusLeaf(leafId) },
            onCloseTab = { tabId -> tabCoordinator.closeTabById(tabId) },
            onCloseOtherTabs = { tabId -> tabCoordinator.closeOtherTabs(tabId) },
            onCloseAllTabs = { tabCoordinator.closeAllTabs() },
            onClosePane = { leafId -> tabCoordinator.closePane(leafId) },
            onSplitRight = { leafId -> tabCoordinator.splitActiveRight(leafId) },
            onSplitDown = { leafId -> tabCoordinator.splitActiveDown(leafId) },
            onDetachTab = { tabId -> tabCoordinator.detachTab(tabId) },
            onDropTab = { tabId, targetLeafId, dropZone, insertIndex ->
                tabCoordinator.dropTab(tabId, targetLeafId, dropZone, insertIndex)
            },
            availableScratchpads = scratchpadManager.sessions,
            onOpenScratchpadSession = { s -> tabCoordinator.openOrCreateTabForScratchpad(s) },
            onRenameScratchpad = { tabId ->
                val idx = openTabs.indexOfFirst { it.id == tabId }
                if (idx >= 0) activeDialog = ActiveDialog.RenameScratchpad(idx)
            },
            onOpenScratchpadManager = { activeDialog = ActiveDialog.ScratchpadManager },
            onOpenFile = { tabCoordinator.openFileViaNativeDialog() },
            onNewScratchpad = { tabCoordinator.createNewScratchpadTab() },
            onOpenTimeline = { tabCoordinator.openOrCreateTimelineTab() },
            timelineManager = timelineManager,
            onJumpToTimelineEvent = { event -> tabCoordinator.jumpToTimelineEvent(event) },
            onOpenTimelineExportDialog = { activeDialog = ActiveDialog.TimelineExport },
            onFilterByText = { text ->
                tabCoordinator.currentTab()?.isFilteredView = true
                isSearchBarVisible = true
                triggerSearch(text, immediate = true)
            },
            onSearchText = { text ->
                isSearchBarVisible = true
                searchFocusTrigger++
                triggerSearch(text, immediate = true)
            },
            onSeekInScrubber = { line ->
                if (tabCoordinator.activeSession() != null) {
                    tabCoordinator.currentTab()?.selectedLineIndex = line
                    activeDialog = ActiveDialog.GoToLineTimestamp
                }
            },
            onExportStartingHere = { line ->
                if (tabCoordinator.activeSession() != null) {
                    tabCoordinator.currentTab()?.selectedLineIndex = line
                    activeDialog = ActiveDialog.ExportSubFile
                }
            },
            onAddToTimeline = { line, text ->
                tabCoordinator.pinLineToTimeline(line, text)
            },
            onAppendToScratchpad = { line, text ->
                tabCoordinator.appendLineToScratchpad(line, text)
            },
            onNewScratchpadFromSelection = { text ->
                tabCoordinator.createNewScratchpadTab(text)
            },
            onToggleBookmarkFilter = { menuCoordinator.toggleBookmarkFilter(tabCoordinator.currentTab()) }
        )
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
                    onToggleBookmarkFilter = { menuCoordinator.toggleBookmarkFilter(currentTab) },
                    onToggleFilterView = { menuCoordinator.toggleSearchFilterView(currentTab) },
                    onNavigateNext = {
                        val tab = currentTab
                        if (tab != null) {
                            if (tab.isSearching || (tab.searchResult != null && !tab.searchResult!!.isEmpty)) {
                                navigateSearch(forward = true)
                            } else if (tab.searchQuery.isNotBlank()) {
                                triggerSearch(tab.searchQuery, immediate = true)
                                tab.pendingSearchNavigation = true
                            }
                        }
                    },
                    onNavigatePrev = {
                        val tab = currentTab
                        if (tab != null) {
                            if (tab.isSearching || (tab.searchResult != null && !tab.searchResult!!.isEmpty)) {
                                navigateSearch(forward = false)
                            } else if (tab.searchQuery.isNotBlank()) {
                                triggerSearch(tab.searchQuery, immediate = true)
                                tab.pendingSearchNavigation = false
                            }
                        }
                    },
                    onExportSubFile = { tabCoordinator.exportSubFile() },
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
                            val maxTop = maxOf(0L, activeSession.totalLines() - currentTab.visibleRowCount)
                            currentTab.topVisibleLine = currentPhysical.coerceIn(0L, maxTop)
                            currentTab.selectedLineIndex = currentPhysical
                        }
                        statusMessageText = "Cleared temporal focus. Showing all ${formatNumberWithCommas(activeSession.totalLines())} lines."
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
                            onOpenFileClicked = { tabCoordinator.openFileViaNativeDialog() },
                            onSelectRecentFile = { recentPath ->
                                tabCoordinator.loadLogFile(recentPath, asNewTab = true)
                            },
                            onClearRecentFiles = {
                                SettingsManager.clearRecentFiles()
                                statusMessageText = "Recent file history cleared."
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        DockLayoutHost(
                            node = dockTree,
                            openTabs = openTabs,
                            activeLeafId = activeLeafId,
                            totalLeavesCount = DockTreeManager.allLeaves(dockTree).size,
                            callbacks = dockCallbacks,
                            modifier = Modifier.fillMaxSize()
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
                    }
                )
            }

            Divider(Orientation.Horizontal)

            // --- Bottom Status Bar ---
            BottomStatusBar(
                statusMessage = statusMessageText,
                activeSession = activeSession,
                activeTab = currentTab,
                onNavigateNextAnomaly = { tabCoordinator.navigateAnomaly(forward = true) },
                onNavigatePrevAnomaly = { tabCoordinator.navigateAnomaly(forward = false) }
            )
        }

        // --- Standardized Modals & Dialogs Host ---
        MainScreenModalHost(
            activeDialog = activeDialog,
            onActiveDialogChange = { activeDialog = it },
            tabCoordinator = tabCoordinator,
            menuCoordinator = menuCoordinator,
            isSearchBarVisible = isSearchBarVisible,
            onSetSearchBarVisible = { isSearchBarVisible = it },
            onIncrementSearchFocusTrigger = { searchFocusTrigger++ },
            isAiAssistantOpen = isAiAssistantOpen,
            onSetAiAssistantOpen = { isAiAssistantOpen = it }
        )

        // --- Detached Floating Windows Host ---
        DetachedWindowsHost(
            detachedTabs = detachedTabs,
            onReDockTab = { tab -> tabCoordinator.reDockTab(tab) },
            callbacks = dockCallbacks
        )

        // --- Dock Ghost Drop Indicator Overlay (floating at window root) ---
        DockGhostOverlay(modifier = Modifier.fillMaxSize())
    }
}
