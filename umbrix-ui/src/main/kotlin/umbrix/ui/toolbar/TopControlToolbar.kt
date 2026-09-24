package umbrix.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.core.session.LogSession
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixColors
import umbrix.ui.util.formatByteSize
import umbrix.ui.util.formatNumberWithCommas

/**
 * Main application top control bar with file loading, search toggling, telemetry badges, and zoom controls.
 */
@Composable
fun TopControlToolbar(
    filePathText: String,
    onFilePathTextChange: (String) -> Unit,
    onOpenFileClicked: () -> Unit,
    onLoadPathClicked: () -> Unit,
    isIndexing: Boolean,
    session: LogSession?,
    fontSizeSp: Float,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    isSearchBarOpen: Boolean,
    onToggleSearchBar: () -> Unit,
    isTimeScrubberOpen: Boolean = false,
    onToggleTimeScrubber: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixColors.surfacePanel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DefaultButton(
            onClick = onOpenFileClicked,
            enabled = !isIndexing
        ) {
            Text("Open Log File...")
        }

        Spacer(modifier = Modifier.width(6.dp))

        OutlinedButton(
            onClick = onOpenSettings,
            enabled = !isIndexing
        ) {
            Text("⚙ Settings")
        }

        Spacer(modifier = Modifier.width(10.dp))

        TextField(
            value = filePathText,
            onValueChange = onFilePathTextChange,
            modifier = Modifier.width(340.dp),
            enabled = !isIndexing
        )

        Spacer(modifier = Modifier.width(6.dp))

        OutlinedButton(
            onClick = onLoadPathClicked,
            enabled = !isIndexing && filePathText.isNotBlank()
        ) {
            Text("Load")
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Search Trigger Button
        OutlinedButton(
            onClick = onToggleSearchBar,
            enabled = session != null
        ) {
            Text("🔍 Search (Ctrl+F)")
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Time Range Scrubber Button
        OutlinedButton(
            onClick = onToggleTimeScrubber,
            enabled = session != null
        ) {
            Text("🕐 Time (Ctrl+T)")
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (session != null) {
            Badge(
                label = "${formatNumberWithCommas(session.totalLines())} lines",
                textColor = UmbrixColors.badgeLinesText,
                backgroundColor = UmbrixColors.badgeLinesBackground
            )
            Spacer(modifier = Modifier.width(8.dp))

            Badge(
                label = formatByteSize(session.fileSizeBytes()),
                textColor = UmbrixColors.badgeSizeText,
                backgroundColor = UmbrixColors.badgeSizeBackground
            )
            Spacer(modifier = Modifier.width(8.dp))

            Badge(
                label = "${session.simdVectorWidth() * 8}-bit SIMD",
                textColor = UmbrixColors.badgeSimdText,
                backgroundColor = UmbrixColors.badgeSimdBackground
            )
            Spacer(modifier = Modifier.width(8.dp))

            Badge(
                label = "${session.indexingDurationMs()} ms index",
                textColor = UmbrixColors.badgeDurationText,
                backgroundColor = UmbrixColors.badgeDurationBackground
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Zoom / Font size controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onDecreaseFontSize,
                enabled = fontSizeSp > 10f
            ) {
                Text("-", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${fontSizeSp.toInt()} pt",
                fontSize = 12.sp,
                color = UmbrixColors.textSecondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = onIncreaseFontSize,
                enabled = fontSizeSp < 26f
            ) {
                Text("+", fontWeight = FontWeight.Bold)
            }
        }
    }
}
