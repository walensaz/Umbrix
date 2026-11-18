package umbrix.ui.screen

import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.session.LogSession
import umbrix.ui.dialog.ActiveDialog
import umbrix.ui.dialog.FormatOpenPolicy
import umbrix.ui.docking.DockDropZone
import umbrix.ui.docking.DockNode
import umbrix.ui.docking.DockTreeManager
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.scratchpad.ScratchpadManager
import umbrix.ui.scratchpad.ScratchpadSession
import umbrix.ui.selection.SelectionTooLargeException
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.timeline.InvestigationTimelineManager
import umbrix.ui.timeline.TimelineEvent
import umbrix.ui.toast.ToastManager
import umbrix.ui.util.formatByteSize
import umbrix.ui.util.formatNumberWithCommas
import umbrix.ui.util.showNativeOpenFileDialog
import umbrix.ui.util.showNativeSaveFileDialog
import umbrix.ui.viewport.ViewportCoordinateMapper
import umbrix.ui.viewport.ViewportState
import umbrix.ui.viewport.VirtualRowIndex
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Non-fatal marker throwable used to capture an operator-requested forensic snapshot (menu
 * "Diagnostic Report") through the standard [ForensicErrorReportGenerator] pipeline.
 */
class OperatorDiagnosticSnapshot :
    RuntimeException("Operator-requested diagnostic snapshot (not an error)")

/**
 * Encapsulates all workspace tabs, multi-dock tree topology, session lifecycles,
 * anomaly and bookmark navigation, clipboard extraction, sub-file export, and forensic snapshots.
 */
