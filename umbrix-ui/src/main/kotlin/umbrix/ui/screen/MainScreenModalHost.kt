package umbrix.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.core.diagnostics.ForensicReportContext
import umbrix.core.session.LogSession
import umbrix.ui.bookmarks.BookmarkManagerDialog
import umbrix.ui.dialog.AboutDialog
import umbrix.ui.dialog.ExportSubFileDialog
import umbrix.ui.dialog.GoToTimestampDialog
import umbrix.ui.dialog.KeyboardShortcutsDialog
import umbrix.ui.dialog.RenameScratchpadDialog
import umbrix.ui.dialog.SettingsDialog
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.menu.MenuAction
import umbrix.ui.palette.CommandExecutionContext
import umbrix.ui.palette.CommandPaletteDialog
import umbrix.ui.scratchpad.ScratchpadManager
import umbrix.ui.scratchpad.ScratchpadManagerDialog
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.FontSpec
import umbrix.ui.timeline.EventTag
import umbrix.ui.timeline.InvestigationTimelineManager
import umbrix.ui.timeline.TimelineExportDialog
import umbrix.ui.toast.ToastHost
import umbrix.ui.toast.ToastManager
import umbrix.ui.util.formatByteSize
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Hosts all modal cards, dialogs, command palettes, and floating toast notifications for UmbrixMainScreen.
 */
