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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import java.awt.Cursor
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.view.FilteredLineView
import umbrix.ui.search.applySelectionHighlight
import umbrix.ui.search.buildHighlightedLine
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.selection.TextPosition
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
            val textLayoutResults = remember { mutableMapOf<Int, TextLayoutResult>() }
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
                            val virtualRowIndex = tab.topVisibleLine + rowIndex
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
                            val isActiveMatch = isMatch && virtualRowIndex == tab.topVisibleLine
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
                val searchNeedles = remember(tab.searchQuery, isHighlightEnabled) {
                    if (isHighlightEnabled) {
                        extractSearchNeedles(tab.searchQuery)
                    } else {
                        emptyList()
                    }
                }

                // Horizontal auto-scroll to reveal match column when navigating
                LaunchedEffect(tab.topVisibleLine, tab.searchResult, tab.isFilteredView) {
                    if (tab.searchResult != null && tab.searchQuery.isNotBlank() && !tab.searchResult!!.isEmpty) {
                        val lineText = try {
                            if (isFilteredMode && filteredView != null) {
                                filteredView.getVirtualLineText(tab.topVisibleLine, ViewportState.MAX_SHAPED_CHARS)
                            } else {
                                session.getLineText(tab.topVisibleLine, ViewportState.MAX_SHAPED_CHARS)
                            }
                        } catch (_: Exception) { "" }

                        var firstMatchCol = -1
                        val lowerText = lineText.lowercase()
                        for (needle in searchNeedles) {
                            if (needle.isNotBlank()) {
                                val idx = lowerText.indexOf(needle.lowercase())
                                if (idx != -1 && (firstMatchCol == -1 || idx < firstMatchCol)) {
                                    firstMatchCol = idx
                                }
                            }
                        }

                        if (firstMatchCol > 20) {
                            val targetCharOffset = (firstMatchCol - 15).coerceAtLeast(0)
                            val targetScrollPx = (targetCharOffset * measuredCharWidthPx).toInt()
                            horizontalScrollState.animateScrollTo(targetScrollPx)
                        } else if (firstMatchCol >= 0) {
                            horizontalScrollState.animateScrollTo(0)
                        }
                    }
                }

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
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            Column(
                                modifier = Modifier
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)))
                                    .pointerInput(tab, renderRowCount, fonts.editorFontSizePt, fonts.lineHeightMultiplier) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val paddingStartPx = with(density) { 8.dp.toPx() }
                                            val localX = (down.position.x - paddingStartPx).coerceAtLeast(0f)
                                            val adjustedY = down.position.y + tab.subLineScrollOffsetPx
                                            val clickRow = (adjustedY / rowHeightPx).toInt().coerceIn(0, renderRowCount - 1)
                                            val clickLine = (tab.topVisibleLine + clickRow).coerceIn(0L, max(0L, totalLinesCount - 1L))

                                            val layout = textLayoutResults[clickRow]
                                            val clickChar = if (layout != null) {
                                                layout.getOffsetForPosition(Offset(localX, rowHeightPx / 2f))
                                            } else {
                                                (localX / dynamicCharWidthPx).toInt()
                                            }

                                            tab.selectionState.startPosition = TextPosition(clickLine, clickChar)
                                            tab.selectionState.endPosition = TextPosition(clickLine, clickChar)
                                            tab.selectionState.isDraggingSelection = true
                                            down.consume()

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull() ?: break
                                                if (!change.pressed) {
                                                    tab.selectionState.isDraggingSelection = false
                                                    break
                                                }
                                                change.consume()
                                                val dragLocalX = (change.position.x - paddingStartPx).coerceAtLeast(0f)
                                                val dragAdjustedY = change.position.y + tab.subLineScrollOffsetPx
                                                val dragRow = (dragAdjustedY / rowHeightPx).toInt().coerceIn(0, renderRowCount - 1)
                                                val dragLine = (tab.topVisibleLine + dragRow).coerceIn(0L, max(0L, totalLinesCount - 1L))

                                                val dragLayout = textLayoutResults[dragRow]
                                                val dragChar = if (dragLayout != null) {
                                                    dragLayout.getOffsetForPosition(Offset(dragLocalX, rowHeightPx / 2f))
                                                } else {
                                                    (dragLocalX / dynamicCharWidthPx).toInt()
                                                }
                                                tab.selectionState.endPosition = TextPosition(dragLine, dragChar)
                                            }
                                        }
                                    }
                                    .padding(start = 8.dp)
                                    .graphicsLayer { translationY = -tab.subLineScrollOffsetPx }
                            ) {
                                for (rowIndex in 0 until renderRowCount) {
                                    val virtualRowIndex = tab.topVisibleLine + rowIndex
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
                                                val match = searchResult?.matchingLines()?.contains(physicalLine) == true
                                                Pair(fetched, match)
                                            }
                                        } catch (e: Exception) {
                                            Pair("", false)
                                        }
                                    } else Pair("", false)

                                    val isActiveMatch = isMatch && virtualRowIndex == tab.topVisibleLine
                                    val isSelected = tab.selectedLineIndex != null && physicalLine >= 0 && tab.selectedLineIndex == physicalLine

                                    val rowBackgroundColor = when {
                                        isSelected -> colors.rowSelected
                                        isActiveMatch -> colors.rowActiveMatch
                                        isMatch && isFilteredMode -> colors.rowFilteredMatch
                                        isMatch -> colors.rowMatch
                                        else -> Color.Transparent
                                    }

                                    val baseAnnotatedLine = remember(rawLineText, searchNeedles, isMatch, isActiveMatch, colors) {
                                        buildHighlightedLine(rawLineText, searchNeedles, isMatch, isActiveMatch, colors)
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
                                                textLayoutResults[rowIndex] = layoutResult
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
