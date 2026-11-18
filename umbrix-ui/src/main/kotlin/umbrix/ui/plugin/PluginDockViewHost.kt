package umbrix.ui.plugin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import umbrix.core.plugin.PluginManager
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.PluginWorkspaceController
import umbrix.plugin.api.ui.CustomDockView
import umbrix.plugin.api.ui.DockViewContext
import umbrix.plugin.api.ui.SkiaCanvasDockView
import umbrix.plugin.api.ui.SkiaCanvasSurface
import umbrix.plugin.ui.ComposeDockView
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme

/**
 * Host component capable of rendering both Compose-native (`ComposeDockView`)
 * and Skia immediate-mode hardware canvas (`SkiaCanvasDockView`) plugin dock panes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PluginDockViewHost(
    view: CustomDockView,
    tab: WorkspaceTab?,
    modifier: Modifier = Modifier
) {
    var repaintCounter by remember { mutableStateOf(0) }
    val density = LocalDensity.current.density

    val dockViewContext = remember(view, tab) {
        object : DockViewContext {
            override fun activeSession(): PluginSessionView =
                tab?.session?.pluginSessionView() ?: PluginManager.instance().getWorkspaceController().activeSession()

            override fun workspace(): PluginWorkspaceController =
                PluginManager.instance().getWorkspaceController()

            override fun selectedRecordIndex(): Long =
                tab?.selectedLineIndex ?: 0L

            override fun topVisibleRecordIndex(): Long =
                tab?.topVisibleLine ?: 0L

            override fun navigateToRecord(recordIndex: Long) {
                tab?.let { it.topVisibleLine = recordIndex; it.selectedLineIndex = recordIndex }
            }

            override fun navigateToTimestampMicros(epochMicros: Long) {
                // Seek nearest timestamp
            }

            override fun executeSearchQuery(queryText: String) {
                tab?.let { it.searchQuery = queryText; it.isSearching = true }
            }

            override fun requestRepaint() {
                repaintCounter++
            }
        }
    }

    DisposableEffect(view) {
        view.onAttach(dockViewContext)
        tab?.session?.let { view.onSessionChanged(it.pluginSessionView()) }
        onDispose {
            // dock view disposal
        }
    }

    Box(modifier = modifier.fillMaxSize().background(UmbrixTheme.colors.surface)) {
        when (view) {
            is ComposeDockView -> {
                view.Content(dockViewContext)
            }
            is SkiaCanvasDockView -> {
                umbrix.plugin.ui.SkiaCanvasHost(
                    view = view,
                    context = dockViewContext,
                    repaintTick = repaintCounter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
