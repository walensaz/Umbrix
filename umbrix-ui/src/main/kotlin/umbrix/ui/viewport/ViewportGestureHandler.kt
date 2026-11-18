package umbrix.ui.viewport

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
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
 * Pure fallback character offset mapping when exact Skia layout results are unavailable.
 */
internal fun fallbackCharOffset(
    localX: Float,
    dynamicCharWidthPx: Float,
    maxChars: Int = ViewportState.MAX_SHAPED_CHARS
): Int = if (dynamicCharWidthPx <= 0f || dynamicCharWidthPx.isNaN() || localX.isNaN()) {
    0
} else {
    (localX.coerceAtLeast(0f) / dynamicCharWidthPx).toInt().coerceIn(0, maxChars)
}

/**
 * Resolves fallback character offset with attached coordinates or viewport bounding box clamp.
 */
internal fun computeFallbackOffset(
    pointerX: Float,
    viewportWidthPx: Float,
    scrollX: Int,
    localX: Float?,
    dynamicCharWidthPx: Float,
    maxChars: Int = ViewportState.MAX_SHAPED_CHARS
): Int = if (localX != null) {
    fallbackCharOffset(localX, dynamicCharWidthPx, maxChars)
} else {
    val maxViewportWidth = if (viewportWidthPx.isNaN()) 0f else (viewportWidthPx - 1f).coerceAtLeast(0f)
    val clampedViewportX = if (pointerX.isNaN()) 0f else pointerX.coerceIn(0f, maxViewportWidth)
    val estimatedX = (clampedViewportX + scrollX.coerceAtLeast(0)).coerceAtLeast(0f)
    fallbackCharOffset(estimatedX, dynamicCharWidthPx, maxChars)
}

/**
 * Pure fixed-height row estimate used when live layout coordinates are unavailable.
 */
internal fun fallbackRowIndex(
    pointerY: Float,
    subLineOffsetPx: Float,
    rowHeightPx: Float,
    renderRowCount: Int
): Int = if (renderRowCount <= 0 || rowHeightPx <= 0f || rowHeightPx.isNaN() || pointerY.isNaN() || subLineOffsetPx.isNaN()) {
    0
} else {
    ((pointerY + subLineOffsetPx) / rowHeightPx).toInt().coerceIn(0, renderRowCount - 1)
}

/**
 * Allocation-free row resolver over an ordered sequence of row top edges.
 * [rowTopAt] returns the viewport-space top Y of rendered row k, or NaN when that row has no attached layout.
 * Returns -1 when no row is measurable so callers can use the fixed-height fallback.
 */
internal inline fun resolveRowByTops(
    pointerY: Float,
    renderRowCount: Int,
    rowTopAt: (Int) -> Float
): Int = run {
    var resolvedRow = -1
    var rowIndex = 0
    var searching = !pointerY.isNaN()
    while (searching && rowIndex < renderRowCount) {
        val rowTop = rowTopAt(rowIndex)
        if (!rowTop.isNaN()) {
            if (resolvedRow < 0) {
                // Pointer above the first measurable row clamps to it.
                resolvedRow = rowIndex
            }
            if (pointerY >= rowTop) {
                resolvedRow = rowIndex
            } else {
                searching = false
            }
        }
        rowIndex++
    }
    resolvedRow
}

/**
 * Resolves the rendered row index under [pointerY] (viewport space) from live row coordinates, so wrapped
 * (Full Wrap) and expanded rows of variable height hit-test correctly. Falls back to fixed-height arithmetic
 * when coordinates are missing or detached. Never throws.
 */
