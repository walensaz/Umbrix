package umbrix.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.simd.VectorScanner
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme

/**
 * Standardized About dialog presenting platform runtime and compiler telemetry.
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit
) {
    val javaVersion = System.getProperty("java.version") ?: "Unknown JVM"
    val osName = System.getProperty("os.name") ?: "Unknown OS"
    val osArch = System.getProperty("os.arch") ?: "Unknown Arch"
    val simdByteWidth = try { VectorScanner.vectorByteWidth() } catch (_: Throwable) { 0 }

    UmbrixModalCard(
        title = "About Umbrix",
        onDismiss = onDismiss,
        width = 460.dp,
        showCloseButton = true,
        isScrollable = false,
        actions = {
            DefaultButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand Icon Tile
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(UmbrixTheme.colors.accentPrimary)
                    .border(2.dp, UmbrixTheme.colors.accentSecondary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "U",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Umbrix Workstation",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = UmbrixTheme.colors.textPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Industrial-Grade High-Velocity Log Analysis Platform",
                fontSize = 11.5.sp,
                color = UmbrixTheme.colors.textSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Metadata Card Grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surfacePanel, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AboutInfoRow("Version", "0.1.0-SNAPSHOT (Beta Ready)")
                AboutInfoRow("Target Runtime", "Azul OpenJDK $javaVersion")
                AboutInfoRow("Operating System", "$osName ($osArch)")
                AboutInfoRow("Core Engine", "Scala 3.3.8 LTS + Kotlin Compose 1.7.3")
                AboutInfoRow(
                    label = "SIMD Vector Width",
                    value = if (simdByteWidth > 0) "${simdByteWidth * 8}-bit (${simdByteWidth}B AVX/NEON)" else "Scalar Fallback"
                )
                AboutInfoRow("Memory Invariant", "0 Bytes Heap Log Ingestion (FFM)")
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Copyright © 2026 Umbrix Project. Open-source workstation.",
                fontSize = 11.sp,
                color = UmbrixTheme.colors.textMuted
            )
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = UmbrixTheme.colors.textMuted
        )
        Text(
            text = value,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = UmbrixTheme.colors.textPrimary
        )
    }
}
