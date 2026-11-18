package umbrix.ui.viewport

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.FontSpec
import kotlin.math.max

/**
 * Attaches mouse-wheel scroll (zoom, horizontal shift-scroll, and vertical sub-line scroll)
 * and keyboard arrow/page/home/end navigation to the log viewport container.
 *
 * Kept outside `Modifier.pointerInput(Unit)` so recompositions always supply fresh
 * `totalLinesCount`, `visibleRowCount`, and `rowHeightPx`.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.viewportScrollAndKeyInput(
    tab: WorkspaceTab,
    totalLinesCount: Long,
    visibleRowCount: Int,
    rowHeightPx: Float,
    horizontalScrollState: ScrollState,
    coroutineScope: CoroutineScope
): Modifier = this
    .onPointerEvent(PointerEventType.Scroll) { event ->
        val change = event.changes.firstOrNull()
        val scrollDeltaY = change?.scrollDelta?.y ?: 0f
        val scrollDeltaX = change?.scrollDelta?.x ?: 0f
        val isShift = event.keyboardModifiers.isPointerShiftPressed
        val isCtrlOrCmd = event.keyboardModifiers.isPointerCtrlPressed || event.keyboardModifiers.isPointerMetaPressed

        if (isCtrlOrCmd && scrollDeltaY != 0f) {
            change?.consume()
            val delta = if (scrollDeltaY < 0f) 0.5f else -0.5f
            val current = SettingsManager.currentSettings
            val updatedPt = (current.editorFontSizePt + delta).coerceIn(
                FontSpec.MIN_FONT_SIZE,
                FontSpec.MAX_FONT_SIZE
            )
            SettingsManager.updateSettings(current.copy(editorFontSizePt = updatedPt))
        } else if (isShift && scrollDeltaY != 0f) {
            change?.consume()
            coroutineScope.launch {
                horizontalScrollState.scrollBy(scrollDeltaY * 60f)
            }
        } else if (scrollDeltaX != 0f) {
            change?.consume()
            coroutineScope.launch {
                horizontalScrollState.scrollBy(scrollDeltaX * 60f)
            }
        } else if (scrollDeltaY != 0f) {
            change?.consume()
            val (nextTop, nextOffset) = SelectionEdgeScroll.advanceVertical(
                top = tab.topVisibleLine,
                subLinePx = tab.subLineScrollOffsetPx,
                deltaPx = scrollDeltaY * 26f,
                rowHeightPx = rowHeightPx,
                totalLines = totalLinesCount,
                visibleRows = visibleRowCount
            )
            tab.topVisibleLine = nextTop
            tab.subLineScrollOffsetPx = nextOffset
        }
    }
    .onKeyEvent { keyEvent: KeyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
            val maxTop = max(0L, totalLinesCount - visibleRowCount)
            when (keyEvent.key) {
                Key.DirectionLeft -> {
                    coroutineScope.launch { horizontalScrollState.scrollBy(-100f) }
                    true
                }
                Key.DirectionRight -> {
                    coroutineScope.launch { horizontalScrollState.scrollBy(100f) }
                    true
                }
                Key.DirectionUp -> {
                    tab.subLineScrollOffsetPx = 0f
                    tab.topVisibleLine = (tab.topVisibleLine - 1L).coerceAtLeast(0L)
                    true
                }
                Key.DirectionDown -> {
                    tab.subLineScrollOffsetPx = 0f
                    tab.topVisibleLine = (tab.topVisibleLine + 1L).coerceIn(0L, maxTop)
                    true
                }
                Key.PageUp -> {
                    tab.subLineScrollOffsetPx = 0f
                    tab.topVisibleLine = (tab.topVisibleLine - visibleRowCount.toLong()).coerceAtLeast(0L)
                    true
                }
                Key.PageDown -> {
                    tab.subLineScrollOffsetPx = 0f
                    tab.topVisibleLine = (tab.topVisibleLine + visibleRowCount.toLong()).coerceIn(0L, maxTop)
                    true
                }
                Key.MoveHome -> {
                    coroutineScope.launch { horizontalScrollState.scrollTo(0) }
                    true
                }
                Key.MoveEnd -> {
                    coroutineScope.launch { horizontalScrollState.scrollTo(horizontalScrollState.maxValue) }
                    true
                }
                else -> false
            }
        } else {
            false
        }
    }