internal fun resolveRowAtPointer(
    pointerY: Float,
    subLineOffsetPx: Float,
    rowHeightPx: Float,
    renderRowCount: Int,
    textLayoutResults: Map<Int, RowTextLayout>,
    viewportCoordinates: LayoutCoordinates?
): Int = run {
    val liveRow = if (viewportCoordinates != null && viewportCoordinates.isAttached && renderRowCount > 0 && textLayoutResults.isNotEmpty()) {
        resolveRowByTops(pointerY, renderRowCount) { rowIndex ->
            val entry = textLayoutResults[rowIndex]
            if (entry != null && entry.coordinates.isAttached) {
                try {
                    viewportCoordinates.localPositionOf(entry.coordinates, Offset.Zero).y
                } catch (_: Throwable) {
                    Float.NaN
                }
            } else {
                Float.NaN
            }
        }
    } else {
        -1
    }
    if (liveRow >= 0) liveRow else fallbackRowIndex(pointerY, subLineOffsetPx, rowHeightPx, renderRowCount)
}

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
    dynamicCharWidthPx: Float,
    horizontalScrollState: ScrollState,
    coroutineScope: CoroutineScope,
    textLayoutResults: Map<Int, RowTextLayout>,
    viewportCoordinates: () -> LayoutCoordinates?,
    isFullWrap: Boolean = false,
    isExpandable: Boolean = true
): Modifier = this.pointerInput(tab, totalLinesCount, searchResult, isFilteredMode, isTemporalMode, temporalStartLine, rowHeightPx, isFullWrap, isExpandable, textLayoutResults) {
    val clicks = ClickSequence()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (totalLinesCount > 0L && currentEvent.buttons.isPrimaryPressed) {
            var pointerPosition = down.position
            val viewportWidthPx = size.width.toFloat()
            val viewportHeightPx = size.height.toFloat()

            fun updateSelectionEndpoint(): TextPosition = run {
                val clampedPointerPosition = Offset(
                    x = pointerPosition.x.coerceIn(0f, (viewportWidthPx - 1f).coerceAtLeast(0f)),
                    y = pointerPosition.y.coerceIn(0f, (viewportHeightPx - 1f).coerceAtLeast(0f))
                )
                val currentViewportCoords = viewportCoordinates()
                val rowIndex = resolveRowAtPointer(
                    pointerY = clampedPointerPosition.y,
                    subLineOffsetPx = tab.subLineScrollOffsetPx,
                    rowHeightPx = rowHeightPx,
                    renderRowCount = renderRowCount,
                    textLayoutResults = textLayoutResults,
                    viewportCoordinates = currentViewportCoords
                )
                val virtualRow = if (totalLinesCount > 0L) {
                    ViewportCoordinateMapper.resolveVirtualRow(tab.topVisibleLine, rowIndex, totalLinesCount)
                        .let { VirtualRowIndex(it.value.coerceIn(0L, totalLinesCount - 1L)) }
                } else {
                    VirtualRowIndex(0L)
                }
                val physicalLine = ViewportCoordinateMapper.resolvePhysicalLine(
                    virtualRow = virtualRow,
                    totalLinesCount = totalLinesCount,
                    isFilteredMode = isFilteredMode,
                    filteredView = filteredView,
                    isTemporalMode = isTemporalMode,
                    temporalStartLine = temporalStartLine
                ).value

                val entry = textLayoutResults[rowIndex]?.takeIf {
                    it.virtualRow == virtualRow.value && it.physicalLine == physicalLine
                }
                val character = if (entry != null && entry.coordinates.isAttached && currentViewportCoords != null && currentViewportCoords.isAttached) {
                    val localPosition = try {
                        entry.coordinates.localPositionOf(currentViewportCoords, clampedPointerPosition)
                    } catch (_: Throwable) {
                        null
                    }
                    if (localPosition != null) {
                        val yInRow = if (isFullWrap) {
                            localPosition.y.coerceIn(0f, (entry.layout.size.height - 1f).coerceAtLeast(0f))
                        } else {
                            rowHeightPx / 2f
                        }
                        entry.layout.getOffsetForPosition(Offset(localPosition.x, yInRow)).coerceIn(0, ViewportState.MAX_SHAPED_CHARS)
                    } else {
                        val effectiveScrollX = if (isFullWrap) 0 else horizontalScrollState.value
                        computeFallbackOffset(
                            pointerX = clampedPointerPosition.x,
                            viewportWidthPx = viewportWidthPx,
                            scrollX = effectiveScrollX,
                            localX = null,
                            dynamicCharWidthPx = dynamicCharWidthPx
                        )
                    }
                } else {
                    val localX = if (entry != null && entry.coordinates.isAttached && currentViewportCoords != null && currentViewportCoords.isAttached) {
                        try {
                            entry.coordinates.localPositionOf(currentViewportCoords, clampedPointerPosition).x
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }
                    val effectiveScrollX = if (isFullWrap) 0 else horizontalScrollState.value
                    computeFallbackOffset(
                        pointerX = clampedPointerPosition.x,
                        viewportWidthPx = viewportWidthPx,
                        scrollX = effectiveScrollX,
                        localX = localX,
                        dynamicCharWidthPx = dynamicCharWidthPx
                    )
                }
                TextPosition(virtualRow.value, character)
            }

            val anchor = updateSelectionEndpoint()
            val clickedVirtualRow = anchor.lineIndex
            val clickedPhysicalLine = ViewportCoordinateMapper.resolvePhysicalLine(
                virtualRow = VirtualRowIndex(clickedVirtualRow),
                totalLinesCount = totalLinesCount,
                isFilteredMode = isFilteredMode,
                filteredView = filteredView,
                isTemporalMode = isTemporalMode,
                temporalStartLine = temporalStartLine
            ).value
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
            fun displayedTextAt(virtualRow: Long): String = run {
                if (virtualRow != cachedTextRow) {
                    val physicalLine = ViewportCoordinateMapper.resolvePhysicalLine(
                        virtualRow = VirtualRowIndex(virtualRow),
                        totalLinesCount = totalLinesCount,
                        isFilteredMode = isFilteredMode,
                        filteredView = filteredView,
                        isTemporalMode = isTemporalMode,
                        temporalStartLine = temporalStartLine
                    ).value
                    cachedText = if (physicalLine >= 0L) {
                        if (isFilteredMode && filteredView != null) {
                            filteredView.getVirtualLineText(virtualRow, ViewportState.MAX_SHAPED_CHARS)
                        } else {
                            session.getLineText(physicalLine, ViewportState.MAX_SHAPED_CHARS)
                        }
                    } else {
                        ""
                    }
                    cachedTextRow = virtualRow
                }
                cachedText
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
                            if (!isFullWrap && horizontalVelocity != 0f) {
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
