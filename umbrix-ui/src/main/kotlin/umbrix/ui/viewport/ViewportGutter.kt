package umbrix.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.core.view.FilteredLineView
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixFonts
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.toast.ToastManager

/**
 * Gutter component displaying line numbers, latency gap dots, bookmark pins, and event folding chevrons.
 */
@Composable
fun ViewportGutter(
    tab: WorkspaceTab,
    session: LogSession,
    renderRowCount: Int,
    totalLinesCount: Long,
    isFilteredMode: Boolean,
    filteredView: FilteredLineView?,
    isTemporalMode: Boolean,
    temporalStartLine: Long,
    searchResult: SearchResult?,
    rowHeightDp: Dp,
    gutterWidth: Dp,
    editorFontFamily: FontFamily,
    onClearFocus: () -> Unit
) {
    val colors = UmbrixTheme.colors
    val fonts = UmbrixFonts.current
    val showLineNumbers = SettingsManager.currentSettings.showLineNumbers

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

                    val physicalLine: Long = if (isRowValid) {
                        when {
                            isFilteredMode && filteredView != null -> (filteredView.physicalLineForVirtualIndex(virtualRowIndex) as Number).toLong()
                            isTemporalMode -> temporalStartLine + virtualRowIndex
                            else -> virtualRowIndex
                        }
                    } else -1L

                    val lineNumberText = if (showLineNumbers && isRowValid && physicalLine >= 0L) {
                        (physicalLine + 1L).toString()
                    } else ""

                    val isMatch = searchResult != null && physicalLine >= 0 && searchResult.matchingLines().contains(physicalLine)
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

                    val isBookmarked = isRowValid && physicalLine >= 0L && tab.bookmarkManager.isBookmarked(physicalLine)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeightDp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onClearFocus()
                                if (isRowValid && physicalLine >= 0L) {
                                    val lineSnippet = try { session.getLineText(physicalLine, 4096) } catch (e: Exception) { "" }
                                    val ts: Long = if (session.isTemporalDataAvailable) {
                                        try {
                                            val cpIdx = session.temporalIndex().closestCheckpointIndexForLine(physicalLine)
                                            (session.temporalIndex().timestampAtCheckpoint(cpIdx) as Number).toLong()
                                        } catch (e: Exception) { 0L }
                                    } else 0L
                                    val added = tab.bookmarkManager.toggle(physicalLine, ts, lineSnippet)
                                    if (added) {
                                        ToastManager.info("Bookmarked line ${physicalLine + 1}")
                                    } else {
                                        ToastManager.info("Removed bookmark on line ${physicalLine + 1}")
                                    }
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

                        if (isBookmarked) {
                            Text(
                                text = "📌",
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = if (hasLatencyGap) 9.dp else 2.dp)
                            )
                        }

                        val isExpandableMode = SettingsManager.currentSettings.viewportMode == ViewportLayoutMode.EXPANDABLE
                        val isHeader = isExpandableMode && isRowValid && physicalLine >= 0L && session.eventClassifier().isEventHeader(physicalLine)
                        val eventSpan = if (isHeader) session.eventClassifier().eventLineSpan(physicalLine) else 1L
                        val hasContinuation = isExpandableMode && eventSpan > 1L
                        val isExpanded = hasContinuation && tab.isEventExpanded(physicalLine)

                        if (hasContinuation) {
                            Text(
                                text = if (isExpanded) "▼" else "▶",
                                fontSize = 8.sp,
                                color = colors.accentPrimary,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = if (hasLatencyGap) (if (isBookmarked) 18.dp else 9.dp) else (if (isBookmarked) 14.dp else 2.dp))
                                    .clickable { tab.toggleEventExpansion(physicalLine) }
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
}
