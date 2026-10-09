package umbrix.ui.viewport

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.core.view.FilteredLineView
import umbrix.ui.selection.ClickSequence
import umbrix.ui.selection.ClickUnit
import umbrix.ui.selection.TextPosition
import umbrix.ui.selection.clickSpan
import umbrix.ui.selection.extendClickSelection
import umbrix.ui.state.WorkspaceTab
import kotlin.math.abs

/**
 * Robust gesture detector handling text selection, multi-click word/line expansions,
 * and edge-velocity scrolling without phantom horizontal scrolling during stationary clicks.
 */
fun Modifier.viewportPointerGestures(
    tab: WorkspaceTab,
    session: LogSession,
    totalLinesCount: Long,
    searchResult: SearchResult?,
    isFilteredMode: Boolean,
    filteredView: FilteredLineView?,
    isTemporalMode: Boolean,
    temporalStartLine: Long,
    renderRowCount: Int,
    rowHeightPx: Float,
    visibleRowCount: Int,
    density: Density,
    dynamicCharWidthPx: Float,
    horizontalScrollState: ScrollState,
    coroutineScope: CoroutineScope,
    textLayoutResults: Map<Int, Triple<Long, Long, androidx.compose.ui.text.TextLayoutResult>>
): Modifier = this.pointerInput(tab, totalLinesCount, searchResult, isFilteredMode, isTemporalMode, temporalStartLine, rowHeightPx) {
    val clicks = ClickSequence()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (totalLinesCount > 0L && currentEvent.buttons.isPrimaryPressed) {
            val paddingStartPx = with(density) { 8.dp.toPx() }
            var pointerPosition = down.position
            val viewportWidthPx = size.width.toFloat()
            val viewportHeightPx = size.height.toFloat()

            fun updateSelectionEndpoint(): TextPosition {
                val x = (pointerPosition.x.coerceIn(0f, (viewportWidthPx - 1f).coerceAtLeast(0f)) +
                    horizontalScrollState.value - paddingStartPx).coerceAtLeast(0f)
                val y = pointerPosition.y.coerceIn(0f, (viewportHeightPx - 1f).coerceAtLeast(0f)) +
                    tab.subLineScrollOffsetPx
                val rowIndex = (y / rowHeightPx).toInt().coerceIn(0, renderRowCount - 1)
                val virtualRow = SelectionEdgeScroll.virtualRowAt(tab.topVisibleLine, rowIndex, totalLinesCount)
                    .coerceIn(0L, totalLinesCount - 1L)
                val physicalLine = when {
                    isFilteredMode && filteredView != null -> (filteredView.physicalLineForVirtualIndex(virtualRow) as Number).toLong()
                    isTemporalMode -> temporalStartLine + virtualRow
                    else -> virtualRow
                }
                val layout = textLayoutResults[rowIndex]?.takeIf {
                    it.first == virtualRow && it.second == physicalLine
                }?.third
                val character = if (layout != null) {
                    layout.getOffsetForPosition(Offset(x, rowHeightPx / 2f))
                } else {
                    (x / dynamicCharWidthPx).toInt().coerceIn(0, ViewportState.MAX_SHAPED_CHARS)
                }
                return TextPosition(virtualRow, character)
            }

            val anchor = updateSelectionEndpoint()
            val clickedVirtualRow = anchor.lineIndex
            val clickedPhysicalLine = when {
                isFilteredMode && filteredView != null -> (filteredView.physicalLineForVirtualIndex(clickedVirtualRow) as Number).toLong()
                isTemporalMode -> temporalStartLine + clickedVirtualRow
                else -> clickedVirtualRow
            }
            val clickCount = clicks.press(
                down.uptimeMillis, down.position.x, down.position.y, anchor.lineIndex,
                viewConfiguration.doubleTapTimeoutMillis.toLong(), viewConfiguration.touchSlop
            )
            val unit = when (clickCount) {
                2 -> ClickUnit.WORD
                3 -> ClickUnit.LINE
                else -> ClickUnit.CHARACTER
            }
            var cachedTextRow = -1L
            var cachedText = ""
            fun displayedTextAt(virtualRow: Long): String {
                if (virtualRow != cachedTextRow) {
                    val physicalLine: Long = when {
                        isFilteredMode && filteredView != null -> (filteredView.physicalLineForVirtualIndex(virtualRow) as Number).toLong()
                        isTemporalMode -> temporalStartLine + virtualRow
                        else -> virtualRow
                    }
                    cachedText = if (physicalLine >= 0L) {
                        if (isFilteredMode && filteredView != null) {
                            filteredView.getVirtualLineText(virtualRow, ViewportState.MAX_SHAPED_CHARS)
                        } else session.getLineText(physicalLine, ViewportState.MAX_SHAPED_CHARS)
                    } else ""
                    cachedTextRow = virtualRow
                }
                return cachedText
            }
            val span = if (unit != ClickUnit.CHARACTER) {
                clickSpan(displayedTextAt(anchor.lineIndex), anchor.charOffset, unit)
            } else null
            val initialStart = if (span != null) TextPosition(anchor.lineIndex, span.start) else anchor
            val initialEnd = if (span != null) TextPosition(anchor.lineIndex, span.endExclusive) else anchor
            tab.selectionState.startPosition = initialStart
            tab.selectionState.endPosition = initialEnd

            fun refreshSelection() {
                val target = updateSelectionEndpoint()
                if (span != null) {
                    val (start, end) = extendClickSelection(
                        anchor.lineIndex, span, target, displayedTextAt(target.lineIndex), unit
                    )
                    tab.selectionState.startPosition = start
                    tab.selectionState.endPosition = end
                } else {
                    tab.selectionState.endPosition = target
                }
            }

            var scrollJob: Job? = null

            fun ensureScrollJobStarted() {
                if (scrollJob == null || !scrollJob!!.isActive) {
                    tab.selectionState.isDraggingSelection = true
                    scrollJob = coroutineScope.launch {
                        var lastFrameNanos = withFrameNanos { it }
                        while (isActive && tab.selectionState.isDraggingSelection) {
                            val frameNanos = withFrameNanos { it }
                            val elapsedSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                            lastFrameNanos = frameNanos
                            val horizontalVelocity = SelectionEdgeScroll.edgeVelocity(pointerPosition.x, viewportWidthPx)
                            val verticalVelocity = SelectionEdgeScroll.edgeVelocity(pointerPosition.y, viewportHeightPx)
                            if (horizontalVelocity != 0f) {
                                val horizontalTarget = SelectionEdgeScroll.clampHorizontal(
                                    horizontalScrollState.value.toFloat(), horizontalVelocity * elapsedSeconds,
                                    horizontalScrollState.maxValue
                                )
                                horizontalScrollState.dispatchRawDelta(horizontalTarget - horizontalScrollState.value)
                            }
                            if (verticalVelocity != 0f) {
                                val (newTop, newOffset) = SelectionEdgeScroll.advanceVertical(
                                    tab.topVisibleLine, tab.subLineScrollOffsetPx, verticalVelocity * elapsedSeconds,
                                    rowHeightPx, totalLinesCount, visibleRowCount
                                )
                                tab.topVisibleLine = newTop
                                tab.subLineScrollOffsetPx = newOffset
                            }
                            refreshSelection()
                        }
                    }
                }
            }

            // Word/Line multi-click selections immediately mark selection
            if (unit != ClickUnit.CHARACTER) {
                tab.selectionState.isDraggingSelection = true
            }

            down.consume()
            var moved = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    if (!moved) {
                        val dx = abs(change.position.x - down.position.x)
                        val dy = abs(change.position.y - down.position.y)
                        if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                            moved = true
                            ensureScrollJobStarted()
                        }
                    }
                    if (moved) {
                        pointerPosition = change.position
                        refreshSelection()
                    }
                    if (event.type != PointerEventType.Scroll) change.consume()
                }

                if (!moved && released) {
                    if (clickedPhysicalLine >= 0L) {
                        tab.selectedLineIndex = clickedPhysicalLine
                    }
                    if (unit == ClickUnit.CHARACTER) {
                        tab.selectionState.clearSelection()
                    }
                }
            } finally {
                if (moved || !released) {
                    clicks.cancel()
                }
                tab.selectionState.isDraggingSelection = false
                scrollJob?.cancel()
            }
        } else {
            clicks.cancel()
        }
    }
}
