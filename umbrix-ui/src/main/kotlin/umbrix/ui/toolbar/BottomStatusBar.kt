package umbrix.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.session.LogSession
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixColors
import umbrix.ui.util.formatByteSize
import umbrix.ui.util.formatNumberWithCommas

/**
 * Bottom workstation status bar displaying telemetry, viewport metrics, and coordinate positions.
 * Periodically polls runtime memory on a 1-second interval to display live JVM heap metrics.
 */
@Composable
fun BottomStatusBar(
    statusMessage: String,
    activeSession: LogSession?,
    topVisibleLine: Long,
    activeTab: WorkspaceTab? = null
) {
    var jvmHeapUsedBytes by remember {
        val runtime = Runtime.getRuntime()
        mutableStateOf(runtime.totalMemory() - runtime.freeMemory())
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            val runtime = Runtime.getRuntime()
            jvmHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(UmbrixColors.surfaceGutter)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (activeTab != null && activeTab.isIndexing) {
            // --- Active Progressive Indexing Indicator (SIMD Tier 1 + Semantic Tier 2) ---
            Text(
                text = activeTab.indexingStatus,
                fontSize = 11.sp,
                color = UmbrixColors.progressText,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Sleek JetBrains-style linear progress bar
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(UmbrixColors.surfaceElevated)
            ) {
                val progressFraction = (activeTab.indexingProgress / 100.0).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(
                            Brush.horizontalGradient(
                                listOf(UmbrixColors.progressGradientStart, UmbrixColors.progressGradientEnd)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "${activeTab.indexingProgress.toInt()}%",
                fontSize = 10.sp,
                color = UmbrixColors.progressText,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = statusMessage,
                fontSize = 11.sp,
                color = UmbrixColors.textMuted
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        val positionLabel = if (activeTab?.selectedLineIndex != null) {
            "Line ${formatNumberWithCommas(activeTab.selectedLineIndex!! + 1L)}"
        } else {
            "Line ${formatNumberWithCommas(topVisibleLine + 1L)}"
        }

        val modeLabel = if (activeTab?.isFilteredView == true) {
            "Mode: Filtered View (${formatNumberWithCommas(activeTab.searchResult?.totalMatches() ?: 0L)} matches)"
        } else {
            "Mode: Compact Stream (60-120 FPS)"
        }

        val memoryLabel = "JVM Heap: ${formatByteSize(jvmHeapUsedBytes)} | 0 bytes log data on GC heap"

        Text(
            text = "$positionLabel | $modeLabel | $memoryLabel",
            fontSize = 11.sp,
            color = UmbrixColors.textDimmed
        )
    }
}
