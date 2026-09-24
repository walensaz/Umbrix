package umbrix.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import umbrix.ui.theme.UmbrixTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 64-bit precision vertical scrollbar engineered for multi-gigabyte log viewing with billions of lines.
 * Uses 64-bit integer line arithmetic to avoid 32-bit floating-point precision loss and overflow.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Custom64BitScrollbar(
    totalLines: Long,
    visibleRowCount: Int,
    topLineIndex: Long,
    onScrollToLine: (Long) -> Unit
) {
    val density = LocalDensity.current
    var isHovered by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .width(14.dp)
            .fillMaxHeight()
            .background(UmbrixTheme.colors.surfaceScrollbarTrack)
    ) {
        val totalTrackHeightPx = constraints.maxHeight.toFloat()
        if (totalTrackHeightPx <= 0f) return@BoxWithConstraints

        val maxTopLine = max(1L, totalLines - visibleRowCount)
        val visibleRatio = (visibleRowCount.toDouble() / totalLines.toDouble()).coerceIn(0.0, 1.0)
        val minThumbHeightPx = 32f
        val calculatedThumbHeightPx = (totalTrackHeightPx * visibleRatio).toFloat()
        val thumbHeightPx = calculatedThumbHeightPx.coerceIn(minThumbHeightPx, totalTrackHeightPx)
        val scrollableTrackPx = (totalTrackHeightPx - thumbHeightPx).coerceAtLeast(1f)

        val scrollProgress = (topLineIndex.toDouble() / maxTopLine.toDouble()).coerceIn(0.0, 1.0)
        val thumbOffsetY = (scrollProgress * scrollableTrackPx).toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                .pointerInput(totalLines, visibleRowCount, totalTrackHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isDragging = true

                        val clickY = down.position.y
                        val isInsideThumb = clickY >= thumbOffsetY && clickY <= (thumbOffsetY + thumbHeightPx)

                        var currentTargetLine: Long = if (isInsideThumb) {
                            topLineIndex
                        } else {
                            val jumpRatio = ((clickY - thumbHeightPx / 2f) / scrollableTrackPx).coerceIn(0f, 1f)
                            val jumpedLine = (jumpRatio.toDouble() * maxTopLine.toDouble()).toLong().coerceIn(0L, maxTopLine)
                            onScrollToLine(jumpedLine)
                            jumpedLine
                        }

                        var lineDeltaAccumulator = 0.0

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                isDragging = false
                                break
                            }

                            val dragDeltaY = change.position.y - change.previousPosition.y
                            if (dragDeltaY != 0f) {
                                change.consume()
                                lineDeltaAccumulator += dragDeltaY.toDouble() * (maxTopLine.toDouble() / scrollableTrackPx.toDouble())
                                val deltaLines = lineDeltaAccumulator.toLong()
                                if (deltaLines != 0L) {
                                    currentTargetLine = (currentTargetLine + deltaLines).coerceIn(0L, maxTopLine)
                                    onScrollToLine(currentTargetLine)
                                    lineDeltaAccumulator -= deltaLines.toDouble()
                                }
                            }
                        }
                        isDragging = false
                    }
                }
        ) {
            val thumbColor = when {
                isDragging -> UmbrixTheme.colors.scrollbarThumbDragging
                isHovered -> UmbrixTheme.colors.scrollbarThumbHovered
                else -> UmbrixTheme.colors.scrollbarThumbResting
            }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = with(density) { 2.dp.roundToPx() },
                            y = thumbOffsetY.toInt()
                        )
                    }
                    .width(10.dp)
                    .height((thumbHeightPx / density.density).dp)
                    .background(
                        color = thumbColor,
                        shape = RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}
