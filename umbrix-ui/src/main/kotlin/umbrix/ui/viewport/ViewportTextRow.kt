package umbrix.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.core.view.FilteredLineView
import umbrix.ui.contextmenu.LogContextMenuState
import umbrix.ui.search.applySelectionHighlight
import umbrix.ui.search.buildHighlightedLine
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixFonts
import umbrix.ui.theme.UmbrixTheme

/**
 * Renders an individual log row with syntax highlighting, search matches, and stack trace continuation frames.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ViewportTextRow(
    rowIndex: Int,
    virtualRowIndex: Long,
    physicalLine: Long,
    totalLinesCount: Long,
    tab: WorkspaceTab,
    session: LogSession,
    isFilteredMode: Boolean,
    filteredView: FilteredLineView?,
    searchResult: SearchResult?,
    searchNeedles: List<String>,
    rowHeightDp: Dp,
    rowHeightPx: Float,
    gutterWidthPx: Float,
    horizontalScrollValue: Int,
    editorFontFamily: FontFamily,
    fontFeatureSettings: String,
    isFullWrap: Boolean,
    isExpandable: Boolean,
    showWhitespace: Boolean,
    onContextMenuOpen: (LogContextMenuState) -> Unit,
    onTextLayoutResult: OnRowTextLayout,
    onClearFocus: () -> Unit,
    viewportCoordinates: () -> LayoutCoordinates? = { null }
) {
    val colors = UmbrixTheme.colors
    val fonts = UmbrixFonts.current
    val density = LocalDensity.current

    val layoutHolder = remember { RowLayoutHolder() }

    SideEffect {
        if (physicalLine >= 0L && virtualRowIndex < totalLinesCount) {
            val layout = layoutHolder.layout
            val coordinates = layoutHolder.coordinates
            if (layout != null && coordinates != null && coordinates.isAttached) {
                onTextLayoutResult(
                    rowIndex,
                    RowTextLayout(virtualRowIndex, physicalLine, layout, coordinates)
                )
            }
        } else {
            onTextLayoutResult(rowIndex, null)
        }
    }

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
    val isMatchCase = tab.isSearchMatchCase
    val isWholeWord = tab.isSearchWholeWord
    val isRegex = tab.isSearchRegex
    val baseAnnotatedLine = remember(
        rawLineText,
        searchNeedles,
        isMatch,
        isActiveMatch,
        activeMatchCol,
        isMatchCase,
        isWholeWord,
        isRegex,
        showWhitespace,
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
            showWhitespace = showWhitespace,
            theme = colors
        )
    }
    val selectedRange = tab.selectionState.getSelectedRangeForLine(virtualRowIndex, rawLineText.length)
    val annotatedLine = if (selectedRange != null) {
        applySelectionHighlight(baseAnnotatedLine, selectedRange, colors.selectionBackground)
    } else {
        baseAnnotatedLine
    }

    val isRowValid = virtualRowIndex < totalLinesCount
    val rawSlice = if (isRowValid && physicalLine >= 0L) {
        try { session.indexer().getLineSlice(physicalLine) } catch (_: Throwable) { null }
    } else null

    val hasVirtualExpansion = isRowValid && physicalLine >= 0L && session.pieceTable().hasVirtualExpansion(physicalLine)
    val virtualExpansionLines = if (hasVirtualExpansion) {
        try { session.pieceTable().getVirtualExpansionLines(physicalLine, 100, 4096) } catch (_: Throwable) { null }
    } else null

    val classifier = if (isRowValid && physicalLine >= 0L && isExpandable) session.eventClassifier() else null
    val isHeader = classifier != null && classifier.isEventHeader(physicalLine)
    val eventSpan = if (isHeader && classifier != null) classifier.eventLineSpan(physicalLine) else 1L
    val hasContinuation = (isExpandable && eventSpan > 1L) || (virtualExpansionLines != null && virtualExpansionLines.isNotEmpty())
    val isExpanded = hasContinuation && tab.isEventExpanded(physicalLine)

    val inlineChips = remember(physicalLine, rawLineText) {
        if (isRowValid && physicalLine >= 0L && rawSlice != null) {
            try {
                val list = java.util.ArrayList<umbrix.plugin.api.decorator.InlineChipDecoration>()
                umbrix.core.plugin.PluginManager.instance().resolveInlineChips(session.pluginSessionView(), physicalLine, rawSlice, rawLineText, list)
                list
            } catch (_: Throwable) { emptyList<umbrix.plugin.api.decorator.InlineChipDecoration>() }
        } else emptyList()
    }

    var activeHoverCard by remember { mutableStateOf<umbrix.plugin.api.decorator.HoverCardDecoration?>(null) }
    var hoverAnchorOffset by remember { mutableStateOf(Offset.Zero) }

    val rowContainerModifier = if (isFullWrap || isExpanded) {
        Modifier.fillMaxWidth().wrapContentHeight().heightIn(min = rowHeightDp)
    } else {
        Modifier.fillMaxWidth().height(rowHeightDp)
    }

    Column(
        modifier = rowContainerModifier
            .background(rowBackgroundColor)
            .onGloballyPositioned { coordinates -> layoutHolder.containerCoordinates = coordinates }
            .onPointerEvent(PointerEventType.Move) { event ->
                event.changes.firstOrNull()?.let { change ->
                    hoverAnchorOffset = change.position
                }
            }
            .onPointerEvent(PointerEventType.Enter) {
                if (isRowValid && physicalLine >= 0L && rawSlice != null) {
                    try {
                        val card = umbrix.core.plugin.PluginManager.instance().resolveHoverCard(session.pluginSessionView(), physicalLine, rawSlice, rawLineText, 0)
                        activeHoverCard = card
                    } catch (_: Throwable) {}
                }
            }
            .onPointerEvent(PointerEventType.Exit) {
                activeHoverCard = null
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed && physicalLine >= 0L) {
                    event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull()
                    val clickPos = change?.position ?: Offset.Zero
                    val gutterSepWidthPx = with(density) { 1.dp.toPx() }
                    val textPaddingStartPx = with(density) { 8.dp.toPx() }
                    val effectiveScrollX = if (isFullWrap) 0 else horizontalScrollValue
                    val posX = gutterWidthPx + gutterSepWidthPx + textPaddingStartPx + clickPos.x - effectiveScrollX
                    val containerCoordinates = layoutHolder.containerCoordinates
                    val currentViewportCoordinates = viewportCoordinates()
                    // Map the click through live coordinates so wrapped/expanded rows of any height are exact.
                    val posY = if (containerCoordinates != null && containerCoordinates.isAttached &&
                        currentViewportCoordinates != null && currentViewportCoordinates.isAttached
                    ) {
                        try {
                            currentViewportCoordinates.localPositionOf(containerCoordinates, clickPos).y
                        } catch (_: Throwable) {
                            (rowIndex * rowHeightPx - tab.subLineScrollOffsetPx + clickPos.y).coerceAtLeast(0f)
                        }
                    } else {
                        (rowIndex * rowHeightPx - tab.subLineScrollOffsetPx + clickPos.y).coerceAtLeast(0f)
                    }

                    val selectedText = try {
                        if (tab.selectionState.hasSelection()) {
                            val temporalStart: Long = if (filteredView == null && tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                            val virtualLineCount = ViewportCoordinateMapper.virtualLineCount(
                                session = session,
                                filteredView = filteredView,
                                isTemporalFocusActive = tab.isTemporalFocusActive,
                                temporalStartLine = temporalStart,
                                temporalEndLine = tab.temporalFocusEndLine
                            )
                            val isTemporalMode = filteredView == null && tab.isTemporalFocusActive
                            tab.selectionState.extractSelectedText(
                                virtualLineCount = virtualLineCount,
                                lineTextAtVirtualIndex = { vRow ->
                                    val pLine = ViewportCoordinateMapper.resolvePhysicalLine(
                                        virtualRow = VirtualRowIndex(vRow),
                                        totalLinesCount = virtualLineCount,
                                        isFilteredMode = filteredView != null,
                                        filteredView = filteredView,
                                        isTemporalMode = isTemporalMode,
                                        temporalStartLine = temporalStart
                                    ).value
                                    session.getLineText(pLine, ViewportState.MAX_SHAPED_CHARS)
                                }
                            )
                        } else null
                    } catch (_: Exception) { null }

                    onContextMenuOpen(
                        LogContextMenuState(
                            isVisible = true,
                            physicalLine = physicalLine,
                            lineText = rawLineText,
                            selectedText = selectedText?.takeIf { it.isNotEmpty() },
                            positionPx = Offset(posX, posY)
                        )
                    )
                }
            },
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasContinuation) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .pointerInput(physicalLine) {
                            detectTapGestures { tab.toggleEventExpansion(physicalLine) }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        fontSize = 10.sp,
                        color = colors.accentPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = annotatedLine,
                fontFamily = editorFontFamily,
                fontSize = fonts.editorFontSizePt.sp,
                fontWeight = fonts.resolvedEditorWeight,
                style = TextStyle(fontFeatureSettings = fontFeatureSettings),
                color = colors.textEditor,
                maxLines = if (isFullWrap) Int.MAX_VALUE else 1,
                softWrap = isFullWrap,
                modifier = (if (isFullWrap) Modifier.weight(1f, fill = false) else Modifier)
                    .onGloballyPositioned { coordinates ->
                        layoutHolder.coordinates = coordinates
                        val layout = layoutHolder.layout
                        if (layout != null && coordinates.isAttached && physicalLine >= 0L && virtualRowIndex < totalLinesCount) {
                            onTextLayoutResult(
                                rowIndex,
                                RowTextLayout(virtualRowIndex, physicalLine, layout, coordinates)
                            )
                        }
                    },
                onTextLayout = { layoutResult ->
                    layoutHolder.layout = layoutResult
                    val coordinates = layoutHolder.coordinates
                    if (coordinates != null && coordinates.isAttached && physicalLine >= 0L && virtualRowIndex < totalLinesCount) {
                        onTextLayoutResult(
                            rowIndex,
                            RowTextLayout(virtualRowIndex, physicalLine, layoutResult, coordinates)
                        )
                    }
                }
            )
            for (chip in inlineChips) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = chip.label(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(chip.textColorArgb()),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(chip.backgroundColorArgb()))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (hasContinuation && !isExpanded) {
                Spacer(modifier = Modifier.width(6.dp))
                val badgeText = if (virtualExpansionLines != null && virtualExpansionLines.isNotEmpty()) {
                    "[+${virtualExpansionLines.size} lines]"
                } else {
                    "[+${eventSpan - 1} frames]"
                }
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    color = colors.textSubtle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.surfaceElevated)
                        .pointerInput(physicalLine) {
                            detectTapGestures { tab.toggleEventExpansion(physicalLine) }
                        }
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        if (isExpanded) {
            Column(
                modifier = Modifier
                    .padding(start = 14.dp, top = 2.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, if (virtualExpansionLines != null && virtualExpansionLines.isNotEmpty()) colors.accentPrimary.copy(alpha = 0.4f) else colors.border, RoundedCornerShape(3.dp))
                    .background(colors.surfaceElevated.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (virtualExpansionLines != null && virtualExpansionLines.isNotEmpty()) {
                    val count = minOf(virtualExpansionLines.size, 100)
                    for (i in 0 until count) {
                        Text(
                            text = virtualExpansionLines[i],
                            fontFamily = editorFontFamily,
                            fontSize = (fonts.editorFontSizePt - 1.0f).coerceAtLeast(10f).sp,
                            fontWeight = FontWeight.Normal,
                            color = colors.textPrimary,
                            maxLines = if (isFullWrap) Int.MAX_VALUE else 1,
                            softWrap = isFullWrap
                        )
                    }
                    if (virtualExpansionLines.size > 100) {
                        Text(
                            text = "... [+${virtualExpansionLines.size - 100} more virtual expansion lines]",
                            fontSize = 10.sp,
                            color = colors.textSubtle
                        )
                    }
                } else {
                    val framesToShow = minOf(eventSpan - 1L, 50L)
                    for (frameOffset in 1L..framesToShow) {
                        val frameLine = physicalLine + frameOffset
                        val frameText = try { session.getLineText(frameLine, 2048) } catch (e: Exception) { "" }
                        Text(
                            text = frameText,
                            fontFamily = editorFontFamily,
                            fontSize = (fonts.editorFontSizePt - 1.0f).coerceAtLeast(10f).sp,
                            fontWeight = FontWeight.Normal,
                            color = colors.textSecondary,
                            maxLines = if (isFullWrap) Int.MAX_VALUE else 1,
                            softWrap = isFullWrap
                        )
                    }
                    if (eventSpan - 1L > 50L) {
                        Text(
                            text = "... [+${eventSpan - 1L - 50L} more frames in file]",
                            fontSize = 10.sp,
                            color = colors.textSubtle
                        )
                    }
                }
            }
        }
    }

    if (activeHoverCard != null) {
        PluginHoverCardPopup(
            decoration = activeHoverCard!!,
            anchorOffsetPx = hoverAnchorOffset,
            onDismissRequest = { activeHoverCard = null }
        )
    }
}

private class RowLayoutHolder {
    var layout: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
    var containerCoordinates: LayoutCoordinates? = null
}
