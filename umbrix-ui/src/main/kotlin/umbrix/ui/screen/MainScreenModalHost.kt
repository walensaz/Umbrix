package umbrix.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import umbrix.ui.bookmarks.BookmarkManagerDialog
import umbrix.ui.dialog.AboutDialog
import umbrix.ui.dialog.ActiveDialog
import umbrix.ui.dialog.ConfirmFormatOpenDialog
import umbrix.ui.dialog.ExportSubFileDialog
import umbrix.ui.dialog.FormatOpenPolicy
import umbrix.ui.dialog.GoToTimestampDialog
import umbrix.ui.dialog.KeyboardShortcutsDialog
import umbrix.ui.palette.CommandExecutionContext
import umbrix.ui.palette.CommandPaletteDialog
import umbrix.ui.dialog.RenameScratchpadDialog
import umbrix.ui.dialog.SettingsDialog
import umbrix.ui.scratchpad.ScratchpadManagerDialog
import umbrix.ui.settings.SettingsManager
import umbrix.ui.timeline.EventTag
import umbrix.ui.timeline.TimelineExportDialog
import umbrix.ui.toast.ToastHost
import umbrix.ui.toast.ToastManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Standardized modal dialog and toast notification host driven by [ActiveDialog].
 * Replaces 38+ individual boolean flags and parameters with cohesive coordinators.
 */
