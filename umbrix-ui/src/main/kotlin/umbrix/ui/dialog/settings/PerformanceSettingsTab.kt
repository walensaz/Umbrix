package umbrix.ui.dialog.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: SIMD Vector API acceleration, max shaped characters safety clamp, and latency gap thresholds.
 */
@Composable
fun PerformanceSettingsTab(
    enableSimdAcceleration: Boolean,
    onEnableSimdAccelerationChange: (Boolean) -> Unit,
    maxShapedCharsPerLine: Int,
    onMaxShapedCharsPerLineChange: (Int) -> Unit,
    isLatencyGapHighlightingEnabled: Boolean,
    onIsLatencyGapHighlightingEnabledChange: (Boolean) -> Unit,
    latencyGapThresholdMs: Long,
    onLatencyGapThresholdMsChange: (Long) -> Unit,
    latencyGapSevereThresholdMs: Long,
    onLatencyGapSevereThresholdMsChange: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Performance, SIMD & Memory Invariants",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(
                label = "Hardware Vector Acceleration Active",
                textColor = UmbrixTheme.colors.badgeSimdText,
                backgroundColor = UmbrixTheme.colors.badgeSimdBackground
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = enableSimdAcceleration,
                onCheckedChange = onEnableSimdAccelerationChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enable Java Vector API SIMD (AVX-512 / AVX2)")
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Max Characters Per Line (Safety Clamp):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (limit in listOf(2048, 4096, 8192)) {
                    OutlinedButton(
                        onClick = { onMaxShapedCharsPerLineChange(limit) },
                        enabled = maxShapedCharsPerLine != limit
                    ) {
                        Text("${limit} chars")
                    }
                }
            }
            Text(
                text = "Protects Skia text shaper from multi-megabyte minified payloads.",
                fontSize = 11.sp,
                color = UmbrixTheme.colors.textMuted
            )
        }

        Divider(Orientation.Horizontal)

        Text(
            text = "Temporal Navigation & Latency Gaps",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = UmbrixTheme.colors.textPrimary
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isLatencyGapHighlightingEnabled,
                onCheckedChange = onIsLatencyGapHighlightingEnabledChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Highlight Latency Gaps in Viewport Gutter (Amber/Red dots)")
        }

        if (isLatencyGapHighlightingEnabled) {
            Column(modifier = Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Warning Gap Threshold (Amber):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (ms in listOf(500L, 1000L, 2000L, 5000L)) {
                        OutlinedButton(
                            onClick = { onLatencyGapThresholdMsChange(ms) },
                            enabled = latencyGapThresholdMs != ms
                        ) {
                            Text("${ms}ms")
                        }
                    }
                }

                Text("Severe Gap Threshold (Red Outage):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (ms in listOf(3000L, 5000L, 10000L)) {
                        OutlinedButton(
                            onClick = { onLatencyGapSevereThresholdMsChange(ms) },
                            enabled = latencyGapSevereThresholdMs != ms
                        ) {
                            Text("${ms}ms")
                        }
                    }
                }
            }
        }
    }
}