class MainScreenTabCoordinator(
    val openTabs: SnapshotStateList<WorkspaceTab>,
    val detachedTabs: SnapshotStateList<WorkspaceTab>,
    val scratchpadManager: ScratchpadManager,
    val timelineManager: InvestigationTimelineManager,
    val coroutineScope: CoroutineScope,
    private val getActiveTabIndex: () -> Int,
    private val setActiveTabIndex: (Int) -> Unit,
    private val getDockTree: () -> DockNode,
    private val setDockTree: (DockNode) -> Unit,
    private val getActiveLeafId: () -> String,
    private val setActiveLeafId: (String) -> Unit,
    private val setStatusMessage: (String) -> Unit,
    private val setIsIndexingActive: (Boolean) -> Unit,
    private val onOpenDialog: (ActiveDialog) -> Unit = {}
) {

    fun currentTab(): WorkspaceTab? {
        val tree = getDockTree()
        val leaf = DockTreeManager.findLeafById(tree, getActiveLeafId()) ?: DockTreeManager.allLeaves(tree).firstOrNull()
        return openTabs.find { it.id == leaf?.activeTabId } ?: openTabs.getOrNull(getActiveTabIndex())
    }

    fun activeSession(): LogSession? = currentTab()?.session

    fun loadLogFile(
        selectedPath: String,
        asNewTab: Boolean = true,
        formatPolicy: FormatOpenPolicy = FormatOpenPolicy.AUTO
    ) {
        val targetPath = selectedPath.trim()
        if (targetPath.isBlank()) return

        val targetFile = File(targetPath)
        if (!targetFile.exists() || !targetFile.isFile) {
            setStatusMessage("Error: File does not exist: $targetPath")
            ToastManager.error("File does not exist: ${targetFile.name}")
            return
        }

        setStatusMessage("Opening ${targetFile.name}...")

        coroutineScope.launch {
            try {
                if (formatPolicy == FormatOpenPolicy.AUTO || formatPolicy == FormatOpenPolicy.AUTO_PLAIN_IF_AMBIGUOUS) {
                    val probeOpt = withContext(Dispatchers.IO) {
                        LogSession.probeFormat(targetFile.toPath())
                    }
                    if (probeOpt.isDefined() && probeOpt.get().requiresConfirmation()) {
                        if (formatPolicy == FormatOpenPolicy.AUTO_PLAIN_IF_AMBIGUOUS) {
                            val plainSession = withContext(Dispatchers.IO) {
                                LogSession.openPlain(targetFile.toPath(), false)
                            }
                            openLoadedSession(targetFile, plainSession, asNewTab)
                            return@launch
                        } else {
                            setStatusMessage("Confirm format for ${targetFile.name}")
                            onOpenDialog(
                                ActiveDialog.ConfirmFormatOpen(
                                    filePath = targetFile.absolutePath,
                                    asNewTab = asNewTab,
                                    formatDisplayName = probeOpt.get().formatDisplayName()
                                )
                            )
                            return@launch
                        }
                    }
                }

                val newSession = withContext(Dispatchers.IO) {
                    when (formatPolicy) {
                        FormatOpenPolicy.FORCE_PLAIN_LOG -> LogSession.openPlain(targetFile.toPath(), false)
                        else -> LogSession.open(targetFile.toPath(), false)
                    }
                }
                openLoadedSession(targetFile, newSession, asNewTab)
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext(
                    scala.Option.apply(targetFile.absolutePath),
                    scala.Option.empty(),
                    scala.Option.empty(),
                    scala.Option.empty()
                )
                WorkstationErrorHandler.reportException(e, context)
                setStatusMessage("Failed to index file: ${e.message}")
                setIsIndexingActive(false)
            }
        }
    }

    private suspend fun openLoadedSession(targetFile: File, newSession: LogSession, asNewTab: Boolean) {

                val newTab = WorkspaceTab(
                    session = newSession,
                    filePath = targetFile.absolutePath,
                    initialTopVisibleLine = 0L
                )

                if (asNewTab || openTabs.isEmpty()) {
                    openTabs.add(newTab)
                    setActiveTabIndex(openTabs.size - 1)
                    setDockTree(DockTreeManager.addTabToLeaf(getDockTree(), getActiveLeafId(), newTab.id))
                } else {
                    val activeIdx = getActiveTabIndex()
                    val oldTab = openTabs[activeIdx]
                    openTabs[activeIdx] = newTab
                    var tree = DockTreeManager.removeTab(getDockTree(), oldTab.id) ?: DockNode.Leaf()
                    tree = DockTreeManager.addTabToLeaf(tree, getActiveLeafId(), newTab.id)
                    setDockTree(tree)
                    withContext(Dispatchers.IO) {
                        oldTab.session?.close()
                    }
                }

                val probeOpt = newSession.activeFormatProbeResult()
                if (probeOpt.isDefined()) {
                    val probeResult = probeOpt.get()
                    val companionId = probeResult.companionDockViewId()
                    if (companionId.isNotEmpty()) {
                        val pluginMgr = umbrix.core.plugin.PluginManager.instance()
                        val autoOpen = run {
                            val pidOpt = pluginMgr.getPluginIdForDockView(companionId)
                            if (pidOpt.isDefined) {
                                val pid = pidOpt.get()
                                val storeOpt = pluginMgr.getPluginConfigStore(pid)
                                if (storeOpt.isDefined) {
                                    storeOpt.get().getBoolean("ui.auto_open_workstation", true)
                                } else {
                                    true
                                }
                            } else {
                                true
                            }
                        }

                        if (autoOpen) {
                            val factoryOpt = pluginMgr.getDockViewFactory(companionId)
                            if (factoryOpt.isDefined()) {
                                val factory = factoryOpt.get()
                                val companionView = factory.createView(newSession.pluginSessionView())
                                if (probeResult.openMode() == umbrix.plugin.api.format.FormatOpenMode.CUSTOM_DOCK_VIEW) {
                                    newTab.customDockView = companionView
                                } else if (probeResult.openMode() == umbrix.plugin.api.format.FormatOpenMode.SPLIT_VIEWPORT_AND_DOCK_VIEW) {
                                    val companionTab = WorkspaceTab(
                                        session = newSession,
                                        filePath = "plugin://${companionView.viewId()}",
                                        initialCustomDockView = companionView
                                    )
                                    openTabs.add(companionTab)
                                    setDockTree(DockTreeManager.splitLeaf(getDockTree(), getActiveLeafId(), companionTab.id, DockDropZone.RIGHT))
                                }
                            }
                        }
                    }
                }

                val allOpenFiles = openTabs.filter { !it.isScratchpad && it.filePath.isNotBlank() && !it.filePath.startsWith("scratch://") }.map { it.filePath }
                SettingsManager.updateSettings(
                    SettingsManager.currentSettings.copy(lastOpenedFiles = allOpenFiles).withRecentFile(targetFile.absolutePath)
                )
                val formattedSize = formatByteSize(newSession.fileSizeBytes())
                val initialLines = formatNumberWithCommas(newSession.totalLines())
                setStatusMessage("Opened ${targetFile.name} ($formattedSize, $initialLines initial lines preview) in ${newSession.indexingDurationMs()} ms")

                if (!newSession.isIndexingComplete()) {
                    newTab.isIndexing = true
                    setIsIndexingActive(true)
                    coroutineScope.launch(Dispatchers.Default) {
                        try {
                            while (!newSession.isIndexingComplete() && openTabs.contains(newTab)) {
                                delay(50)
                                withContext(Dispatchers.Main) {
                                    newTab.indexingProgress = newSession.indexingProgressPercent()
                                    newTab.indexingStatus = newSession.indexingStatusText()
                                    newTab.liveTotalLines = newSession.totalLines()
                                }
                            }
                            withContext(Dispatchers.Main) {
                                newTab.indexingProgress = 100.0
                                newTab.isIndexing = false
                                setIsIndexingActive(false)
                                val finalLines = formatNumberWithCommas(newSession.totalLines())
                                val eventsFormatted = formatNumberWithCommas(newSession.totalEvents())
                                setStatusMessage("Ready — ${targetFile.name} ($formattedSize, $finalLines lines, $eventsFormatted logical events)")
                            }
                        } catch (_: Throwable) {
                            // Tab may have closed
                        }
                    }
        } else {
            newTab.isIndexing = false
            setIsIndexingActive(false)
        }
    }

    fun openFileViaNativeDialog() {
        val chosenPath = showNativeOpenFileDialog()
        if (chosenPath != null) {
            loadLogFile(chosenPath, asNewTab = true)
        }
    }

    fun closeTab(tabIndex: Int) {
        try {
            if (tabIndex < 0 || tabIndex >= openTabs.size) return
            val tabToClose = openTabs.removeAt(tabIndex)
            setDockTree(DockTreeManager.removeTab(getDockTree(), tabToClose.id) ?: DockNode.Leaf())
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
            val activeScratchpad = openTabs.getOrNull(getActiveTabIndex())?.scratchpadSession?.id
            SettingsManager.updateSettings(
                SettingsManager.currentSettings.copy(lastOpenedFiles = remainingFiles, lastActiveScratchpadId = activeScratchpad)
            )

            if (openTabs.isEmpty()) {
                setActiveTabIndex(0)
                setStatusMessage("All tabs closed. Ready.")
            } else if (getActiveTabIndex() >= openTabs.size) {
                setActiveTabIndex(openTabs.size - 1)
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

    fun closeCurrentTab() {
        val tab = currentTab() ?: return
        closeTabById(tab.id)
    }

    fun closeAllTabs() {
        openTabs.toList().forEach { closeTabById(it.id) }
    }

    fun closeOtherTabs(keepTabId: String) {
        val toClose = openTabs.filter { it.id != keepTabId }
        toClose.forEach { closeTabById(it.id) }
    }

    fun closePane(leafId: String) {
        val leaf = DockTreeManager.findLeafById(getDockTree(), leafId)
        leaf?.tabIds?.toList()?.forEach { closeTabById(it) }
    }

    fun switchToTab(index: Int) {
        if (index in openTabs.indices) {
            setActiveTabIndex(index)
            val tab = openTabs[index]
            val tree = getDockTree()
            val leaf = DockTreeManager.findLeafForTab(tree, tab.id)
            if (leaf != null) {
                leaf.activeTabId = tab.id
                setActiveLeafId(leaf.id)
            }
        }
    }

    fun nextTab() {
        if (openTabs.isNotEmpty()) {
            val nextIndex = (getActiveTabIndex() + 1) % openTabs.size
            switchToTab(nextIndex)
        }
    }

    fun prevTab() {
        if (openTabs.isNotEmpty()) {
            val prevIndex = if (getActiveTabIndex() - 1 < 0) openTabs.size - 1 else getActiveTabIndex() - 1
            switchToTab(prevIndex)
        }
    }

    fun createNewScratchpadTab(initialText: String = ""): WorkspaceTab {
        val s = scratchpadManager.createSession(initialText)
        val newTab = WorkspaceTab(
            session = null,
            filePath = "scratch://${s.title}",
            scratchpadSession = s
        )
        openTabs.add(newTab)
        setActiveTabIndex(openTabs.size - 1)
        setDockTree(DockTreeManager.addTabToLeaf(getDockTree(), getActiveLeafId(), newTab.id))
        setStatusMessage("Opened new scratchpad ${s.title}")
        ToastManager.info("Opened new scratchpad ${s.title}")
        return newTab
    }

    fun openOrCreateTabForScratchpad(session: ScratchpadSession) {
        val existingTabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
        if (existingTabIndex >= 0) {
            setActiveTabIndex(existingTabIndex)
            val existingTab = openTabs[existingTabIndex]
            val leaf = DockTreeManager.findLeafForTab(getDockTree(), existingTab.id)
            if (leaf != null) {
                leaf.activeTabId = existingTab.id
                setActiveLeafId(leaf.id)
            }
            ToastManager.info("Switched to ${session.title}")
        } else {
            val newTab = WorkspaceTab(
                session = null,
                filePath = session.diskPath ?: "scratch://${session.title}",
                scratchpadSession = session
            )
            openTabs.add(newTab)
            setActiveTabIndex(openTabs.size - 1)
            setDockTree(DockTreeManager.addTabToLeaf(getDockTree(), getActiveLeafId(), newTab.id))
            ToastManager.info("Opened ${session.title}")
        }
    }

    fun openOrCreateTimelineTab() {
        val existingTabIndex = openTabs.indexOfFirst { it.isTimelineTab }
        if (existingTabIndex >= 0) {
            setActiveTabIndex(existingTabIndex)
            val existingTab = openTabs[existingTabIndex]
            val leaf = DockTreeManager.findLeafForTab(getDockTree(), existingTab.id)
            if (leaf != null) {
                leaf.activeTabId = existingTab.id
                setActiveLeafId(leaf.id)
            }
            ToastManager.info("Switched to Incident Timeline")
        } else {
            val newTab = WorkspaceTab(
                session = null,
                filePath = "timeline://incident"
            )
            openTabs.add(newTab)
            setActiveTabIndex(openTabs.size - 1)
            setDockTree(DockTreeManager.addTabToLeaf(getDockTree(), getActiveLeafId(), newTab.id))
            ToastManager.info("Opened Incident Timeline")
        }
    }

    private fun resolveTabForSplit(targetLeaf: DockNode.Leaf, currentWorkspaceTab: WorkspaceTab): String {
        return if (targetLeaf.tabIds.size == 1) {
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
            targetLeaf.activeTabId ?: currentWorkspaceTab.id
        }
    }

    fun splitActiveRight(sourceLeafId: String = getActiveLeafId()) {
        val targetLeaf = DockTreeManager.findLeafById(getDockTree(), sourceLeafId) ?: return
        val currentTabId = targetLeaf.activeTabId ?: return
        val currentWorkspaceTab = openTabs.find { it.id == currentTabId } ?: return

        val tabIdToPlaceInNewPane = resolveTabForSplit(targetLeaf, currentWorkspaceTab)
        setDockTree(DockTreeManager.splitLeaf(getDockTree(), sourceLeafId, tabIdToPlaceInNewPane, DockDropZone.RIGHT))
        ToastManager.info("Split pane right (Ctrl+\\)")
    }

    fun splitActiveDown(sourceLeafId: String = getActiveLeafId()) {
        val targetLeaf = DockTreeManager.findLeafById(getDockTree(), sourceLeafId) ?: return
        val currentTabId = targetLeaf.activeTabId ?: return
        val currentWorkspaceTab = openTabs.find { it.id == currentTabId } ?: return

        val tabIdToPlaceInNewPane = resolveTabForSplit(targetLeaf, currentWorkspaceTab)
        setDockTree(DockTreeManager.splitLeaf(getDockTree(), sourceLeafId, tabIdToPlaceInNewPane, DockDropZone.BOTTOM))
        ToastManager.info("Split pane down (Ctrl+Alt+\\)")
    }

    fun detachTab(tabId: String) {
        openTabs.find { it.id == tabId }?.let { tab ->
            setDockTree(DockTreeManager.removeTab(getDockTree(), tabId) ?: DockNode.Leaf())
            openTabs.remove(tab)
            detachedTabs.add(tab)
            ToastManager.info("Moved ${tab.title} to separate window")
        }
    }

    fun reDockTab(tab: WorkspaceTab) {
        detachedTabs.remove(tab)
        openTabs.add(tab)
        setDockTree(DockTreeManager.addTabToLeaf(getDockTree(), getActiveLeafId(), tab.id))
        setActiveTabIndex(openTabs.size - 1)
        ToastManager.info("Re-docked ${tab.title} into main window")
    }

    fun focusLeaf(leafId: String) {
        setActiveLeafId(leafId)
        val leaf = DockTreeManager.findLeafById(getDockTree(), leafId)
        val tabId = leaf?.activeTabId
        val idx = openTabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) setActiveTabIndex(idx)
    }

    fun focusNextPane() {
        val leaves = DockTreeManager.allLeaves(getDockTree())
        if (leaves.size > 1) {
            val currIdx = leaves.indexOfFirst { it.id == getActiveLeafId() }
            val nextLeaf = leaves[(currIdx + 1) % leaves.size]
            focusLeaf(nextLeaf.id)
            ToastManager.info("Focused next pane")
        }
    }

    fun dropTab(tabId: String, targetLeafId: String, dropZone: DockDropZone, insertIndex: Int) {
        val newTree = if (dropZone == DockDropZone.CENTER_TAB) {
            DockTreeManager.moveTab(getDockTree(), tabId, targetLeafId, insertIndex)
        } else {
            DockTreeManager.splitLeaf(getDockTree(), targetLeafId, tabId, dropZone)
        }
        setDockTree(newTree)
        val leaf = DockTreeManager.findLeafForTab(newTree, tabId)
        if (leaf != null) {
            setActiveLeafId(leaf.id)
            leaf.activeTabId = tabId
            val idx = openTabs.indexOfFirst { it.id == tabId }
            if (idx >= 0) setActiveTabIndex(idx)
        }
    }

    fun jumpToTimelineEvent(event: TimelineEvent) {
        val targetTabIdx = openTabs.indexOfFirst { it.filePath == event.sourceDocumentPath }
        if (targetTabIdx >= 0) {
            setActiveTabIndex(targetTabIdx)
            val targetTab = openTabs[targetTabIdx]
            val leaf = DockTreeManager.findLeafForTab(getDockTree(), targetTab.id)
            if (leaf != null) {
                setActiveLeafId(leaf.id)
                leaf.activeTabId = targetTab.id
            }
            targetTab.selectedLineIndex = event.lineIndex
            targetTab.topVisibleLine = (event.lineIndex - 10).coerceAtLeast(0L)
        } else {
            loadLogFile(event.sourceDocumentPath, asNewTab = true)
        }
    }

    fun pinLineToTimeline(line: Long, text: String) {
        val tab = currentTab() ?: return
        val session = tab.session
        val ts = if (session != null && session.isTemporalDataAvailable) {
            try {
                val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                session.temporalIndex().timestampAtCheckpoint(cpIdx)
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
    }

    fun appendLineToScratchpad(line: Long, text: String) {
        val tab = currentTab() ?: return
        val session = tab.session
        val ts = if (session != null && session.isTemporalDataAvailable) {
            try {
                val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                session.temporalIndex().timestampAtCheckpoint(cpIdx)
            } catch (_: Exception) { 0L }
        } else 0L
        val scratchSession = scratchpadManager.appendToActiveOrNew(
            sourceTitle = File(tab.filePath).name,
            lineIndex = line,
            timestampMicros = ts,
            snippet = text
        )
        ToastManager.info("Appended to ${scratchSession.title}")
    }

    fun toggleBookmarkOnCurrentLine() {
        val tab = currentTab() ?: return
        val session = tab.session ?: return
        val line = tab.selectedLineIndex ?: tab.topVisibleLine
        val text = try { session.getLineText(line, 4096) } catch (e: Exception) { "" }
        val ts = if (session.isTemporalDataAvailable) {
            try {
                val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(line)
                session.temporalIndex().timestampAtCheckpoint(cpIdx)
            } catch (e: Exception) { 0L }
        } else 0L
        val added = tab.bookmarkManager.toggle(line, ts, text)
        if (added) ToastManager.info("Bookmarked line ${line + 1}")
        else ToastManager.info("Removed bookmark on line ${line + 1}")
    }

    fun navigateBookmark(forward: Boolean) {
        val tab = currentTab() ?: return
        val target = if (forward) {
            tab.bookmarkManager.nextBookmark(tab.selectedLineIndex ?: tab.topVisibleLine)
        } else {
            tab.bookmarkManager.previousBookmark(tab.selectedLineIndex ?: tab.topVisibleLine)
        }
        if (target != null) {
            tab.selectedLineIndex = target
            tab.topVisibleLine = (target - 10).coerceAtLeast(0L)
            ToastManager.info("Jumped to bookmark on line ${target + 1}")
        } else {
            ToastManager.info("No bookmarks in this document")
        }
    }

    fun navigateAnomaly(forward: Boolean) {
        try {
            val tab = currentTab() ?: return
            val session = tab.session ?: return
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
                setStatusMessage("Jumped to anomaly at line ${formatNumberWithCommas(targetLine + 1L)}")
            } else {
                setStatusMessage("No errors or warnings found in document.")
            }
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            WorkstationErrorHandler.reportException(e)
        }
    }

    fun copySelectionOrLineToClipboard(): Boolean {
        val tab = currentTab() ?: return false
        val session = tab.session ?: return false
        if (tab.selectionState.hasSelection()) {
            try {
                val filteredView = if (tab.isFilteredView && tab.searchResult != null) {
                    session.createFilteredView(tab.searchResult!!.matchingLines())
                } else null
                val temporalStart = if (filteredView == null && tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                val virtualLineCount = ViewportCoordinateMapper.virtualLineCount(
                    session = session,
                    filteredView = filteredView,
                    isTemporalFocusActive = tab.isTemporalFocusActive,
                    temporalStartLine = temporalStart,
                    temporalEndLine = tab.temporalFocusEndLine
                )
                val isTemporalMode = filteredView == null && tab.isTemporalFocusActive
                val selectedText = tab.selectionState.extractSelectedText(virtualLineCount, { virtualRow ->
                    val physicalLine = ViewportCoordinateMapper.resolvePhysicalLine(
                        virtualRow = VirtualRowIndex(virtualRow),
                        totalLinesCount = virtualLineCount,
                        isFilteredMode = filteredView != null,
                        filteredView = filteredView,
                        isTemporalMode = isTemporalMode,
                        temporalStartLine = temporalStart
                    ).value
                    session.getLineText(physicalLine, ViewportState.MAX_SHAPED_CHARS)
                })
                if (selectedText.isNotEmpty()) {
                    val selection = StringSelection(selectedText)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    setStatusMessage("Copied ${formatNumberWithCommas(selectedText.length.toLong())} characters to clipboard.")
                    ToastManager.success("Copied ${formatNumberWithCommas(selectedText.length.toLong())} characters to clipboard.")
                }
            } catch (e: SelectionTooLargeException) {
                setStatusMessage("Selection too large to copy (limit: 1 MiB or 8,192 rows).")
                ToastManager.warn("Selection too large to copy (limit: 1 MiB or 8,192 rows).")
            } catch (e: Exception) {
                setStatusMessage("Could not copy the selected log text.")
                ToastManager.error("Could not copy the selected log text.")
            }
            return true
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
                        setStatusMessage("Copied line ${formatNumberWithCommas(selectedLine + 1L)} to clipboard.")
                        ToastManager.success("Copied line ${formatNumberWithCommas(selectedLine + 1L)} to clipboard.")
                    } catch (_: Throwable) {}
                }
                return true
            }
            return false
        }
    }

    fun exportSubFile() {
        val tab = currentTab() ?: return
        if (tab.isScratchpad) {
            val scratchpad = tab.scratchpadSession ?: return
            val defaultName = if (scratchpad.title.endsWith(".log") || scratchpad.title.endsWith(".txt")) {
                "excerpt_${scratchpad.title}"
            } else {
                "excerpt_${scratchpad.title}.log"
            }
            val destinationPath = showNativeSaveFileDialog(defaultName) ?: return

            coroutineScope.launch {
                try {
                    setStatusMessage("Exporting scratchpad excerpt to $destinationPath...")
                    val targetFile = File(destinationPath)
                    withContext(Dispatchers.IO) {
                        val textToExport = if (tab.isFilteredView && tab.searchResult != null) {
                            val lines = scratchpad.content.lines()
                            val matchingIndices = tab.searchResult!!.matchingLines()
                            lines.filterIndexed { index, _ -> matchingIndices.contains(index.toLong()) }.joinToString("\n")
                        } else {
                            scratchpad.content
                        }
                        targetFile.writeText(textToExport, Charsets.UTF_8)
                    }
                    setStatusMessage("Exported scratchpad excerpt to ${targetFile.name}. Opening in new tab...")
                    ToastManager.success("Exported sub-file: ${targetFile.name}")
                    loadLogFile(destinationPath, asNewTab = true)
                } catch (e: Throwable) {
                    if (ForensicErrorReportGenerator.isFatal(e)) throw e
                    WorkstationErrorHandler.reportException(e)
                }
            }
            return
        }
        val session = tab.session ?: return
        val defaultName = "excerpt_${File(tab.filePath).nameWithoutExtension}.log"
        val destinationPath = showNativeSaveFileDialog(defaultName) ?: return

        coroutineScope.launch {
            try {
                setStatusMessage("Exporting sub-file to $destinationPath...")
                val sliceResult = withContext(Dispatchers.IO) {
                    val targetFile = File(destinationPath)
                    if (tab.isFilteredView && tab.searchResult != null) {
                        session.exportFilteredLines(tab.searchResult!!.matchingLines(), targetFile.toPath())
                    } else {
                        val endLine = (tab.topVisibleLine + 1000L).coerceAtMost(session.totalLines() - 1L)
                        session.exportContiguousRange(tab.topVisibleLine, endLine, targetFile.toPath())
                    }
                }

                setStatusMessage("Exported ${sliceResult.linesExported()} lines (${formatByteSize(sliceResult.bytesWritten())}) in ${sliceResult.durationNanos() / 1_000_000L} ms. Opening in new tab...")
                ToastManager.success("Exported sub-file: ${File(destinationPath).name} (${formatByteSize(sliceResult.bytesWritten())})")
                loadLogFile(destinationPath, asNewTab = true)
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext.fromSession(
                    tab.filePath,
                    session.fileSizeBytes(),
                    session.totalLines(),
                    tab.topVisibleLine
                )
                WorkstationErrorHandler.reportException(e, context)
                setStatusMessage("Export failed: ${e.message}")
            }
        }
    }

    fun exportContiguousRange(startLine: Long, endLine: Long, destinationFile: File) {
        val tab = currentTab() ?: return
        val session = tab.session ?: return
        coroutineScope.launch {
            try {
                setStatusMessage("Exporting sub-file to ${destinationFile.absolutePath}...")
                val sliceResult = withContext(Dispatchers.IO) {
                    session.exportContiguousRange(startLine, endLine, destinationFile.toPath())
                }
                val formattedBytes = formatByteSize(sliceResult.bytesWritten())
                val formattedLines = formatNumberWithCommas(sliceResult.linesExported())
                val durationMs = sliceResult.durationNanos() / 1_000_000L
                setStatusMessage("Exported $formattedLines lines ($formattedBytes) in $durationMs ms. Opening in new tab...")
                ToastManager.success("Exported $formattedLines lines ($formattedBytes) to ${destinationFile.name}")
                loadLogFile(destinationFile.absolutePath, asNewTab = true)
            } catch (e: Throwable) {
                if (ForensicErrorReportGenerator.isFatal(e)) throw e
                val context = ForensicReportContext.fromSession(
                    tab.filePath,
                    session.fileSizeBytes(),
                    session.totalLines(),
                    startLine
                )
                WorkstationErrorHandler.reportException(e, context)
                setStatusMessage("Sub-file export failed: ${e.message}")
                ToastManager.error("Export failed: ${e.message}")
            }
        }
    }

    fun openCustomDockView(
        viewFactory: umbrix.plugin.api.ui.DockViewFactory,
        dropZone: DockDropZone = DockDropZone.CENTER_TAB
    ) {
        val s = activeSession()
        val targetViewId = viewFactory.viewId()

        // De-dupe: if a tab for the same viewId + session already exists, focus it instead of opening another
        val existingIndex = openTabs.indexOfFirst { tab ->
            tab.session === s && (tab.customDockView?.viewId() == targetViewId || tab.filePath == "plugin://$targetViewId")
        }
        if (existingIndex >= 0) {
            val existingTab = openTabs[existingIndex]
            setActiveTabIndex(existingIndex)
            val leaf = DockTreeManager.findLeafForTab(getDockTree(), existingTab.id)
            if (leaf != null) {
                setActiveLeafId(leaf.id)
                leaf.activeTabId = existingTab.id
            }
            return
        }

        val sessionView = s?.pluginSessionView() ?: umbrix.core.plugin.PluginManager.instance().getWorkspaceController().activeSession()
        if (sessionView == null) {
            ToastManager.warn("Open a session first to open ${viewFactory.displayName()}.")
            return
        }
        val view = viewFactory.createView(sessionView)
        val tab = WorkspaceTab(
            session = s,
            filePath = "plugin://${view.viewId()}",
            initialCustomDockView = view
        )
        openTabs.add(tab)
        setActiveTabIndex(openTabs.size - 1)

        val newTree = if (dropZone == DockDropZone.CENTER_TAB || openTabs.size <= 1) {
            DockTreeManager.addTabToLeaf(getDockTree(), getActiveLeafId(), tab.id)
        } else {
            DockTreeManager.splitLeaf(getDockTree(), getActiveLeafId(), tab.id, dropZone)
        }
        setDockTree(newTree)
        val leaf = DockTreeManager.findLeafForTab(newTree, tab.id)
        if (leaf != null) {
            setActiveLeafId(leaf.id)
            leaf.activeTabId = tab.id
        }
        ToastManager.info("Opened ${view.title()}")
    }

    fun showRawRecordsForActiveSession() {
        val current = currentTab()
        val session = current?.session
        if (current == null || session == null) {
            ToastManager.warn("No active session to show raw records.")
            return
        }
        // If a raw tab for the same session already exists, focus it
        val existingRawIdx = openTabs.indexOfFirst { it.session === session && it.customDockView == null && !it.isScratchpad }
        if (existingRawIdx >= 0) {
            val existingRawTab = openTabs[existingRawIdx]
            setActiveTabIndex(existingRawIdx)
            val leaf = DockTreeManager.findLeafForTab(getDockTree(), existingRawTab.id)
            if (leaf != null) {
                setActiveLeafId(leaf.id)
                leaf.activeTabId = existingRawTab.id
            }
            return
        }
        val rawTab = WorkspaceTab(
            session = session,
            filePath = current.filePath,
            initialTopVisibleLine = current.topVisibleLine
        )
        openTabs.add(rawTab)
        setActiveTabIndex(openTabs.size - 1)
        val newTree = DockTreeManager.splitLeaf(getDockTree(), getActiveLeafId(), rawTab.id, DockDropZone.RIGHT)
        setDockTree(newTree)
        val leaf = DockTreeManager.findLeafForTab(newTree, rawTab.id)
        if (leaf != null) {
            setActiveLeafId(leaf.id)
            leaf.activeTabId = rawTab.id
        }
        ToastManager.info("Opened raw records view")
    }

    fun generateDiagnosticReport() {
        val tab = currentTab()
        val session = tab?.session
        val context = if (tab != null && session != null) {
            ForensicReportContext.fromSession(tab.filePath, session.fileSizeBytes(), session.totalLines(), tab.topVisibleLine)
        } else {
            ForensicReportContext.empty()
        }
        val report = WorkstationErrorHandler.reportException(OperatorDiagnosticSnapshot(), context)
        setStatusMessage("Diagnostic report ${report.reportId()} generated — ${report.reportFilePathString()}")
    }
}