@Composable
fun MainScreenModalHost(
    activeDialog: ActiveDialog,
    onActiveDialogChange: (ActiveDialog) -> Unit,
    tabCoordinator: MainScreenTabCoordinator,
    menuCoordinator: MainScreenMenuCoordinator,
    isSearchBarVisible: Boolean,
    onSetSearchBarVisible: (Boolean) -> Unit,
    onIncrementSearchFocusTrigger: () -> Unit,
    isAiAssistantOpen: Boolean,
    onSetAiAssistantOpen: (Boolean) -> Unit
) {
    val currentTab = tabCoordinator.currentTab()
    val activeSession = tabCoordinator.activeSession()

    when (activeDialog) {
        is ActiveDialog.GoToLineTimestamp -> {
            if (activeSession != null) {
                GoToTimestampDialog(
                    session = activeSession,
                    currentLine = currentTab?.selectedLineIndex ?: currentTab?.topVisibleLine ?: 0L,
                    documentTitle = currentTab?.title,
                    onGoToLine = { targetLine ->
                        currentTab?.let { tab ->
                            if (tab.isTemporalFocusActive) {
                                val clampedLine = targetLine.coerceIn(0L, maxOf(0L, activeSession.totalLines() - 1L))
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
                        onActiveDialogChange(ActiveDialog.None)
                    },
                    onDismiss = { onActiveDialogChange(ActiveDialog.None) }
                )
            }
        }

        is ActiveDialog.Settings -> {
            SettingsDialog(
                initialSettings = SettingsManager.currentSettings,
                onSaveSettings = { updatedSettings ->
                    SettingsManager.updateSettings(updatedSettings)
                },
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.About -> {
            AboutDialog(
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.KeyboardShortcuts -> {
            KeyboardShortcutsDialog(
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.ExportSubFile -> {
            if (activeSession != null && currentTab != null) {
                ExportSubFileDialog(
                    session = activeSession,
                    currentLine = currentTab.selectedLineIndex ?: currentTab.topVisibleLine,
                    sourceFilePath = currentTab.filePath,
                    onExportRequested = { startLine, endLine, destinationFile, _ ->
                        tabCoordinator.exportContiguousRange(startLine, endLine, destinationFile)
                    },
                    onDismiss = { onActiveDialogChange(ActiveDialog.None) }
                )
            }
        }

        is ActiveDialog.CommandPalette -> {
            CommandPaletteDialog(
                isVisible = true,
                initialQuery = activeDialog.initialQuery,
                executionContext = CommandExecutionContext(
                    onOpenFile = { tabCoordinator.openFileViaNativeDialog() },
                    onOpenRecentFile = { path -> tabCoordinator.loadLogFile(path, true) },
                    onExportSubFile = {
                        if (activeSession != null) {
                            onActiveDialogChange(ActiveDialog.ExportSubFile)
                        } else {
                            ToastManager.warn("Open a log file to export sub-files.")
                        }
                    },
                    onGoToLine = {
                        if (activeSession != null) {
                            onActiveDialogChange(ActiveDialog.GoToLineTimestamp)
                        } else {
                            ToastManager.warn("Open a log file to use Go To Line.")
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
                            onActiveDialogChange(ActiveDialog.GoToLineTimestamp)
                        } else {
                            ToastManager.warn("Open a log file to use Go To Timestamp.")
                        }
                    },
                    onOpenSearch = {
                        onSetSearchBarVisible(true)
                        onIncrementSearchFocusTrigger()
                    },
                    onClearSearch = {
                        onSetSearchBarVisible(false)
                        if (currentTab != null && activeSession != null) {
                            MainScreenSearchController.triggerSearch(currentTab, activeSession, tabCoordinator.coroutineScope, "") {}
                        }
                    },
                    onNavigateSearch = { forward ->
                        if (currentTab != null && activeSession != null) {
                            MainScreenSearchController.navigateSearch(currentTab, activeSession, forward)
                        }
                    },
                    onNavigateAnomaly = { forward ->
                        tabCoordinator.navigateAnomaly(forward)
                    },
                    onOpenSettings = { onActiveDialogChange(ActiveDialog.Settings) },
                    onOpenShortcuts = { onActiveDialogChange(ActiveDialog.KeyboardShortcuts) },
                    onOpenAbout = { onActiveDialogChange(ActiveDialog.About) },
                    onCaptureDiagnosticReport = { tabCoordinator.generateDiagnosticReport() },
                    onCloseCurrentTab = { tabCoordinator.closeCurrentTab() },
                    onCloseAllTabs = { tabCoordinator.closeAllTabs() },
                    onNextTab = { tabCoordinator.nextTab() },
                    onPrevTab = { tabCoordinator.prevTab() },
                    onSwitchToTab = { index -> tabCoordinator.switchToTab(index) },
                    onZoomIn = { menuCoordinator.adjustZoom(1.0f) },
                    onZoomOut = { menuCoordinator.adjustZoom(-1.0f) },
                    onResetZoom = { menuCoordinator.resetZoom() },
                    openTabTitles = tabCoordinator.openTabs.map { File(it.filePath).name },
                    activeTabIndex = tabCoordinator.openTabs.indexOf(currentTab).coerceAtLeast(0),
                    onToggleTimelineDrawer = { tabCoordinator.openOrCreateTimelineTab() },
                    onSortTimelineChronologically = {
                        tabCoordinator.timelineManager.sortChronologically()
                        ToastManager.success("Timeline sorted chronologically")
                    },
                    onExportTimelineReport = { onActiveDialogChange(ActiveDialog.TimelineExport) },
                    onToggleBookmark = { tabCoordinator.toggleBookmarkOnCurrentLine() },
                    onNextBookmark = { tabCoordinator.navigateBookmark(true) },
                    onPrevBookmark = { tabCoordinator.navigateBookmark(false) },
                    onOpenBookmarkManager = { onActiveDialogChange(ActiveDialog.BookmarkManager) },
                    onNewScratchpad = { tabCoordinator.createNewScratchpadTab() },
                    onOpenScratchpadManager = { onActiveDialogChange(ActiveDialog.ScratchpadManager) },
                    onToggleBookmarkFilter = { menuCoordinator.toggleBookmarkFilter(currentTab) },
                    onExpandAllEvents = {
                        currentTab?.expandAllEvents()
                        ToastManager.info("Expanded all multi-line events in active tab")
                    },
                    onCollapseAllEvents = {
                        currentTab?.collapseAllEvents()
                        ToastManager.info("Collapsed all multi-line events in active tab")
                    },
                    onToggleAiAssistant = { onSetAiAssistantOpen(!isAiAssistantOpen) },
                    onShowRawRecords = if (currentTab?.session != null && currentTab.customDockView != null) {
                        { tabCoordinator.showRawRecordsForActiveSession() }
                    } else null
                ),
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.ScratchpadManager -> {
            val openScratchpadIds = tabCoordinator.openTabs.mapNotNull { it.scratchpadSession?.id }.toSet()
            ScratchpadManagerDialog(
                scratchpadManager = tabCoordinator.scratchpadManager,
                openScratchpadIds = openScratchpadIds,
                onOpenScratchpad = { session ->
                    tabCoordinator.openOrCreateTabForScratchpad(session)
                },
                onNewScratchpad = {
                    tabCoordinator.createNewScratchpadTab()
                },
                onDeleteScratchpad = { session ->
                    val tabIndex = tabCoordinator.openTabs.indexOfFirst { it.scratchpadSession?.id == session.id }
                    if (tabIndex >= 0) {
                        tabCoordinator.closeTab(tabIndex)
                    }
                    tabCoordinator.scratchpadManager.deleteSession(session)
                },
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.BookmarkManager -> {
            if (currentTab != null) {
                BookmarkManagerDialog(
                    bookmarkManager = currentTab.bookmarkManager,
                    documentTitle = currentTab.title,
                    onJumpToLine = { targetLine ->
                        currentTab.selectedLineIndex = targetLine
                        currentTab.topVisibleLine = (targetLine - 10).coerceAtLeast(0L)
                    },
                    onAddToTimeline = { bm ->
                        tabCoordinator.timelineManager.addEvent(
                            sourceDocumentPath = currentTab.filePath,
                            sourceDocumentTitle = currentTab.title,
                            lineIndex = bm.lineIndex,
                            timestampMicros = bm.timestampMicros,
                            lineContent = bm.lineSnippet,
                            tag = EventTag.fromString(bm.tag.name),
                            userNote = bm.note
                        )
                        ToastManager.success("Added bookmark to Timeline")
                        tabCoordinator.openOrCreateTimelineTab()
                    },
                    onDismiss = { onActiveDialogChange(ActiveDialog.None) },
                    isBookmarkFilterActive = currentTab.isBookmarkFilterActive,
                    onToggleBookmarkFilter = { menuCoordinator.toggleBookmarkFilter(currentTab) }
                )
            }
        }

        is ActiveDialog.TimelineExport -> {
            TimelineExportDialog(
                events = tabCoordinator.timelineManager.events,
                onCopyToClipboard = { content ->
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val sel = StringSelection(content)
                    clipboard.setContents(sel, sel)
                    ToastManager.success("Incident Report copied to clipboard")
                },
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.RenameScratchpad -> {
            val tab = tabCoordinator.openTabs.getOrNull(activeDialog.tabIndex)
            val scratchpad = tab?.scratchpadSession
            if (scratchpad != null) {
                RenameScratchpadDialog(
                    currentTitle = scratchpad.title,
                    onConfirm = { newTitle ->
                        scratchpad.title = newTitle
                        tabCoordinator.scratchpadManager.renameSession(scratchpad.id, newTitle)
                        ToastManager.info("Renamed scratchpad to $newTitle")
                        onActiveDialogChange(ActiveDialog.None)
                    },
                    onDismiss = { onActiveDialogChange(ActiveDialog.None) }
                )
            } else {
                onActiveDialogChange(ActiveDialog.None)
            }
        }

        is ActiveDialog.ConfirmFormatOpen -> {
            ConfirmFormatOpenDialog(
                filePath = activeDialog.filePath,
                formatDisplayName = activeDialog.formatDisplayName,
                onOpenAsFormat = {
                    val targetPath = activeDialog.filePath
                    val targetAsNewTab = activeDialog.asNewTab
                    onActiveDialogChange(ActiveDialog.None)
                    tabCoordinator.loadLogFile(targetPath, targetAsNewTab, FormatOpenPolicy.FORCE_FORMAT)
                },
                onOpenAsPlainLog = {
                    val targetPath = activeDialog.filePath
                    val targetAsNewTab = activeDialog.asNewTab
                    onActiveDialogChange(ActiveDialog.None)
                    tabCoordinator.loadLogFile(targetPath, targetAsNewTab, FormatOpenPolicy.FORCE_PLAIN_LOG)
                },
                onDismiss = { onActiveDialogChange(ActiveDialog.None) }
            )
        }

        is ActiveDialog.None -> Unit
    }

    // Floating Toast Notifications Host
    ToastHost(modifier = Modifier.fillMaxSize())
}
