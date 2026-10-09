package umbrix.ui.dialog.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: Async background analyzers and extension plugins SPI.
 */
@Composable
fun PluginsSettingsTab() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Async Analyzers & Plugins SPI",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )
        Text(
            text = "Background analysis extensions that run asynchronously without blocking the viewport.",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textMuted
        )

        PluginCard(
            name = "GPU Seismograph Minimap",
            status = "Active (Built-in)",
            description = "Real-time 1,000-bin severity density mipmap with zero GC heap ingestion."
        )

        PluginCard(
            name = "Latency Gap & Stall Detector",
            status = "Active (Built-in)",
            description = "Detects unlogged execution freezes and thread starvation gaps exceeding 1,000ms."
        )

        PluginCard(
            name = "JVM Garbage Collection Analyzer",
            status = "Planned",
            description = "Inspects GC pause distribution, allocation rates, and stop-the-world spikes."
        )
    }
}

@Composable
private fun PluginCard(
    name: String,
    status: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfaceElevated, shape = RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = UmbrixTheme.colors.textPrimary)
            Spacer(modifier = Modifier.weight(1f))
            Badge(
                label = status,
                textColor = if (status.startsWith("Active")) UmbrixTheme.colors.badgeSimdText else UmbrixTheme.colors.textMuted,
                backgroundColor = if (status.startsWith("Active")) UmbrixTheme.colors.badgeSimdBackground else UmbrixTheme.colors.surfaceGutter
            )
        }
        Text(description, fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
    }
}
