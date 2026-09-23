package umbrix.ui.launcher

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.header.IntegratedTitleBar
import umbrix.ui.screen.UmbrixMainScreen
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.theme.UmbrixColors
import umbrix.ui.theme.UmbrixTheme
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/**
 * Desktop Application composition root and entry point for Compose Desktop UI.
 * Initializes the global error handler, sets up the main application window with an
 * integrated IntelliJ-style dark header, routes keyboard preview events, enables drag-and-drop
 * file opening, and launches the Umbrix workstation theme and screen.
 */
object DesktopAppLauncher {

    @JvmStatic
    fun launch(args: Array<String>) {
        WorkstationErrorHandler.install()
        val initialFilePath: String? = args.firstOrNull { !it.startsWith("--") }

        try {
            application {
                val windowState = rememberWindowState(width = 1380.dp, height = 860.dp)

                Window(
                    onCloseRequest = {
                        WorkstationErrorHandler.isShuttingDown = true
                        exitApplication()
                    },
                    state = windowState,
                    title = "Umbrix — Industrial Log Workstation",
                    undecorated = true,
                    onPreviewKeyEvent = { keyEvent ->
                        WorkstationKeyDispatcher.handleKeyEvent(keyEvent)
                    }
                ) {
                    var droppedFilePath by remember { mutableStateOf<String?>(null) }
                    var activeDocTitle by remember { mutableStateOf<String?>(null) }
                    var isSettingsOpen by remember { mutableStateOf(false) }

                    DisposableEffect(window) {
                        val dropTarget = object : DropTarget() {
                            @Synchronized
                            override fun drop(evt: DropTargetDropEvent) {
                                try {
                                    evt.acceptDrop(DnDConstants.ACTION_COPY)
                                    val transferable = evt.transferable
                                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                        val droppedFiles = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                                        val firstFile = droppedFiles?.firstOrNull() as? File
                                        if (firstFile != null && firstFile.isFile) {
                                            droppedFilePath = firstFile.absolutePath
                                        }
                                    }
                                } catch (_: Throwable) {
                                    // Suppress DnD errors defensively
                                }
                            }
                        }
                        window.dropTarget = dropTarget
                        onDispose {
                            window.dropTarget = null
                        }
                    }

                    UmbrixTheme {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, UmbrixColors.surfaceElevated)
                        ) {
                            IntegratedTitleBar(
                                windowState = windowState,
                                activeDocumentTitle = activeDocTitle,
                                onOpenSettings = { isSettingsOpen = true },
                                onMinimize = { windowState.isMinimized = true },
                                onToggleMaximize = {
                                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                        WindowPlacement.Floating
                                    } else {
                                        WindowPlacement.Maximized
                                    }
                                },
                                onClose = {
                                    WorkstationErrorHandler.isShuttingDown = true
                                    exitApplication()
                                }
                            )

                            UmbrixMainScreen(
                                initialFilePath = initialFilePath,
                                droppedFilePath = droppedFilePath,
                                onClearDroppedFilePath = { droppedFilePath = null },
                                onActiveDocTitleChange = { activeDocTitle = it },
                                isSettingsDialogVisible = isSettingsOpen,
                                onSetSettingsDialogVisible = { isSettingsOpen = it }
                            )
                        }
                    }
                }
            }
        } finally {
            WorkstationErrorHandler.uninstall()
        }
    }
}
