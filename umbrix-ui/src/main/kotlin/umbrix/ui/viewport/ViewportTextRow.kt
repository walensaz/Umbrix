package umbrix.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
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
    onTextLayoutResult: (Int, Long, Long, TextLayoutResult) -> Unit,
    onClearFocus: () -> Unit
) {
    val colors = UmbrixTheme.colors
    val fonts = UmbrixFonts.current

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
    val isHeader = isRowValid && physicalLine >= 0L && isExpandable && session.eventClassifier().isEventHeader(physicalLine)
    val eventSpan = if (isHeader) session.eventClassifier().eventLineSpan(physicalLine) else 1L
    val hasContinuation = isExpandable && eventSpan > 1L
    val isExpanded = hasContinuation && tab.isEventExpanded(physicalLine)

    val rowContainerModifier = if (isFullWrap || isExpanded) {
        Modifier.fillMaxWidth().wrapContentHeight().heightIn(min = rowHeightDp)
    } else {
        Modifier.fillMaxWidth().height(rowHeightDp)
    }

    Column(
        modifier = rowContainerModifier
            .background(rowBackgroundColor)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed && physicalLine >= 0L) {
                    event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull()
                    val clickPos = change?.position ?: Offset.Zero
                    val posX = gutterWidthPx + 8f + clickPos.x - horizontalScrollValue
                    val posY = (rowIndex * rowHeightPx - tab.subLineScrollOffsetPx + clickPos.y).coerceAtLeast(0f)

                    val selectedText = try {
                        if (tab.selectionState.hasSelection()) {
                            val temporalStart: Long = if (filteredView == null && tab.isTemporalFocusActive) tab.temporalFocusStartLine else 0L
                            val virtualLineCount: Long = when {
                                filteredView != null -> (filteredView.totalFilteredLines() as Number).toLong()
                                tab.isTemporalFocusActive -> (tab.temporalFocusEndLine - temporalStart + 1L).coerceAtLeast(0L)
                                else -> (session.totalLines() as Number).toLong()
                            }
                            tab.selectionState.extractSelectedText(
                                virtualLineCount = virtualLineCount,
                                lineTextAtVirtualIndex = { vRow ->
                                    val pLine: Long = (filteredView?.physicalLineForVirtualIndex(vRow) as Number?)?.toLong() ?: (temporalStart + vRow)
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
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    fontSize = 10.sp,
                    color = colors.accentPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .pointerInput(physicalLine) {
                            detectTapGestures { tab.toggleEventExpansion(physicalLine) }
                        }
                        .padding(end = 4.dp)
                )
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
                onTextLayout = { layoutResult ->
                    onTextLayoutResult(rowIndex, virtualRowIndex, physicalLine, layoutResult)
                }
            )
            if (hasContinuation && !isExpanded) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[+${eventSpan - 1} frames]",
                    fontSize = 10.sp,
                    color = colors.textSubtle,
                    fontWeight = FontWeight.Medium,
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
                    .border(1.dp, colors.border, RoundedCornerShape(3.dp))
                    .background(colors.surfaceElevated.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
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
