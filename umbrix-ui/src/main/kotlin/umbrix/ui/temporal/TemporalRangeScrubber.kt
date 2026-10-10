package umbrix.ui.temporal

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.session.LogSession
import umbrix.ui.components.Badge
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatDurationMicros
import umbrix.ui.util.formatEpochMicros
import umbrix.ui.util.formatNumberWithCommas
import kotlin.math.abs
import kotlin.math.max

/**
 * Dual-Handle Time Range Scrubber (Pillar 4).
 *
 * Interactive range slider that maps wall-clock timestamps across 25GB+ logs in O(log N) time.
 * Supports:
 * - Dual draggable knobs bounding an isolated time window
 * - Latency gap indicators (amber/red ticks) marked directly on the track
 * - In-place viewport trimming ("Focus Mode")
 * - 0 bytes GC heap text ingestion
 */
@Composable
fun TemporalRangeScrubber(
    tab: WorkspaceTab,
    session: LogSession,
    onTrimView: (startLine: Long, endLine: Long, startMicros: Long, endMicros: Long) -> Unit,
    onClearTrim: () -> Unit,
    onClose: () -> Unit
) {
    val colors = UmbrixTheme.colors
    val firstTimestamp = session.firstTimestamp()
    val lastTimestamp = session.lastTimestamp()

    if (!session.isTemporalDataAvailable() || !firstTimestamp.isDefined || !lastTimestamp.isDefined) {
        val isAnalyzing = !session.isTemporalIndexComplete()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfacePanel)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAnalyzing) {
                Text(
                    text = "🕐 Temporal index is analyzing log timestamps in background...",
                    fontSize = 11.5.sp,
                    color = colors.progressText
                )
            } else {
                Text(
                    text = "⚠️ No recognized timestamps detected in this file. (Supported formats: ISO-8601, Unix Epoch, Syslog RFC 3164, Apache CLF, Dotted dates)",
                    fontSize = 11.5.sp,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) {
                Text("✕ Close")
            }
        }
        return
    }

    val minMicros = (firstTimestamp.get() as Number).toLong()
    val maxMicros = (lastTimestamp.get() as Number).toLong()
    val totalSpanMicros = max(1L, maxMicros - minMicros)
    val isSingleDay = totalSpanMicros < 86_400_000_000L

    val startFractionState = remember(tab.filePath) {
        val initialStart = if (tab.isTemporalFocusActive && tab.temporalFocusStartMicros >= minMicros) {
            ((tab.temporalFocusStartMicros - minMicros).toDouble() / totalSpanMicros.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        mutableStateOf(initialStart.toFloat())
    }

    val endFractionState = remember(tab.filePath) {
        val initialEnd = if (tab.isTemporalFocusActive && tab.temporalFocusEndMicros <= maxMicros && tab.temporalFocusEndMicros > minMicros) {
            ((tab.temporalFocusEndMicros - minMicros).toDouble() / totalSpanMicros.toDouble()).coerceIn(0.0, 1.0)
        } else 1.0
        mutableStateOf(initialEnd.toFloat())
    }

    LaunchedEffect(tab.isTemporalFocusActive, tab.temporalFocusStartMicros, tab.temporalFocusEndMicros, minMicros, maxMicros, totalSpanMicros) {
        if (tab.isTemporalFocusActive && tab.temporalFocusStartMicros >= minMicros) {
            startFractionState.value = ((tab.temporalFocusStartMicros - minMicros).toDouble() / totalSpanMicros.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else if (!tab.isTemporalFocusActive) {
            startFractionState.value = 0f
        }
        if (tab.isTemporalFocusActive && tab.temporalFocusEndMicros <= maxMicros && tab.temporalFocusEndMicros > minMicros) {
            endFractionState.value = ((tab.temporalFocusEndMicros - minMicros).toDouble() / totalSpanMicros.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else if (!tab.isTemporalFocusActive) {
            endFractionState.value = 1f
        }
    }

    val startFraction = startFractionState.value
    val endFraction = endFractionState.value

    val currentStartMicros = minMicros + (startFraction.toDouble() * totalSpanMicros.toDouble()).toLong()
    val currentEndMicros = minMicros + (endFraction.toDouble() * totalSpanMicros.toDouble()).toLong()

    val lineBounds = remember(startFraction, endFraction, minMicros, maxMicros, totalSpanMicros) {
        session.seekLineRangeBounds(currentStartMicros, currentEndMicros)
    }
    val selectedLineCount = max(0L, lineBounds[1] - lineBounds[0] + 1L)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfacePanel)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // --- Header Row: Selected time range info, Trim View, Clear, Close ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🕐 Time Range Scrubber",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )

            Spacer(modifier = Modifier.width(10.dp))

            val showDate = totalSpanMicros >= 86_400_000_000L ||
                (currentStartMicros / 86_400_000_000L != currentEndMicros / 86_400_000_000L)
            val startStr = formatEpochMicros(currentStartMicros, includeDate = showDate)
            val endStr = formatEpochMicros(currentEndMicros, includeDate = showDate)
            Text(
                text = "Selected: $startStr  →  $endStr",
                fontSize = 11.5.sp,
                color = colors.textEditor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.width(8.dp))

            Badge(
                label = "${formatNumberWithCommas(selectedLineCount)} lines",
                textColor = colors.badgeLinesText,
                backgroundColor = colors.badgeLinesBackground
            )

            if (tab.isTemporalFocusActive) {
                Spacer(modifier = Modifier.width(6.dp))
                Badge(
                    label = "Focus Mode Active",
                    textColor = colors.temporalFocusBadgeText,
                    backgroundColor = colors.temporalFocusBadgeBackground
                )
            }

            val isSingleDay = totalSpanMicros < 86_400_000_000L
            if (isSingleDay) {
                Spacer(modifier = Modifier.width(6.dp))
                Badge(
                    label = "Single Day (${formatDurationMicros(totalSpanMicros)})",
                    textColor = colors.accentPrimary,
                    backgroundColor = colors.surfaceElevated
                )
            }

            if (!session.isLogChronologicallyOrdered()) {
                Spacer(modifier = Modifier.width(6.dp))
                Badge(
                    label = "Out-of-Order (Auto-Resolved)",
                    textColor = colors.latencyGapAmber,
                    backgroundColor = colors.surfaceElevated
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            DefaultButton(
                onClick = {
                    onTrimView(lineBounds[0], lineBounds[1], currentStartMicros, currentEndMicros)
                },
                enabled = selectedLineCount > 0L
            ) {
                Text(if (tab.isTemporalFocusActive) "Update Focus" else "Trim View (Focus)")
            }

            if (tab.isTemporalFocusActive) {
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(
                    onClick = {
                        startFractionState.value = 0f
                        endFractionState.value = 1f
                        onClearTrim()
                    }
                ) {
                    Text("Clear Focus")
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(onClick = onClose) {
                Text("✕")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- Track & Dual-Handle Slider ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        ) {
            val trackWidth = constraints.maxWidth.toFloat()
            val handleRadiusPx = 7.dp.value * 2f // roughly 14px

            val gapResult = session.latencyGapResult()
            val totalLines = (session.totalLines() as Number).toLong()

            // Pointer drag gesture detector for both handles
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(trackWidth, totalSpanMicros, startFractionState, endFractionState) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val clickRatio = (down.position.x / trackWidth).coerceIn(0f, 1f)

                            // Pick whichever handle is closer to the initial click
                            val distToStart = abs(clickRatio - startFractionState.value)
                            val distToEnd = abs(clickRatio - endFractionState.value)
                            val isDraggingStart = distToStart <= distToEnd

                            if (isDraggingStart) {
                                startFractionState.value = clickRatio.coerceAtMost(endFractionState.value)
                            } else {
                                endFractionState.value = clickRatio.coerceAtLeast(startFractionState.value)
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()

                                val currentRatio = (change.position.x / trackWidth).coerceIn(0f, 1f)
                                if (isDraggingStart) {
                                    startFractionState.value = currentRatio.coerceAtMost(endFractionState.value)
                                } else {
                                    endFractionState.value = currentRatio.coerceAtLeast(startFractionState.value)
                                }
                            }
                        }
                    }
            ) {
                // Background Track with Latency Gap Ticks
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .align(Alignment.Center)
                ) {
                    val trackHeight = size.height

                    // Track base
                    drawRoundRect(
                        color = colors.scrubberTrack,
                        topLeft = Offset(0f, 0f),
                        size = Size(trackWidth, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f, trackHeight / 2f)
                    )

                    // Latency gap tick marks
                    if (gapResult != null && !gapResult.isEmpty && totalLines > 0L) {
                        val gapCount = gapResult.gapCount()
                        val sampleStep = max(1, gapCount / 100)
                        var g = 0
                        while (g < gapCount) {
                            val gapLine = gapResult.gapLineAt(g)
                            val gapDuration = gapResult.gapDurationAtIndex(g)
                            val xPos = ((gapLine.toDouble() / totalLines.toDouble()) * trackWidth).toFloat()

                            val tickColor = if (gapDuration >= 5_000_000L) {
                                colors.latencyGapRed
                            } else {
                                colors.latencyGapAmber
                            }

                            drawRect(
                                color = tickColor,
                                topLeft = Offset(xPos - 1f, 0f),
                                size = Size(2.5f, trackHeight)
                            )
                            g += sampleStep
                        }
                    }

                    // Active range gradient fill
                    val startX = startFraction * trackWidth
                    val endX = endFraction * trackWidth
                    val fillWidth = max(2f, endX - startX)

                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(colors.scrubberFillStart, colors.scrubberFillEnd),
                            startX = startX,
                            endX = endX
                        ),
                        topLeft = Offset(startX, 0f),
                        size = Size(fillWidth, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f, trackHeight / 2f)
                    )
                }

                // Left Handle Knob
                val leftHandleOffsetPx = (startFraction * trackWidth) - 7.dp.value
                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftHandleOffsetPx.toInt(), 7.dp.roundToPx()) }
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(colors.scrubberHandle)
                        .border(1.5.dp, colors.scrubberHandleBorder, CircleShape)
                )

                // Right Handle Knob
                val rightHandleOffsetPx = (endFraction * trackWidth) - 7.dp.value
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rightHandleOffsetPx.toInt(), 7.dp.roundToPx()) }
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(colors.scrubberHandle)
                        .border(1.5.dp, colors.scrubberHandleBorder, CircleShape)
                )
            }
        }

        // --- Edge Labels (Start & End Times of document) ---
        val showEdgeDates = totalSpanMicros >= 86_400_000_000L
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatEpochMicros(minMicros, includeDate = showEdgeDates),
                fontSize = 10.sp,
                color = colors.textMuted
            )
            Text(
                text = "Total Duration: ${formatDurationMicros(totalSpanMicros)}${if (isSingleDay) " (Single Day)" else ""}",
                fontSize = 10.sp,
                color = colors.textDimmed
            )
            Text(
                text = formatEpochMicros(maxMicros, includeDate = showEdgeDates),
                fontSize = 10.sp,
                color = colors.textMuted
            )
        }
    }
}
