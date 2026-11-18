package umbrix.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import umbrix.ui.dialog.ActiveDialog
import umbrix.ui.docking.DockTreeManager
import umbrix.ui.menu.MenuAction
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.toast.ToastManager

/**
 * Standardized global workstation keyboard shortcut router and preview dispatcher.
 */
@Composable
fun BindWorkstationKeyHandler(
    activeDialog: ActiveDialog,
    onActiveDialogChange: (ActiveDialog) -> Unit,
    tabCoordinator: MainScreenTabCoordinator,
    menuCoordinator: MainScreenMenuCoordinator,
    isSearchBarVisible: Boolean,
    onSetSearchBarVisible: (Boolean) -> Unit,
    onTriggerSearchFocus: () -> Unit,
    onTriggerSearch: (String, Boolean) -> Unit,
    onNavigateSearch: (Boolean) -> Unit,
    isAiAssistantOpen: Boolean,
    onSetAiAssistantOpen: (Boolean) -> Unit,
    focusManager: FocusManager
) {
    DisposableEffect(
        activeDialog,
        isSearchBarVisible,
        isAiAssistantOpen,
        tabCoordinator.openTabs.size
    ) {
        var lastShiftPressTimeMs = 0L

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
                    onActiveDialogChange(ActiveDialog.CommandPalette(""))
                    true
                } else {
                    when {
                        // Command Palette shortcuts
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.P -> {
                            onActiveDialogChange(ActiveDialog.CommandPalette(">"))
                            true
                        }
                        isCtrlOrCmd && !keyEvent.isShiftPressed && keyEvent.key == Key.P -> {
                            onActiveDialogChange(ActiveDialog.CommandPalette(""))
                            true
                        }
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.A -> {
                            onActiveDialogChange(ActiveDialog.CommandPalette(">"))
                            true
                        }
                        !isCtrlOrCmd && !keyEvent.isAltPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Semicolon &&
                            SettingsManager.currentSettings.enableVimColonTrigger &&
                            activeDialog == ActiveDialog.None && !isSearchBarVisible -> {
                            onActiveDialogChange(ActiveDialog.CommandPalette(":"))
                            true
                        }

                        // Help / Shortcuts
                        keyEvent.key == Key.F1 || (isCtrlOrCmd && keyEvent.key == Key.Slash) -> {
                            onActiveDialogChange(ActiveDialog.KeyboardShortcuts)
                            true
                        }

                        // Export Sub-file (Ctrl+Shift+S)
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.S -> {
                            if (tabCoordinator.activeSession() != null) {
                                onActiveDialogChange(ActiveDialog.ExportSubFile)
                                true
                            } else false
                        }

                        // Settings (Ctrl+,)
                        isCtrlOrCmd && keyEvent.key == Key.Comma -> {
                            onActiveDialogChange(ActiveDialog.Settings)
                            true
                        }

                        // Zoom In / Out / Reset
                        isCtrlOrCmd && (keyEvent.key == Key.Plus || keyEvent.key == Key.Equals || keyEvent.key == Key.NumPadAdd) -> {
                            menuCoordinator.adjustZoom(1.0f)
                            true
                        }
                        isCtrlOrCmd && (keyEvent.key == Key.Minus || keyEvent.key == Key.NumPadSubtract) -> {
                            menuCoordinator.adjustZoom(-1.0f)
                            true
                        }
                        isCtrlOrCmd && (keyEvent.key == Key.Zero || keyEvent.key == Key.NumPad0) -> {
                            menuCoordinator.resetZoom()
                            true
                        }

                        // Toggle Bookmark Filter (Ctrl+Alt+B)
                        isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.B -> {
                            menuCoordinator.toggleBookmarkFilter(tabCoordinator.currentTab())
                            true
                        }

                        // Open Scratchpad Manager (Ctrl+Alt+S)
                        isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.S -> {
                            onActiveDialogChange(ActiveDialog.ScratchpadManager)
                            true
                        }

                        // Open File (Ctrl+O)
                        isCtrlOrCmd && keyEvent.key == Key.O -> {
                            tabCoordinator.openFileViaNativeDialog()
                            true
                        }

                        // Find (Ctrl+F)
                        isCtrlOrCmd && keyEvent.key == Key.F -> {
                            onSetSearchBarVisible(true)
                            onTriggerSearchFocus()
                            true
                        }

                        // Go to Line / Timestamp (Ctrl+G)
                        isCtrlOrCmd && keyEvent.key == Key.G -> {
                            if (tabCoordinator.activeSession() != null) {
                                onActiveDialogChange(ActiveDialog.GoToLineTimestamp)
                                true
                            } else false
                        }

                        // Time Scrubber (Ctrl+T)
                        isCtrlOrCmd && keyEvent.key == Key.T -> {
                            val tab = tabCoordinator.currentTab()
                            if (tab != null && tabCoordinator.activeSession() != null) {
                                tab.isTimeScrubberOpen = !tab.isTimeScrubberOpen
                                true
                            } else false
                        }

                        // Copy selection or line (Ctrl+C)
                        isCtrlOrCmd && keyEvent.key == Key.C -> {
                            tabCoordinator.copySelectionOrLineToClipboard()
                        }

                        // Incident Timeline (Ctrl+Shift+T) - UX Bugfix: open dockable tab
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.T -> {
                            tabCoordinator.openOrCreateTimelineTab()
                            true
                        }

                        // AI Assistant Drawer (Ctrl+Shift+I)
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.I -> {
                            onSetAiAssistantOpen(!isAiAssistantOpen)
                            true
                        }

                        // Close active tab (Ctrl+W)
                        isCtrlOrCmd && !keyEvent.isShiftPressed && keyEvent.key == Key.W -> {
                            tabCoordinator.closeCurrentTab()
                            true
                        }

                        // Close active pane (Ctrl+Shift+W)
                        isCtrlOrCmd && keyEvent.isShiftPressed && keyEvent.key == Key.W -> {
                            val currentTab = tabCoordinator.currentTab()
                            if (currentTab != null) {
                                tabCoordinator.closeTabById(currentTab.id)
                                true
                            } else false
                        }

                        // New scratchpad (Ctrl+N)
                        isCtrlOrCmd && !keyEvent.isAltPressed && !keyEvent.isShiftPressed && keyEvent.key == Key.N -> {
                            tabCoordinator.createNewScratchpadTab()
                            true
                        }

                        // Split pane right (Ctrl+\)
                        isCtrlOrCmd && !keyEvent.isAltPressed && keyEvent.key == Key.Backslash -> {
                            tabCoordinator.splitActiveRight()
                            true
                        }

                        // Split pane down (Ctrl+Alt+\)
                        isCtrlOrCmd && keyEvent.isAltPressed && keyEvent.key == Key.Backslash -> {
                            tabCoordinator.splitActiveDown()
                            true
                        }

                        // Focus next pane (F6)
                        keyEvent.key == Key.F6 -> {
                            tabCoordinator.focusNextPane()
                            true
                        }

                        // Bookmark toggle on current line (Ctrl+B)
                        isCtrlOrCmd && keyEvent.key == Key.B -> {
                            tabCoordinator.toggleBookmarkOnCurrentLine()
                            true
                        }

                        // Bookmark navigation (F2 / Shift+F2)
                        keyEvent.key == Key.F2 -> {
                            tabCoordinator.navigateBookmark(forward = !keyEvent.isShiftPressed)
                            true
                        }

                        // Search navigation (F3 / Shift+F3)
                        keyEvent.key == Key.F3 -> {
                            onNavigateSearch(!keyEvent.isShiftPressed)
                            true
                        }

                        // Anomaly navigation (F4 / Shift+F4)
                        keyEvent.key == Key.F4 -> {
                            tabCoordinator.navigateAnomaly(forward = !keyEvent.isShiftPressed)
                            true
                        }

                        // Escape key handler - complete & robust
                        keyEvent.key == Key.Escape -> {
                            if (activeDialog != ActiveDialog.None) {
                                onActiveDialogChange(ActiveDialog.None)
                                true
                            } else if (isSearchBarVisible) {
                                onSetSearchBarVisible(false)
                                focusManager.clearFocus()
                                onTriggerSearch("", false)
                                true
                            } else {
                                val currentTab = tabCoordinator.currentTab()
                                if (currentTab?.isTimeScrubberOpen == true) {
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
}
