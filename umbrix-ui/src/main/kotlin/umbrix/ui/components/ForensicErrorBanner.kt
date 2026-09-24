package umbrix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.core.diagnostics.ForensicErrorReport
import umbrix.ui.theme.UmbrixTheme
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Prominent, non-crashing in-app forensic notification banner alerting the user to
 * intercepted exceptions with diagnostic report details, clipboard export, and dismissal.
 */
@Composable
fun ForensicErrorBanner(
    report: ForensicErrorReport,
    onDismiss: () -> Unit
) {
    var copyStatus by remember { mutableStateOf("Copy Diagnostic Report") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.badgeErrorBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "⚠",
            fontSize = 18.sp,
            color = UmbrixTheme.colors.errorIconColor
        )
        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Workstation Exception Intercepted: ${report.exceptionClassName()}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = UmbrixTheme.colors.errorClassNameColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "[Thread: ${report.threadName()} #${report.threadId()}]",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.errorThreadColor
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = report.errorMessage(),
                fontSize = 12.sp,
                color = UmbrixTheme.colors.errorMessageColor,
                maxLines = 2
            )
            if (report.hasReportFile()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Diagnostic report saved to: ${report.reportFilePathString()}",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.errorFilePathColor
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        OutlinedButton(
            onClick = {
                try {
                    val selection = StringSelection(report.formattedSummaryText())
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    copyStatus = "✓ Copied to Clipboard!"
                } catch (_: Exception) {
                    copyStatus = "Failed to copy"
                }
            }
        ) {
            Text(copyStatus, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        DefaultButton(onClick = onDismiss) {
            Text("Dismiss", fontSize = 12.sp)
        }
    }
}