@Composable
fun MainScreenModalHost(
    activeSession: LogSession?,
    currentTab: WorkspaceTab?,
    openTabs: SnapshotStateList<WorkspaceTab>,
    activeTabIndex: Int,
    onActiveTabIndexChange: (Int) -> Unit,
    timelineManager: InvestigationTimelineManager,
    scratchpadManager: ScratchpadManager,
    coroutineScope: CoroutineScope,
    isSettingsDialogVisible: Boolean,
    onSetSettingsDialogVisible: (Boolean) -> Unit,
    isGoToLineDialogVisible: Boolean,
    onSetGoToLineDialogVisible: (Boolean) -> Unit,
    isAboutDialogVisible: Boolean,
    onSetAboutDialogVisible: (Boolean) -> Unit,
    isShortcutsDialogVisible: Boolean,
    onSetShortcutsDialogVisible: (Boolean) -> Unit,
    isExportDialogVisible: Boolean,
    onSetExportDialogVisible: (Boolean) -> Unit,
    isCommandPaletteVisible: Boolean,
    onSetCommandPaletteVisible: (Boolean) -> Unit,
    commandPaletteInitialQuery: String,
    onSetCommandPaletteInitialQuery: (String) -> Unit,
    isScratchpadManagerDialogOpen: Boolean,
    onSetScratchpadManagerDialogOpen: (Boolean) -> Unit,
    isBookmarkDialogOpen: Boolean,
    onSetBookmarkDialogOpen: (Boolean) -> Unit,
    isTimelineExportDialogOpen: Boolean,
    onSetTimelineExportDialogOpen: (Boolean) -> Unit,
    renamingScratchpadTabIndex: Int?,
    onSetRenamingScratchpadTabIndex: (Int?) -> Unit,
    isSearchBarVisible: Boolean,
    onSetSearchBarVisible: (Boolean) -> Unit,
    searchFocusTrigger: Int,
    onSetSearchFocusTrigger: (Int) -> Unit,
    isTimelineDrawerOpen: Boolean,
    onSetTimelineDrawerOpen: (Boolean) -> Unit,
    isAiAssistantOpen: Boolean,
    onSetAiAssistantOpen: (Boolean) -> Unit,
    onStatusMessageTextChange: (String) -> Unit,
    onOpenFileViaNativeDialog: () -> Unit,
    onLoadLogFile: (String, Boolean) -> Unit,
    onCloseTab: (Int) -> Unit,
    onGenerateDiagnosticReport: () -> Unit,
    onHandleMenuAction: (MenuAction) -> Unit
) {
    // 1. Go to Line or Timestamp Modal Dialog (Ctrl+G / Cmd+G)
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
                            tab.isTemporalFocusActive = false
                            tab.topVisibleLine = clampedLine
                            tab.selectedLineIndex = clampedLine
                        }
                    } else {
                        tab.topVisibleLine = targetLine
                        tab.selectedLineIndex = targetLine
                    }
                }
                onSetGoToLineDialogVisible(false)
            },
            onDismiss = { onSetGoToLineDialogVisible(false) }
        )
    }

    // 2. Settings Modal Dialog (Ctrl+,)
    if (isSettingsDialogVisible) {
        SettingsDialog(
            initialSettings = SettingsManager.currentSettings,
            onSaveSettings = { updatedSettings ->
                SettingsManager.updateSettings(updatedSettings)
            },
            onDismiss = { onSetSettingsDialogVisible(false) }
        )
    }

    // 3. About Dialog
    if (isAboutDialogVisible) {
        AboutDialog(
            onDismiss = { onSetAboutDialogVisible(false) }
        )
    }

    // 4. Keyboard Shortcuts Cheatsheet Dialog (F1 / Ctrl+/)
    if (isShortcutsDialogVisible) {
        KeyboardShortcutsDialog(
            onDismiss = { onSetShortcutsDialogVisible(false) }
        )
    }

    // 5. Export Sub-File Dialog (Ctrl+Shift+S)
    if (isExportDialogVisible && activeSession != null && currentTab != null) {
        ExportSubFileDialog(
            session = activeSession,
            currentLine = currentTab.selectedLineIndex ?: currentTab.topVisibleLine,
            sourceFilePath = currentTab.filePath,
            onExportRequested = { startLine, endLine, destinationFile, _ ->
                coroutineScope.launch {
                    try {
                        onStatusMessageTextChange("Exporting sub-file to ${destinationFile.absolutePath}...")
                        val sliceResult = withContext(Dispatchers.IO) {
                            activeSession.exportContiguousRange(startLine, endLine, destinationFile.toPath())
                        }
                        onStatusMessageTextChange("Exported ${sliceResult.linesExported()} lines (${formatByteSize(sliceResult.bytesWritten())}) in ${sliceResult.durationNanos() / 1_000_000L} ms. Opening in new tab...")
                        onLoadLogFile(destinationFile.absolutePath, true)
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
            onDismiss = { onSetExportDialogVisible(false) }
        )
    }

    // 6. Command Palette Dialog
    if (isCommandPaletteVisible) {
        CommandPaletteDialog(
            isVisible = isCommandPaletteVisible,
            initialQuery = commandPaletteInitialQuery,
            executionContext = CommandExecutionContext(
                onOpenFile = { onOpenFileViaNativeDialog() },
                onOpenRecentFile = { path -> onLoadLogFile(path, true) },
                onExportSubFile = {
                    if (activeSession != null) {
                        onSetExportDialogVisible(true)
                    } else {
                        onStatusMessageTextChange("Open a log file to export sub-files.")
                    }
                },
                onGoToLine = {
                    if (activeSession != null) {
                        onSetGoToLineDialogVisible(true)
                    } else {
                        onStatusMessageTextChange("Open a log file to use Go To Line.")
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
                        onSetGoToLineDialogVisible(true)
                    } else {
                        onStatusMessageTextChange("Open a log file to use Go To Timestamp.")
                    }
                },
                onOpenSearch = {
                    onSetSearchBarVisible(true)
                    onSetSearchFocusTrigger(searchFocusTrigger + 1)
                },
                onClearSearch = {
                    onSetSearchBarVisible(false)
                    if (currentTab != null && activeSession != null) {
                        MainScreenSearchController.triggerSearch(currentTab, activeSession, coroutineScope, "") {}
                    }
                },
                onNavigateSearch = { forward ->
                    if (currentTab != null && activeSession != null) {
                        MainScreenSearchController.navigateSearch(currentTab, activeSession, forward)
                    }
                },
                onNavigateAnomaly = { forward ->
                    val tab = currentTab
                    val session = activeSession
                    if (tab != null && session != null) {
                        val current = tab.selectedLineIndex ?: tab.topVisibleLine
                        val nextAnomaly = if (forward) session.findNextAnomaly(current) else session.findPreviousAnomaly(current)
                        if (nextAnomaly >= 0L) {
                            tab.selectedLineIndex = nextAnomaly
                            tab.topVisibleLine = (nextAnomaly - 5).coerceAtLeast(0L)
                            onStatusMessageTextChange("Jumped to anomaly at line ${nextAnomaly + 1}")
                        }
                    }
                },
                onOpenSettings = { onSetSettingsDialogVisible(true) },
                onOpenShortcuts = { onSetShortcutsDialogVisible(true) },
                onOpenAbout = { onSetAboutDialogVisible(true) },
                onCaptureDiagnosticReport = { onGenerateDiagnosticReport() },
                onCloseCurrentTab = {
                    if (activeTabIndex in openTabs.indices) {
                        onCloseTab(activeTabIndex)
                    }
                },
                onCloseAllTabs = {
                    while (openTabs.isNotEmpty()) {
                        onCloseTab(0)
                    }
                },
                onNextTab = {
                    if (openTabs.isNotEmpty()) {
                        onActiveTabIndexChange((activeTabIndex + 1) % openTabs.size)
                    }
                },
                onPrevTab = {
                    if (openTabs.isNotEmpty()) {
                        onActiveTabIndexChange(if (activeTabIndex - 1 < 0) openTabs.size - 1 else activeTabIndex - 1)
                    }
                },
                onSwitchToTab = { index ->
                    if (index in openTabs.indices) {
                        onActiveTabIndexChange(index)
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
                onToggleTimelineDrawer = { onHandleMenuAction(MenuAction.ToggleTimelineDrawer) },
                onSortTimelineChronologically = {
                    timelineManager.sortChronologically()
                    ToastManager.success("Timeline sorted chronologically")
                },
                onExportTimelineReport = { onSetTimelineExportDialogOpen(true) },
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
                onOpenBookmarkManager = { onSetBookmarkDialogOpen(true) },
                onNewScratchpad = {
                    val s = scratchpadManager.createSession()
                    val newTab = WorkspaceTab(
                        session = null,
                        filePath = "scratch://${s.title}",
                        scratchpadSession = s
                    )
                    openTabs.add(newTab)
                    onActiveTabIndexChange(openTabs.size - 1)
                    ToastManager.info("Created ${s.title}")
                },
                onOpenScratchpadManager = { onSetScratchpadManagerDialogOpen(true) },
                onToggleBookmarkFilter = { onHandleMenuAction(MenuAction.ToggleBookmarkFilter) },
                onExpandAllEvents = {
                    currentTab?.expandAllEvents()
                    ToastManager.info("Expanded all multi-line events in active tab")
                },
                onCollapseAllEvents = {
                    currentTab?.collapseAllEvents()
                    ToastManager.info("Collapsed all multi-line events in active tab")
                },
                onToggleAiAssistant = {
                    onSetAiAssistantOpen(!isAiAssistantOpen)
                }
            ),
            onDismiss = {
                onSetCommandPaletteVisible(false)
                onSetCommandPaletteInitialQuery("")
            }
        )
    }

    // 7. Scratchpad Manager Modal Dialog
    if (isScratchpadManagerDialogOpen) {
        val openScratchpadIds = openTabs.mapNotNull { it.scratchpadSession?.id }.toSet()
        ScratchpadManagerDialog(
            scratchpadManager = scratchpadManager,
            openScratchpadIds = openScratchpadIds,
            onOpenScratchpad = { session ->
                val existingTabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
                if (existingTabIndex >= 0) {
                    onActiveTabIndexChange(existingTabIndex)
                    ToastManager.info("Switched to ${session.title}")
                } else {
                    val newTab = WorkspaceTab(
                        session = null,
                        filePath = session.diskPath ?: "scratch://${session.title}",
                        scratchpadSession = session
                    )
                    openTabs.add(newTab)
                    onActiveTabIndexChange(openTabs.size - 1)
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
                onActiveTabIndexChange(openTabs.size - 1)
                ToastManager.info("Created ${s.title}")
            },
            onDeleteScratchpad = { session ->
                val tabIndex = openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
                if (tabIndex >= 0) {
                    onCloseTab(tabIndex)
                }
                scratchpadManager.deleteSession(session)
            },
            onDismiss = { onSetScratchpadManagerDialogOpen(false) }
        )
    }

    // 8. Bookmarks Manager Modal Dialog
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
                onSetTimelineDrawerOpen(true)
            },
            onDismiss = { onSetBookmarkDialogOpen(false) },
            isBookmarkFilterActive = currentTab.isBookmarkFilterActive,
            onToggleBookmarkFilter = { onHandleMenuAction(MenuAction.ToggleBookmarkFilter) }
        )
    }

    // 9. Timeline Report Export Modal Dialog
    if (isTimelineExportDialogOpen) {
        TimelineExportDialog(
            events = timelineManager.events,
            onCopyToClipboard = { content ->
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val sel = StringSelection(content)
                clipboard.setContents(sel, sel)
                ToastManager.success("Incident Report copied to clipboard")
            },
            onDismiss = { onSetTimelineExportDialogOpen(false) }
        )
    }

    // 10. Rename Scratchpad Modal Dialog
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
                    onSetRenamingScratchpadTabIndex(null)
                },
                onDismiss = { onSetRenamingScratchpadTabIndex(null) }
            )
        } else {
            onSetRenamingScratchpadTabIndex(null)
        }
    }

    // Floating Toast Notifications Host
    ToastHost(modifier = Modifier.fillMaxSize())
}
