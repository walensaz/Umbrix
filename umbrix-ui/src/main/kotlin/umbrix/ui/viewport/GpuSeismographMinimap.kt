package umbrix.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.minimap.MinimapDensityTable
import umbrix.core.search.SearchResult
import umbrix.core.session.LogSession
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatNumberWithCommas
import kotlin.math.max

/**
 * Hardware-accelerated GPU Seismograph Minimap rendering a real-time density histogram
 * directly on the Skia GPU canvas.
 *
 * Features:
 * - 1,000-bin multi-channel density representation (Red Error, Amber Warn, Blue Density)
 * - Yellow glow for search match distribution
 * - Viewport indicator rect tracking visible lines
 * - Sub-millisecond interactive scrubbing on click/drag with auto-snap to errors
 * - Hover tooltip displaying line range and exact anomaly breakdown
 * - Visual color legend
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GpuSeismographMinimap(
    table: MinimapDensityTable?,
    totalLines: Long,
    visibleRowCount: Int,
    topLineIndex: Long,
    searchResult: SearchResult?,
    session: LogSession? = null,
    widthDp: Dp = 48.dp,
    focusStartLine: Long = 0L,
    focusEndLine: Long = 0L,
    isFocusActive: Boolean = false,
    bookmarkManager: umbrix.ui.bookmarks.BookmarkManager? = null,
    onScrollToLine: (Long) -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    var hoverOffset by remember { mutableStateOf<Offset?>(null) }
    val colors = UmbrixTheme.colors

    BoxWithConstraints(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .background(colors.surfaceGutter)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) {
                isHovered = false
                hoverOffset = null
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                hoverOffset = event.changes.firstOrNull()?.position
            }
    ) {
        val canvasHeight = constraints.maxHeight.toFloat()
        val canvasWidth = constraints.maxWidth.toFloat()
        if (canvasHeight <= 0f || canvasWidth <= 0f) return@BoxWithConstraints

        val maxTopLine = max(1L, totalLines - visibleRowCount)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(totalLines, visibleRowCount, canvasHeight, table, session) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val clickRatio = (down.position.y / canvasHeight).coerceIn(0f, 1f)
                        val binCount = table?.binCount() ?: MinimapDensityTable.DefaultBinCount()
                        val targetBin = (clickRatio * binCount).toInt().coerceIn(0, binCount - 1)

                        // If clicked bin has errors or warnings, snap directly to the anomaly line!
                        val targetLine = if (table != null && table.hasAnomalies(targetBin) && session != null) {
                            val binStart = table.binStartLine(targetBin, totalLines)
                            val binEnd = table.binEndLine(targetBin, totalLines)
                            val firstAnomaly = session.findNextAnomaly(binStart - 1L)
                            if (firstAnomaly in binStart..binEnd) {
                                firstAnomaly
                            } else {
                                (clickRatio.toDouble() * maxTopLine.toDouble()).toLong().coerceIn(0L, maxTopLine)
                            }
                        } else {
                            (clickRatio.toDouble() * maxTopLine.toDouble()).toLong().coerceIn(0L, maxTopLine)
                        }
                        onScrollToLine(targetLine)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break

                            change.consume()
                            val dragRatio = (change.position.y / canvasHeight).coerceIn(0f, 1f)
                            val draggedLine = (dragRatio.toDouble() * maxTopLine.toDouble()).toLong().coerceIn(0L, maxTopLine)
                            onScrollToLine(draggedLine)
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val binCount = table?.binCount() ?: MinimapDensityTable.DefaultBinCount()
                val binHeightPx = max(1f, canvasHeight / binCount.toFloat())

                // Draw background track
                drawRect(
                    color = colors.surfaceGutter,
                    topLeft = Offset.Zero,
                    size = Size(canvasWidth, canvasHeight)
                )

                if (table != null) {
                    val maxTotal = table.maxTotal().toFloat().coerceAtLeast(1f)
                    val maxError = table.maxError().toFloat().coerceAtLeast(1f)
                    val maxWarn = table.maxWarn().toFloat().coerceAtLeast(1f)

                    for (bin in 0 until binCount) {
                        val y = bin * (canvasHeight / binCount.toFloat())
                        val total = table.totalCount(bin)
                        val errors = table.errorCount(bin)
                        val warns = table.warnCount(bin)

                        if (total > 0) {
                            // Base log density bar (subtle blue/gray)
                            val totalWidth = (total.toFloat() / maxTotal) * (canvasWidth * 0.45f)
                            drawRect(
                                color = colors.minimapDensity,
                                topLeft = Offset(0f, y),
                                size = Size(totalWidth.coerceAtLeast(1f), binHeightPx)
                            )
                        }

                        if (warns > 0) {
                            // Amber warning bar
                            val warnWidth = (warns.toFloat() / maxWarn) * (canvasWidth * 0.65f)
                            drawRect(
                                color = colors.minimapWarn,
                                topLeft = Offset(0f, y),
                                size = Size(warnWidth.coerceAtLeast(2f), binHeightPx)
                            )
                        }

                        if (errors > 0) {
                            // Vibrant red error spike
                            val errorWidth = (errors.toFloat() / maxError) * canvasWidth
                            drawRect(
                                color = colors.minimapError,
                                topLeft = Offset(0f, y),
                                size = Size(errorWidth.coerceAtLeast(4f), binHeightPx)
                            )
                        }
                    }
                }

                // Render Search Match indicator glow
                if (searchResult != null && !searchResult.isEmpty && totalLines > 0L) {
                    val matching = searchResult.matchingLines()
                    val matchCount = searchResult.totalMatches()
                    if (matchCount > 0L) {
                        // Sample active matches across bins
                        val step = max(1L, matchCount / 200L)
                        var i = 0L
                        while (i < matchCount) {
                            try {
                                val matchLine = matching.select(i)
                                val matchY = ((matchLine.toDouble() / totalLines.toDouble()) * canvasHeight).toFloat()
                                drawRect(
                                    color = colors.minimapMatch,
                                    topLeft = Offset(0f, matchY - 1f),
                                    size = Size(canvasWidth, 2.5f)
                                )
                            } catch (_: Throwable) {
                                break
                            }
                            i += step
                        }
                    }
                }

                // Render Viewport View Window Rect
                if (totalLines > 0L) {
                    val viewportTopRatio = (topLineIndex.toDouble() / totalLines.toDouble()).coerceIn(0.0, 1.0)
                    val viewportBottomRatio = ((topLineIndex + visibleRowCount).toDouble() / totalLines.toDouble()).coerceIn(0.0, 1.0)

                    val viewTopY = (viewportTopRatio * canvasHeight).toFloat()
                    val viewHeight = ((viewportBottomRatio - viewportTopRatio) * canvasHeight).toFloat().coerceAtLeast(6f)

                    // Semi-transparent overlay with cyan border
                    drawRect(
                        color = colors.minimapViewportRect,
                        topLeft = Offset(0f, viewTopY),
                        size = Size(canvasWidth, viewHeight)
                    )
                    drawRect(
                        color = colors.accentPrimary.copy(alpha = 0.53f),
                        topLeft = Offset(0f, viewTopY),
                        size = Size(canvasWidth, 1.5f)
                    )
                    drawRect(
                        color = colors.accentPrimary.copy(alpha = 0.53f),
                        topLeft = Offset(0f, viewTopY + viewHeight - 1.5f),
                        size = Size(canvasWidth, 1.5f)
                    )
                }

                // Render Bookmarks (Cyan horizontal tick marks)
                if (bookmarkManager != null && totalLines > 0L) {
                    for (bm in bookmarkManager.bookmarks) {
                        val yRatio = (bm.lineIndex.toDouble() / totalLines.toDouble()).coerceIn(0.0, 1.0)
                        val yPx = (yRatio * canvasHeight).toFloat()
                        drawRect(
                            color = colors.accentPrimary,
                            topLeft = Offset(0f, (yPx - 1f).coerceAtLeast(0f)),
                            size = Size(canvasWidth, 2.5f)
                        )
                    }
                }

                // Render Temporal Focus Mode dimming overlay
                if (isFocusActive && totalLines > 0L) {
                    val focusTopRatio = (focusStartLine.toDouble() / totalLines.toDouble()).coerceIn(0.0, 1.0)
                    val focusBottomRatio = ((focusEndLine + 1L).toDouble() / totalLines.toDouble()).coerceIn(0.0, 1.0)
                    val focusTopY = (focusTopRatio * canvasHeight).toFloat()
                    val focusBottomY = (focusBottomRatio * canvasHeight).toFloat()

                    if (focusTopY > 0f) {
                        drawRect(
                            color = colors.temporalFocusDimOverlay,
                            topLeft = Offset.Zero,
                            size = Size(canvasWidth, focusTopY)
                        )
                    }
                    if (focusBottomY < canvasHeight) {
                        drawRect(
                            color = colors.temporalFocusDimOverlay,
                            topLeft = Offset(0f, focusBottomY),
                            size = Size(canvasWidth, canvasHeight - focusBottomY)
                        )
                    }

                    // Border indicators at focus boundaries
                    drawRect(
                        color = colors.accentSecondary,
                        topLeft = Offset(0f, focusTopY),
                        size = Size(canvasWidth, 1.5f)
                    )
                    drawRect(
                        color = colors.accentSecondary,
                        topLeft = Offset(0f, focusBottomY - 1.5f),
                        size = Size(canvasWidth, 1.5f)
                    )
                }
            }
        }

        // --- Floating Hover Tooltip Card ---
        if (isHovered && hoverOffset != null && table != null && totalLines > 0L) {
            val binCount = table.binCount()
            val hoveredBin = ((hoverOffset!!.y / canvasHeight) * binCount).toInt().coerceIn(0, binCount - 1)
            val binStart = table.binStartLine(hoveredBin, totalLines)
            val binEnd = table.binEndLine(hoveredBin, totalLines)
            val errs = table.errorCount(hoveredBin)
            val warns = table.warnCount(hoveredBin)
            val infos = table.infoCount(hoveredBin)

            val tooltipY = (hoverOffset!!.y - 40f).coerceIn(10f, (canvasHeight - 110f).coerceAtLeast(10f))
            Box(
                modifier = Modifier
                    .offset { IntOffset((-240).dp.roundToPx(), tooltipY.toInt()) }
                    .width(230.dp)
                    .background(colors.surfaceTooltip, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = "Lines ${formatNumberWithCommas(binStart + 1L)} – ${formatNumberWithCommas(binEnd + 1L)}",
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (errs > 0) {
                            Text("🔴 $errs Err", color = colors.severityError, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (warns > 0) {
                            Text("🟡 $warns Warn", color = colors.severityWarn, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("🔵 $infos Info", color = colors.severityInfo, fontSize = 10.5.sp)
                    }
                    if (errs > 0 || warns > 0) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text("Click to jump to anomaly", color = colors.textLink, fontSize = 9.5.sp)
                    }
                }
            }
        }

        // --- Bottom Legend Strip ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.surfaceGutter.copy(alpha = 0.85f))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(6.dp).background(colors.severityError, CircleShape))
                Spacer(modifier = Modifier.width(3.dp))
                Box(modifier = Modifier.size(6.dp).background(colors.severityWarn, CircleShape))
                Spacer(modifier = Modifier.width(3.dp))
                Box(modifier = Modifier.size(6.dp).background(colors.severityInfo, CircleShape))
            }
        }
    }
}
