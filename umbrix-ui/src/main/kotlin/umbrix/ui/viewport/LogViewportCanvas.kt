package umbrix.ui.viewport

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import java.awt.Cursor
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.view.FilteredLineView
import umbrix.ui.search.applySelectionHighlight
import umbrix.ui.search.buildHighlightedLine
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.selection.TextPosition
import umbrix.ui.selection.ClickSequence
import umbrix.ui.selection.ClickUnit
import umbrix.ui.selection.clickSpan
import umbrix.ui.selection.extendClickSelection
import umbrix.ui.settings.SettingsManager
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.EditorTypography
import umbrix.ui.theme.FontSpec
import umbrix.ui.theme.UmbrixFonts
import umbrix.ui.theme.UmbrixTheme
import kotlin.math.max

/**
 * High-performance 64-bit log viewport canvas rendering hardware-accelerated text rows,
 * gutter line numbers, active search needle highlights, and 64-bit virtual scrolling.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogViewportCanvas(
    tab: WorkspaceTab?,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val colors = UmbrixTheme.colors
    val fonts = UmbrixFonts.current

    val editorFontFamily = remember(fonts.editorFontFamily) {
        EditorTypography.resolvedEditorFontFamily(fonts.editorFontFamily)
    }
    val fontFeatureSettings = remember(fonts.enableLigatures) {
        EditorTypography.resolveFontFeatureSettings(fonts.enableLigatures)
    }

    BoxWithConstraints(modifier = modifier) {
        val canvasMaxWidth = maxWidth
        val session = tab?.session
        val searchResult = tab?.searchResult
        val isFilteredMode = tab?.isFilteredView == true && searchResult != null
        val isTemporalMode = tab?.isTemporalFocusActive == true && !isFilteredMode

        val filteredView: FilteredLineView? = if (isFilteredMode && session != null) {
            session.createFilteredView(searchResult!!.matchingLines())
        } else null

        val temporalStartLine = if (isTemporalMode) tab!!.temporalFocusStartLine else 0L
        val temporalEndLine = if (isTemporalMode) tab!!.temporalFocusEndLine else (session?.totalLines() ?: 0L) - 1L

        val totalLinesCount = when {
            isFilteredMode && filteredView != null -> filteredView.totalFilteredLines()
            isTemporalMode -> max(0L, temporalEndLine - temporalStartLine + 1L)
            else -> session?.totalLines() ?: 0L
        }

        val rowHeightDp = (fonts.editorFontSizePt * fonts.lineHeightMultiplier + 4.0f).dp
        val rowHeightPx = (fonts.editorFontSizePt * fonts.lineHeightMultiplier + 4.0f) * density.density
        val visibleRowCount = ((maxHeight - 12.dp) / rowHeightDp).toInt().coerceAtLeast(1)
        val renderRowCount = visibleRowCount + 2

        // Publish so UmbrixMainScreen centering math can offset topVisibleLine by half this count.
        if (tab != null && tab.visibleRowCount != visibleRowCount) {
            tab.visibleRowCount = visibleRowCount
        }

        // Defensive clamp: ensure topVisibleLine never scrolls past the end of the total virtual lines
        // (prevents blank screen glitches during mode switches or range-constrained searches).
        val maxTopVisible = maxOf(0L, totalLinesCount - visibleRowCount)
        if (tab != null && tab.topVisibleLine > maxTopVisible) {
            tab.topVisibleLine = maxTopVisible
        }

        val textMeasurer = rememberTextMeasurer()
        val measuredCharWidthPx = remember(fonts.editorFontSizePt, fonts.editorFontFamily, fonts.editorFontWeight, density) {
            val sample = "01234567890123456789012345678901234567890123456789" // 50 chars
            val layout = textMeasurer.measure(
                text = sample,
                style = TextStyle(
                    fontFamily = editorFontFamily,
                    fontSize = fonts.editorFontSizePt.sp,
                    fontWeight = fonts.resolvedEditorWeight
                )
            )
            (layout.size.width.toFloat() / sample.length.toFloat()).coerceAtLeast(1f)
        }

        if (session == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No log file open",
                        color = colors.textEmptyStateTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Click 'Open Log File...' or enter a path above to inspect 25GB–100GB+ logs.",
                        color = colors.textEmptyStateSubtitle,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            val horizontalScrollState = rememberScrollState()
            val viewportFocusRequester = remember { FocusRequester() }
            val textLayoutResults = remember(tab, searchResult, isFilteredMode, isTemporalMode, temporalStartLine) {
                mutableMapOf<Int, Triple<Long, Long, TextLayoutResult>>()
            }
            var dynamicCharWidthPx by remember(measuredCharWidthPx) { mutableStateOf(measuredCharWidthPx) }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .focusRequester(viewportFocusRequester)
                    .focusable()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        focusManager.clearFocus()
                        viewportFocusRequester.requestFocus()
                    }
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
                            val scrollDeltaPixels = scrollDeltaY * 26f
                            var newOffset = tab.subLineScrollOffsetPx + scrollDeltaPixels
                            var currentTop = tab.topVisibleLine
                            val maxTop = max(0L, totalLinesCount - visibleRowCount)

                            while (newOffset >= rowHeightPx && currentTop < maxTop) {
                                newOffset -= rowHeightPx
                                currentTop += 1L
                            }
                            while (newOffset < 0f && currentTop > 0L) {
                                newOffset += rowHeightPx
                                currentTop -= 1L
                            }
                            if (currentTop <= 0L && newOffset < 0f) {
                                newOffset = 0f
                            }
                            if (currentTop >= maxTop && newOffset > 0f) {
                                newOffset = 0f
                            }
                            tab.topVisibleLine = currentTop
                            tab.subLineScrollOffsetPx = newOffset
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
                        } else false
                    }
            ) {
                // --- Gutter: Line Numbers ---
                val showLineNumbers = SettingsManager.currentSettings.showLineNumbers
                val maxLineDigits = totalLinesCount.toString().length.coerceAtLeast(4)
                // Hidden numbers collapse the gutter to a slim lane that still hosts latency-gap dots
                val gutterWidth = if (showLineNumbers) {
                    (maxLineDigits * (fonts.editorFontSizePt * 0.72f).toInt() + 24).dp
                } else {
                    16.dp
                }

                Column(
                    modifier = Modifier
                        .width(gutterWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(colors.surfaceGutter)
                        .padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                .graphicsLayer { translationY = -tab.subLineScrollOffsetPx }
                        ) {
                        for (rowIndex in 0 until renderRowCount) {
                            val virtualRowIndex = SelectionEdgeScroll.virtualRowAt(tab.topVisibleLine, rowIndex, totalLinesCount)
                            val isRowValid = virtualRowIndex < totalLinesCount

                            val physicalLine = if (isRowValid) {
                                when {
                                    isFilteredMode && filteredView != null -> filteredView.physicalLineForVirtualIndex(virtualRowIndex)
                                    isTemporalMode -> temporalStartLine + virtualRowIndex
                                    else -> virtualRowIndex
                                }
                            } else -1L

                            val lineNumberText = if (showLineNumbers && isRowValid && physicalLine >= 0L) {
                                (physicalLine + 1L).toString()
                            } else ""

                            val isMatch = searchResult != null && physicalLine >= 0 && searchResult.matchingLines().contains(physicalLine)
                            // isActiveMatch tracks the pinned navigate-to line (selectedLineIndex), not the current
                            // scroll-top row. This keeps the active match highlighted even when the user scrolls.
                            val isActiveMatch = isMatch && tab.selectedLineIndex != null && physicalLine >= 0 && tab.selectedLineIndex == physicalLine
                            val isSelected = tab.selectedLineIndex != null && physicalLine >= 0 && tab.selectedLineIndex == physicalLine

                            val lineNumberColor = when {
                                isSelected -> colors.gutterLineSelected
                                isActiveMatch -> colors.gutterLineActiveMatch
                                isMatch -> colors.gutterLineMatch
                                isFilteredMode -> colors.gutterLineFiltered
                                isTemporalMode -> colors.gutterLineActiveMatch
                                else -> colors.gutterLineNormal
                            }

                            val lineNumberWeight = when {
                                isSelected -> FontWeight.Bold
                                isActiveMatch -> FontWeight.Bold
                                isMatch -> FontWeight.SemiBold
                                else -> FontWeight.Normal
                            }

                            val hasLatencyGap = isRowValid && physicalLine >= 0L &&
                                SettingsManager.currentSettings.isLatencyGapHighlightingEnabled &&
                                session.hasLatencyGapAt(physicalLine)
                            val gapDuration = if (hasLatencyGap) session.latencyGapDurationAt(physicalLine) else 0L
                            val isSevereGap = gapDuration >= (SettingsManager.currentSettings.latencyGapSevereThresholdMs * 1000L)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeightDp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        focusManager.clearFocus()
                                        if (isRowValid && physicalLine >= 0L) {
                                            tab.selectedLineIndex = physicalLine
                                        }
                                    }
                            ) {
                                if (hasLatencyGap) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 2.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (isSevereGap) colors.latencyGapRed else colors.latencyGapAmber)
                                    )
                                }

                                Text(
                                    text = lineNumberText,
                                    fontFamily = editorFontFamily,
                                    fontSize = (fonts.editorFontSizePt - 1.5f).coerceAtLeast(10f).sp,
                                    color = lineNumberColor,
                                    fontWeight = lineNumberWeight,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                    }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Gutter separator line
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.gutterBorder)
                )

                // --- Content: Monospaced Log Text Rows ---
                val isHighlightEnabled = SettingsManager.currentSettings.isSearchHighlightingEnabled
                val isRegexMode = tab.isSearchRegex
                val searchNeedles = remember(tab.searchQuery, isRegexMode, isHighlightEnabled) {
                    if (isHighlightEnabled) {
                        extractSearchNeedles(tab.searchQuery, isRegex = isRegexMode)
                    } else {
                        emptyList()
                    }
                }

                // Horizontal auto-scroll to center the active match column ONLY when navigating explicitly.
                // Does NOT run on vertical scrolling (searchNavigationGeneration is only incremented by navigation).
                LaunchedEffect(tab.searchNavigationGeneration) {
                    if (tab.searchNavigationGeneration > 0 && tab.searchResult != null && !tab.searchResult!!.isEmpty) {
                        val matchCol = tab.selectedMatchColumn
                        if (matchCol >= 0) {
                            val minimapWidth = if (SettingsManager.currentSettings.isMinimapEnabled) SettingsManager.currentSettings.minimapWidthDp.dp else 0.dp
                            val viewportWidthPx = with(density) { (canvasMaxWidth - gutterWidth - minimapWidth).toPx() }
                            val matchPx = matchCol * dynamicCharWidthPx
                            val targetScrollPx = (matchPx - viewportWidthPx / 2f).toInt().coerceAtLeast(0)
                            horizontalScrollState.animateScrollTo(targetScrollPx)
                        } else {
                            horizontalScrollState.animateScrollTo(0)
                        }
                    }
                }

                // Reset the accumulated max line width whenever the view mode changes (filtered ↔ full ↔
                // temporal). The line population is entirely different in each mode so the old max would
                // otherwise over-extend the scroll canvas with phantom width from the previous mode.
                LaunchedEffect(tab.isFilteredView, tab.isTemporalFocusActive) {
                    tab.maxObservedContentWidthPx = 0f
                }

                // Compute a stable dp floor from the running max. The widthIn(min=) below pins the
                // scrollable canvas to at least this width so vertical scrolling never causes
                // ScrollState.maxValue to shrink and yank the user's horizontal position.
                val stableMinWidthDp = with(density) { tab.maxObservedContentWidthPx.toDp() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)))
                            .pointerInput(tab, totalLinesCount, searchResult, isFilteredMode, isTemporalMode, temporalStartLine, rowHeightPx) {
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
                                                isFilteredMode && filteredView != null -> filteredView.physicalLineForVirtualIndex(virtualRow)
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
                                                val physicalLine = when {
                                                    isFilteredMode && filteredView != null -> filteredView.physicalLineForVirtualIndex(virtualRow)
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
                                        tab.selectionState.isDraggingSelection = true
                                        down.consume()

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

                                        val scrollJob = coroutineScope.launch {
                                            var lastFrameNanos = withFrameNanos { it }
                                            while (isActive && tab.selectionState.isDraggingSelection) {
                                                val frameNanos = withFrameNanos { it }
                                                val elapsedSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                                                lastFrameNanos = frameNanos
                                                if (pointerPosition.x >= 0f &&
                                                    (pointerPosition.x <= viewportWidthPx ||
                                                        !SettingsManager.currentSettings.isMinimapEnabled && pointerPosition.x <= viewportWidthPx + 48f)) {
                                                    val horizontalVelocity = SelectionEdgeScroll.edgeVelocity(pointerPosition.x, viewportWidthPx)
                                                    val verticalVelocity = SelectionEdgeScroll.edgeVelocity(pointerPosition.y, viewportHeightPx)
                                                    val horizontalTarget = SelectionEdgeScroll.clampHorizontal(
                                                        horizontalScrollState.value.toFloat(), horizontalVelocity * elapsedSeconds,
                                                        horizontalScrollState.maxValue
                                                    )
                                                    if (horizontalVelocity != 0f) {
                                                        horizontalScrollState.scrollBy(horizontalTarget - horizontalScrollState.value)
                                                    }
                                                    if (verticalVelocity != 0f) {
                                                        val (newTop, newOffset) = SelectionEdgeScroll.advanceVertical(
                                                            tab.topVisibleLine, tab.subLineScrollOffsetPx, verticalVelocity * elapsedSeconds,
                                                            rowHeightPx, totalLinesCount, visibleRowCount
                                                        )
                                                        tab.topVisibleLine = newTop
                                                        tab.subLineScrollOffsetPx = newOffset
                                                    }
                                                }
                                                refreshSelection()
                                            }
                                        }
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
                                                if (kotlin.math.abs(change.position.x - down.position.x) > viewConfiguration.touchSlop ||
                                                    kotlin.math.abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
                                                pointerPosition = change.position
                                                refreshSelection()
                                                if (tab.selectionState.startPosition != initialStart ||
                                                    tab.selectionState.endPosition != initialEnd) moved = true
                                                if (event.type != PointerEventType.Scroll) change.consume()
                                            }
                                        } finally {
                                            if (moved || !released || tab.selectionState.startPosition != initialStart ||
                                                tab.selectionState.endPosition != initialEnd) clicks.cancel()
                                            tab.selectionState.isDraggingSelection = false
                                            scrollJob.cancel()
                                        }
                                    } else {
                                        clicks.cancel()
                                    }
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(min = stableMinWidthDp)
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .padding(start = 8.dp)
                                    .graphicsLayer { translationY = -tab.subLineScrollOffsetPx }
                            ) {
                                for (rowIndex in 0 until renderRowCount) {
                                    val virtualRowIndex = SelectionEdgeScroll.virtualRowAt(tab.topVisibleLine, rowIndex, totalLinesCount)
                                    val physicalLine = if (virtualRowIndex < totalLinesCount) {
                                        when {
                                            isFilteredMode && filteredView != null -> filteredView.physicalLineForVirtualIndex(virtualRowIndex)
                                            isTemporalMode -> temporalStartLine + virtualRowIndex
                                            else -> virtualRowIndex
                                        }
                                    } else -1L

                                    val (rawLineText, isMatch) = if (virtualRowIndex < totalLinesCount && physicalLine >= 0L) {
                                        try {
                                            if (isFilteredMode && filteredView != null) {
                                                val fetched = filteredView.getVirtualLineText(virtualRowIndex, ViewportState.MAX_SHAPED_CHARS)
                                                Pair(fetched, true)
                                            } else {
                                                val fetched = session.getLineText(physicalLine, ViewportState.MAX_SHAPED_CHARS)
                                                val match = when {
                                                    searchResult != null -> searchResult.matchingLines().contains(physicalLine)
                                                    tab.isSearching && searchNeedles.isNotEmpty() -> true
                                                    else -> false
                                                }
                                                Pair(fetched, match)
                                            }
                                        } catch (e: Exception) {
                                            Pair("", false)
                                        }
                                    } else Pair("", false)

                                    // isActiveMatch tracks the pinned navigate-to line (selectedLineIndex), not the current
                                    // scroll-top row. This keeps the active match highlighted even when the user scrolls.
                                    val isActiveMatch = isMatch && tab.selectedLineIndex != null && physicalLine >= 0 && tab.selectedLineIndex == physicalLine
                                    val isSelected = tab.selectedLineIndex != null && physicalLine >= 0 && tab.selectedLineIndex == physicalLine

                                    val rowBackgroundColor = when {
                                        isSelected -> colors.rowSelected
                                        isActiveMatch -> colors.rowActiveMatch
                                        isMatch && isFilteredMode -> colors.rowFilteredMatch
                                        isMatch && searchResult != null -> colors.rowMatch
                                        else -> Color.Transparent
                                    }

                                    val activeMatchCol = if (isActiveMatch) tab.selectedMatchColumn else -1
                                    val isMatchCase = tab?.isSearchMatchCase ?: false
                                    val isWholeWord = tab?.isSearchWholeWord ?: false
                                    val isRegex = tab?.isSearchRegex ?: false
                                    val baseAnnotatedLine = remember(
                                        rawLineText,
                                        searchNeedles,
                                        isMatch,
                                        isActiveMatch,
                                        activeMatchCol,
                                        isMatchCase,
                                        isWholeWord,
                                        isRegex,
                                        colors
                                    ) {
                                        buildHighlightedLine(
                                            rawLineText,
                                            searchNeedles,
                                            isMatch,
                                            isActiveMatch,
                                            activeMatchCol,
                                            isMatchCase,
                                            isWholeWord,
                                            isRegex,
                                            colors
                                        )
                                    }
                                    val selectedRange = tab.selectionState.getSelectedRangeForLine(virtualRowIndex, rawLineText.length)
                                    val annotatedLine = if (selectedRange != null) {
                                        applySelectionHighlight(baseAnnotatedLine, selectedRange, colors.selectionBackground)
                                    } else {
                                        baseAnnotatedLine
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(rowHeightDp)
                                            .background(rowBackgroundColor)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                focusManager.clearFocus()
                                                if (!tab.selectionState.hasSelection() && physicalLine >= 0L) {
                                                    tab.selectedLineIndex = physicalLine
                                                }
                                            },
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(
                                            text = annotatedLine,
                                            fontFamily = editorFontFamily,
                                            fontSize = fonts.editorFontSizePt.sp,
                                            fontWeight = fonts.resolvedEditorWeight,
                                            style = TextStyle(fontFeatureSettings = fontFeatureSettings),
                                            color = colors.textEditor,
                                            maxLines = 1,
                                            softWrap = false,
                                            onTextLayout = { layoutResult ->
                                                textLayoutResults[rowIndex] = Triple(virtualRowIndex, physicalLine, layoutResult)
                                                // Ratchet: grow the max observed width but never shrink it.
                                                // Using layoutResult.size.width captures the full unsoftWrapped
                                                // pixel extent of the line, which matches what the scroll canvas
                                                // needs to size itself against.
                                                val renderedLineWidthPx = layoutResult.size.width.toFloat()
                                                if (renderedLineWidthPx > tab.maxObservedContentWidthPx) {
                                                    tab.maxObservedContentWidthPx = renderedLineWidthPx
                                                }
                                                if (layoutResult.layoutInput.text.isNotEmpty() && rowIndex == 0) {
                                                    val textLen = layoutResult.layoutInput.text.length
                                                    val lineWidth = layoutResult.getLineRight(0)
                                                    if (lineWidth > 0f && textLen > 0) {
                                                        dynamicCharWidthPx = (lineWidth / textLen.toFloat()).coerceAtLeast(1f)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // --- Bottom Horizontal Scrollbar ---
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(horizontalScrollState),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .background(colors.surfaceGutter),
                        style = ScrollbarStyle(
                            minimalHeight = 32.dp,
                            thickness = 8.dp,
                            shape = RoundedCornerShape(4.dp),
                            hoverDurationMillis = 300,
                            unhoverColor = colors.scrollbarHorizontalUnhovered,
                            hoverColor = colors.scrollbarHorizontalHovered
                        )
                    )
                }

                // --- GPU Seismograph Minimap ---
                if (SettingsManager.currentSettings.isMinimapEnabled) {
                    val minimapTable = session.minimapTableOrNull()
                    val minimapTotalLines = if (isTemporalMode) session.totalLines() else totalLinesCount
                    val effectiveTopLine = if (isTemporalMode) temporalStartLine + tab.topVisibleLine else tab.topVisibleLine

                    GpuSeismographMinimap(
                        table = minimapTable,
                        session = session,
                        totalLines = minimapTotalLines,
                        visibleRowCount = visibleRowCount,
                        topLineIndex = effectiveTopLine,
                        searchResult = searchResult,
                        widthDp = SettingsManager.currentSettings.minimapWidthDp.dp,
                        focusStartLine = temporalStartLine,
                        focusEndLine = temporalEndLine,
                        isFocusActive = isTemporalMode,
                        onScrollToLine = { newTop ->
                            tab.subLineScrollOffsetPx = 0f
                            if (isTemporalMode) {
                                val relTop = (newTop - temporalStartLine).coerceIn(0L, max(0L, totalLinesCount - visibleRowCount))
                                tab.topVisibleLine = relTop
                            } else {
                                val maxTop = max(0L, totalLinesCount - visibleRowCount)
                                tab.topVisibleLine = newTop.coerceIn(0L, maxTop)
                            }
                        }
                    )
                }

                // --- Custom 64-Bit Vertical Scrollbar ---
                if (totalLinesCount > visibleRowCount) {
                    Custom64BitScrollbar(
                        totalLines = totalLinesCount,
                        visibleRowCount = visibleRowCount,
                        topLineIndex = tab.topVisibleLine,
                        onScrollToLine = { newTop ->
                            tab.subLineScrollOffsetPx = 0f
                            val maxTop = max(0L, totalLinesCount - visibleRowCount)
                            tab.topVisibleLine = newTop.coerceIn(0L, maxTop)
                        }
                    )
                }
            }
        }
    }
}
