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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import umbrix.ui.error.WorkstationErrorHandler
import umbrix.ui.header.IntegratedTitleBar
import umbrix.ui.menu.MenuAction
import umbrix.ui.screen.UmbrixMainScreen
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkstationKeyDispatcher
import umbrix.ui.theme.ThemeRegistry
import umbrix.ui.theme.UmbrixTheme
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
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
                val defaultConfiguration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration
                val startupWorkArea = DesktopWindowGeometry.usableBounds(
                    defaultConfiguration.bounds,
                    Toolkit.getDefaultToolkit().getScreenInsets(defaultConfiguration)
                )
                val startupWindowSize = DesktopWindowGeometry.initialSize(startupWorkArea)
                val windowState = rememberWindowState(
                    width = startupWindowSize.width.dp,
                    height = startupWindowSize.height.dp
                )

                Window(
                    onCloseRequest = {
                        WorkstationErrorHandler.isShuttingDown = true
                        exitApplication()
                    },
                    state = windowState,
                    title = "Umbrix — Industrial Log Workstation",
                    icon = umbrix.ui.icon.UmbrixIcon.painter,
                    undecorated = true,
                    onPreviewKeyEvent = { keyEvent ->
                        WorkstationKeyDispatcher.handleKeyEvent(keyEvent)
                    }
                ) {
                    var droppedFilePath by remember { mutableStateOf<String?>(null) }
                    var activeDocTitle by remember { mutableStateOf<String?>(null) }
                    var isSettingsOpen by remember { mutableStateOf(false) }
                    // Title bar (producer) and main screen (consumer) are siblings; a channel decouples them
                    val menuActionChannel = remember { Channel<MenuAction>(Channel.UNLIMITED) }
                    val menuActionFlow = remember(menuActionChannel) { menuActionChannel.receiveAsFlow() }

                    DisposableEffect(window) {
                        umbrix.ui.icon.UmbrixIcon.applyToWindow(window)
                        window.maximizedBounds = DesktopWindowGeometry.usableBounds(
                            window.graphicsConfiguration.bounds,
                            Toolkit.getDefaultToolkit().getScreenInsets(window.graphicsConfiguration)
                        )
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

                    val currentTheme = ThemeRegistry.findByName(SettingsManager.currentSettings.themeName)
                        ?: ThemeRegistry.defaultTheme()

                    UmbrixTheme(
                        theme = currentTheme,
                        fonts = SettingsManager.currentSettings.toFontSpec()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, UmbrixTheme.colors.border)
                        ) {
                            IntegratedTitleBar(
                                windowState = windowState,
                                activeDocumentTitle = activeDocTitle,
                                settings = SettingsManager.currentSettings,
                                onMenuAction = { menuAction -> menuActionChannel.trySend(menuAction) },
                                onMinimize = { windowState.isMinimized = true },
                                onToggleMaximize = {
                                    window.maximizedBounds = DesktopWindowGeometry.usableBounds(
                                        window.graphicsConfiguration.bounds,
                                        Toolkit.getDefaultToolkit().getScreenInsets(window.graphicsConfiguration)
                                    )
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
                                onSetSettingsDialogVisible = { isSettingsOpen = it },
                                menuActions = menuActionFlow,
                                onExitRequest = {
                                    WorkstationErrorHandler.isShuttingDown = true
                                    exitApplication()
                                }
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
