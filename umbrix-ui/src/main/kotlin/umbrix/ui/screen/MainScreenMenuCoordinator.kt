package umbrix.ui.screen

import umbrix.core.diagnostics.ForensicErrorReportGenerator
import umbrix.ui.dialog.ActiveDialog
import umbrix.ui.menu.MenuAction
import umbrix.ui.menu.MenuSettingsReducer
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.FontSpec
import umbrix.ui.toast.ToastManager

/**
 * Handles title bar and hamburger menu dispatching, bookmark filter math,
 * and UI zoom coordinate scaling.
 */
class MainScreenMenuCoordinator(
    private val tabCoordinator: MainScreenTabCoordinator,
    private val onOpenDialog: (ActiveDialog) -> Unit,
    private val onOpenSearchBar: () -> Unit,
    private val onExitRequest: () -> Unit,
    private val setStatusMessage: (String) -> Unit
) {

    fun handleMenuAction(action: MenuAction) {
        try {
            val updatedSettings = MenuSettingsReducer.reduce(action, SettingsManager.currentSettings)
            if (updatedSettings != null) {
                SettingsManager.updateSettings(updatedSettings)
            }
            val tab = tabCoordinator.currentTab()
            val session = tab?.session

            when (action) {
                is MenuAction.OpenFile -> tabCoordinator.openFileViaNativeDialog()
                is MenuAction.NewScratchpad -> tabCoordinator.createNewScratchpadTab()
                is MenuAction.OpenScratchpadManager -> onOpenDialog(ActiveDialog.ScratchpadManager)
                is MenuAction.OpenRecentFile -> tabCoordinator.loadLogFile(action.path, asNewTab = true)
                is MenuAction.ClearRecentFiles -> setStatusMessage("Cleared recent files.")
                is MenuAction.ExportSubFile -> {
                    if (session != null) {
                        onOpenDialog(ActiveDialog.ExportSubFile)
                    } else {
                        ToastManager.warn("Open a log file to export sub-files.")
                        setStatusMessage("Open a log file to export sub-files.")
                    }
                }
                is MenuAction.CommandPalette -> onOpenDialog(ActiveDialog.CommandPalette())
                is MenuAction.Find -> onOpenSearchBar()
                is MenuAction.GoToLineTimestamp -> {
                    if (session != null) {
                        onOpenDialog(ActiveDialog.GoToLineTimestamp)
                    } else {
                        ToastManager.warn("Open a log file to use Go To Line / Timestamp.")
                        setStatusMessage("Open a log file to use Go To Line / Timestamp.")
                    }
                }
                is MenuAction.ToggleTimelineDrawer -> tabCoordinator.openOrCreateTimelineTab()
                is MenuAction.ManageBookmarks -> {
                    if (tab != null) {
                        onOpenDialog(ActiveDialog.BookmarkManager)
                    } else {
                        ToastManager.warn("Open a log file or scratchpad to view bookmarks.")
                        setStatusMessage("Open a log file to view bookmarks.")
                    }
                }
                is MenuAction.ToggleBookmarkFilter -> toggleBookmarkFilter(tab)
                is MenuAction.TemporalRange -> {
                    if (tab != null && session != null) {
                        if (!session.isTemporalDataAvailable) {
                            ToastManager.info("No temporal timestamps detected in ${tab.title}")
                        }
                        tab.isTimeScrubberOpen = !tab.isTimeScrubberOpen
                    } else {
                        ToastManager.warn("Open a log file to use the temporal range scrubber.")
                        setStatusMessage("Open a log file to use the temporal range scrubber.")
                    }
                }
                is MenuAction.SetViewportMode -> setStatusMessage("Viewport layout: ${action.mode.displayLabel}")
                is MenuAction.ToggleView -> Unit
                is MenuAction.SetTheme -> Unit
                is MenuAction.OpenSettings -> onOpenDialog(ActiveDialog.Settings)
                is MenuAction.DiagnosticReport -> tabCoordinator.generateDiagnosticReport()
                is MenuAction.OpenLogsFolder -> umbrix.ui.util.openLogsDirectory()
                is MenuAction.KeyboardShortcuts -> onOpenDialog(ActiveDialog.KeyboardShortcuts)
                is MenuAction.About -> onOpenDialog(ActiveDialog.About)
                is MenuAction.Exit -> onExitRequest()
            }
        } catch (e: Throwable) {
            if (ForensicErrorReportGenerator.isFatal(e)) throw e
            umbrix.ui.error.WorkstationErrorHandler.reportException(e)
        }
    }

    fun toggleBookmarkFilter(tab: WorkspaceTab?) {
        if (tab != null) {
            if (tab.bookmarkManager.bookmarks.isEmpty()) {
                ToastManager.info("No bookmarks in ${tab.title} to filter")
            } else {
                val session = tab.session
                val willBeActive = !tab.isBookmarkFilterActive
                if (willBeActive) {
                    val currentPhysical = tab.selectedLineIndex ?: if (tab.isTemporalFocusActive) {
                        tab.temporalFocusStartLine + tab.topVisibleLine
                    } else {
                        tab.topVisibleLine
                    }
                    val bView = session?.createFilteredView(tab.bookmarkManager.toBitmap())
                    if (bView != null) {
                        val vIdx = bView.virtualIndexForPhysicalLine(currentPhysical)
                        val maxV = maxOf(0L, bView.totalFilteredLines() - tab.visibleRowCount)
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
                            val maxTop = maxOf(0L, session.totalLines() - tab.visibleRowCount)
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
                setStatusMessage(msg)
                ToastManager.info(msg)
            }
        } else {
            ToastManager.warn("Open a log file to use bookmark filtering.")
        }
    }

    fun toggleSearchFilterView(tab: WorkspaceTab?) {
        if (tab == null) return
        if (tab.isScratchpad) {
            if (tab.searchResult != null && !tab.searchResult!!.isEmpty) {
                tab.isFilteredView = !tab.isFilteredView
            }
            return
        }
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
                val virtualIndex = filteredView.virtualIndexForPhysicalLine(currentPhysical)
                val totalFiltered = filteredView.totalFilteredLines()
                val maxVirtualTop = maxOf(0L, totalFiltered - tab.visibleRowCount)
                tab.topVisibleLine = virtualIndex.coerceIn(0L, maxVirtualTop)
                val resolvedPhysical = filteredView.physicalLineForVirtualIndex(tab.topVisibleLine)
                tab.selectedLineIndex = resolvedPhysical
                tab.isFilteredView = true
            } else {
                val filteredView = session.createFilteredView(searchResult.matchingLines())
                val currentPhysical = tab.selectedLineIndex ?: filteredView.physicalLineForVirtualIndex(tab.topVisibleLine)
                if (tab.isTemporalFocusActive) {
                    val relativeTop = (currentPhysical - tab.temporalFocusStartLine).coerceIn(
                        0L,
                        maxOf(0L, (tab.temporalFocusEndLine - tab.temporalFocusStartLine + 1L) - tab.visibleRowCount)
                    )
                    tab.topVisibleLine = relativeTop
                } else {
                    val maxTop = maxOf(0L, session.totalLines() - tab.visibleRowCount)
                    tab.topVisibleLine = currentPhysical.coerceIn(0L, maxTop)
                }
                tab.selectedLineIndex = currentPhysical
                tab.isFilteredView = false
            }
        }
    }

    fun adjustZoom(deltaPt: Float) {
        val current = SettingsManager.currentSettings
        val updatedPt = (current.editorFontSizePt + deltaPt).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE)
        SettingsManager.updateSettings(current.copy(editorFontSizePt = updatedPt))
    }

    fun resetZoom() {
        val current = SettingsManager.currentSettings
        SettingsManager.updateSettings(current.copy(editorFontSizePt = FontSpec.DEFAULT_FONT_SIZE))
    }
}
