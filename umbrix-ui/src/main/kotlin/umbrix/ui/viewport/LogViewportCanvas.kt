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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.view.FilteredLineView
import umbrix.ui.contextmenu.LogContextMenuState
import umbrix.ui.contextmenu.LogViewportContextMenu
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.EditorTypography
import umbrix.ui.theme.FontSpec
import umbrix.ui.theme.UmbrixFonts
import umbrix.ui.theme.UmbrixTheme
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.max

/**
 * High-performance 64-bit log viewport canvas rendering hardware-accelerated text rows,
 * gutter line numbers, active search needle highlights, and 64-bit virtual scrolling.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogViewportCanvas(
    tab: WorkspaceTab?,
    modifier: Modifier = Modifier,
    onFilterByText: ((String) -> Unit)? = null,
    onSearchText: ((String) -> Unit)? = null,
    onSeekInScrubber: ((Long) -> Unit)? = null,
    onExportStartingHere: ((Long) -> Unit)? = null,
    onAddToTimeline: ((Long, String) -> Unit)? = null,
    onAppendToScratchpad: ((Long, String) -> Unit)? = null,
    onNewScratchpadFromSelection: ((String) -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onNewScratchpad: (() -> Unit)? = null,
    onToggleBookmarkFilter: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val colors = UmbrixTheme.colors
    val fonts = UmbrixFonts.current

    var contextMenuState by remember { mutableStateOf(LogContextMenuState()) }

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
        val isBookmarkFilteredMode = tab?.isBookmarkFilterActive == true && (tab.bookmarkManager.bookmarks.isNotEmpty())
        val isSearchFilteredMode = tab?.isFilteredView == true && searchResult != null && !isBookmarkFilteredMode
        val isFilteredMode = isBookmarkFilteredMode || isSearchFilteredMode
        val isTemporalMode = tab?.isTemporalFocusActive == true && !isFilteredMode

        val bookmarkBitmap = remember(tab?.bookmarkManager?.bookmarks?.size, tab?.bookmarkManager?.bookmarks?.toList()) {
            tab?.bookmarkManager?.toBitmap()
        }

        val filteredView: FilteredLineView? = if (isFilteredMode && session != null) {
            if (isBookmarkFilteredMode && bookmarkBitmap != null) {
                session.createFilteredView(bookmarkBitmap)
            } else if (searchResult != null) {
                session.createFilteredView(searchResult.matchingLines())
            } else null
        } else null

        val totalSessionLines: Long = (session?.totalLines() as Number?)?.toLong() ?: 0L
        val temporalStartLine: Long = if (isTemporalMode) tab!!.temporalFocusStartLine else 0L
        val temporalEndLine: Long = if (isTemporalMode) tab!!.temporalFocusEndLine else (totalSessionLines - 1L)

        val totalLinesCount: Long = when {
            isFilteredMode && filteredView != null -> (filteredView.totalFilteredLines() as Number).toLong()
            isTemporalMode -> max(0L, temporalEndLine - temporalStartLine + 1L)
            else -> totalSessionLines
        }

        val rowHeightDp = (fonts.editorFontSizePt * fonts.lineHeightMultiplier + 4.0f).dp
        val rowHeightPx = (fonts.editorFontSizePt * fonts.lineHeightMultiplier + 4.0f) * density.density
        val visibleRowCount = ((maxHeight - 12.dp) / rowHeightDp).toInt().coerceAtLeast(1)
        val renderRowCount = visibleRowCount + 2

        if (tab != null && tab.visibleRowCount != visibleRowCount) {
            tab.visibleRowCount = visibleRowCount
        }

        val maxTopVisible = maxOf(0L, totalLinesCount - visibleRowCount)
        if (tab != null && tab.topVisibleLine > maxTopVisible) {
            tab.topVisibleLine = maxTopVisible
        }

        val textMeasurer = rememberTextMeasurer()
        val measuredCharWidthPx = remember(fonts.editorFontSizePt, fonts.editorFontFamily, fonts.editorFontWeight, density) {
            val sample = "01234567890123456789012345678901234567890123456789"
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
                val showLineNumbers = SettingsManager.currentSettings.showLineNumbers
                val maxLineDigits = totalLinesCount.toString().length.coerceAtLeast(4)
                val gutterWidth = if (showLineNumbers) {
                    (maxLineDigits * (fonts.editorFontSizePt * 0.72f).toInt() + 24).dp
                } else {
                    16.dp
                }
                val gutterWidthPx = with(density) { gutterWidth.toPx() }

                ViewportGutter(
                    tab = tab,
                    session = session,
                    renderRowCount = renderRowCount,
                    totalLinesCount = totalLinesCount,
                    isFilteredMode = isFilteredMode,
                    filteredView = filteredView,
                    isTemporalMode = isTemporalMode,
                    temporalStartLine = temporalStartLine,
                    searchResult = searchResult,
                    rowHeightDp = rowHeightDp,
                    gutterWidth = gutterWidth,
                    editorFontFamily = editorFontFamily,
                    onClearFocus = { focusManager.clearFocus() }
                )

                // Gutter separator line
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.gutterBorder)
                )

                // Content Monospaced Log Text Rows
                val isHighlightEnabled = SettingsManager.currentSettings.isSearchHighlightingEnabled
                val isRegexMode = tab.isSearchRegex
                val searchNeedles = remember(tab.searchQuery, isRegexMode, isHighlightEnabled) {
                    if (isHighlightEnabled) {
                        extractSearchNeedles(tab.searchQuery, isRegex = isRegexMode)
                    } else {
                        emptyList()
                    }
                }

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

                LaunchedEffect(tab.isFilteredView, tab.isTemporalFocusActive) {
                    tab.maxObservedContentWidthPx = 0f
                }

                val stableMinWidthDp = with(density) { tab.maxObservedContentWidthPx.toDp() }
                val currentViewportMode = SettingsManager.currentSettings.viewportMode
                val isFullWrap = currentViewportMode == ViewportLayoutMode.FULL_WRAP
                val isExpandable = currentViewportMode == ViewportLayoutMode.EXPANDABLE
                val showWhitespace = SettingsManager.currentSettings.showWhitespaceChars

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
                            .onPointerEvent(PointerEventType.Press) { event ->
                                if (event.buttons.isSecondaryPressed && !contextMenuState.isVisible) {
                                    val change = event.changes.firstOrNull()
                                    if (change != null && !change.isConsumed) {
                                        change.consume()
                                        val posX = gutterWidthPx + change.position.x
                                        val posY = change.position.y
                                        contextMenuState = LogContextMenuState(
                                            isVisible = true,
                                            physicalLine = (tab.selectedLineIndex ?: tab.topVisibleLine).coerceAtLeast(0L),
                                            lineText = "",
                                            selectedText = null,
                                            positionPx = Offset(posX, posY)
                                        )
                                    }
                                }
                            }
                            .viewportPointerGestures(
                                tab = tab,
                                session = session,
                                totalLinesCount = totalLinesCount,
                                searchResult = searchResult,
                                isFilteredMode = isFilteredMode,
                                filteredView = filteredView,
                                isTemporalMode = isTemporalMode,
                                temporalStartLine = temporalStartLine,
                                renderRowCount = renderRowCount,
                                rowHeightPx = rowHeightPx,
                                visibleRowCount = visibleRowCount,
                                density = density,
                                dynamicCharWidthPx = dynamicCharWidthPx,
                                horizontalScrollState = horizontalScrollState,
                                coroutineScope = coroutineScope,
                                textLayoutResults = textLayoutResults
                            )
                    ) {
                        Box(
                            modifier = if (isFullWrap) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .then(if (isFullWrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = stableMinWidthDp))
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .padding(start = 8.dp)
                                    .graphicsLayer { translationY = -tab.subLineScrollOffsetPx }
                            ) {
                                for (rowIndex in 0 until renderRowCount) {
                                    val virtualRowIndex = SelectionEdgeScroll.virtualRowAt(tab.topVisibleLine, rowIndex, totalLinesCount)
                                    val physicalLine: Long = if (virtualRowIndex < totalLinesCount) {
                                        when {
                                            isFilteredMode && filteredView != null -> (filteredView.physicalLineForVirtualIndex(virtualRowIndex) as Number).toLong()
                                            isTemporalMode -> temporalStartLine + virtualRowIndex
                                            else -> virtualRowIndex
                                        }
                                    } else -1L

                                    ViewportTextRow(
                                        rowIndex = rowIndex,
                                        virtualRowIndex = virtualRowIndex,
                                        physicalLine = physicalLine,
                                        totalLinesCount = totalLinesCount,
                                        tab = tab,
                                        session = session,
                                        isFilteredMode = isFilteredMode,
                                        filteredView = filteredView,
                                        searchResult = searchResult,
                                        searchNeedles = searchNeedles,
                                        rowHeightDp = rowHeightDp,
                                        rowHeightPx = rowHeightPx,
                                        gutterWidthPx = gutterWidthPx,
                                        horizontalScrollValue = horizontalScrollState.value,
                                        editorFontFamily = editorFontFamily,
                                        fontFeatureSettings = fontFeatureSettings,
                                        isFullWrap = isFullWrap,
                                        isExpandable = isExpandable,
                                        showWhitespace = showWhitespace,
                                        onContextMenuOpen = { contextMenuState = it },
                                        onTextLayoutResult = { rIdx, vRow, pLine, layoutResult ->
                                            textLayoutResults[rIdx] = Triple(vRow, pLine, layoutResult)
                                            val renderedLineWidthPx = layoutResult.size.width.toFloat()
                                            if (renderedLineWidthPx > tab.maxObservedContentWidthPx) {
                                                tab.maxObservedContentWidthPx = renderedLineWidthPx
                                            }
                                            if (layoutResult.layoutInput.text.isNotEmpty() && rIdx == 0) {
                                                val textLen = layoutResult.layoutInput.text.length
                                                val lineWidth = layoutResult.getLineRight(0)
                                                if (lineWidth > 0f && textLen > 0) {
                                                    dynamicCharWidthPx = (lineWidth / textLen.toFloat()).coerceAtLeast(1f)
                                                }
                                            }
                                        },
                                        onClearFocus = { focusManager.clearFocus() }
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Horizontal Scrollbar
                    if (!isFullWrap) {
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
                }

                // GPU Seismograph Minimap
                if (SettingsManager.currentSettings.isMinimapEnabled) {
                    val minimapTable = session.minimapTableOrNull()
                    val minimapTotalLines: Long = if (isTemporalMode) (session.totalLines() as Number).toLong() else totalLinesCount
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
                        bookmarkManager = tab.bookmarkManager,
                        onScrollToLine = { newTop ->
                            tab.subLineScrollOffsetPx = 0f
                            if (isTemporalMode) {
                                val relTop = (newTop - temporalStartLine).coerceIn(0L, max(0L, totalLinesCount - visibleRowCount))
                                tab.topVisibleLine = relTop
                            } else {
                                tab.topVisibleLine = newTop.coerceIn(0L, max(0L, totalLinesCount - visibleRowCount))
                            }
                        }
                    )
                }

                // 64-bit Virtual Scrollbar
                Custom64BitScrollbar(
                    totalLines = totalLinesCount,
                    visibleRowCount = visibleRowCount,
                    topLineIndex = tab.topVisibleLine,
                    onScrollToLine = { newTop ->
                        tab.subLineScrollOffsetPx = 0f
                        tab.topVisibleLine = newTop.coerceIn(0L, max(0L, totalLinesCount - visibleRowCount))
                    }
                )
            }
        }
    }

    val activeTab = tab
    if (contextMenuState.isVisible && activeTab != null) {
        val s = activeTab.session
        LogViewportContextMenu(
            state = contextMenuState,
            onDismiss = { contextMenuState = contextMenuState.copy(isVisible = false) },
            onCopyLineText = { lineText ->
                val stringSelection = StringSelection(lineText)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, stringSelection)
            },
            onCopyLineNumber = { lineNum ->
                val stringSelection = StringSelection((lineNum + 1L).toString())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, stringSelection)
            },
            onFilterByText = onFilterByText ?: {},
            onSearchText = onSearchText ?: {},
            onSeekInScrubber = { line ->
                if (onSeekInScrubber != null) {
                    onSeekInScrubber(line)
                } else {
                    activeTab.selectedLineIndex = line
                    if (activeTab.isTemporalFocusActive) {
                        val relTop = (line - activeTab.temporalFocusStartLine - 10).coerceAtLeast(0L)
                        activeTab.topVisibleLine = relTop
                    } else {
                        activeTab.topVisibleLine = (line - 10).coerceAtLeast(0L)
                    }
                }
            },
            onExportStartingHere = { line ->
                if (onExportStartingHere != null) {
                    onExportStartingHere(line)
                } else {
                    activeTab.selectedLineIndex = line
                }
            },
            onAddToTimeline = onAddToTimeline,
            onToggleBookmark = { line, lineText ->
                if (s != null) {
                    val ts: Long = if (s.isTemporalDataAvailable) {
                        try {
                            val cpIdx = s.temporalIndex().closestCheckpointIndexForLine(line)
                            (s.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                        } catch (e: Exception) { 0L }
                    } else 0L
                    activeTab.bookmarkManager.toggle(line, ts, lineText)
                }
            },
            onAppendToScratchpad = onAppendToScratchpad,
            onNewScratchpadFromSelection = onNewScratchpadFromSelection,
            onOpenFile = onOpenFile,
            onNewScratchpad = onNewScratchpad
        )
    }
}
