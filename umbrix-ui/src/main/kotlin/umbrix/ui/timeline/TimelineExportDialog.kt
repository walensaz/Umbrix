package umbrix.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

enum class TimelineExportFormat(val extension: String, val label: String) {
    MARKDOWN(".md", "Markdown Report (*.md)"),
    CONSOLIDATED_LOG(".log", "Consolidated Log (*.log)"),
    JSON_ARCHIVE(".json", "JSON Evidence Archive (*.json)")
}

/**
 * Modal dialog for exporting the Incident Timeline to Markdown, raw log, or JSON.
 */
@Composable
fun TimelineExportDialog(
    events: List<TimelineEvent>,
    onCopyToClipboard: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf(TimelineExportFormat.MARKDOWN) }
    var reportTitle by remember { mutableStateOf("Production Incident Timeline Analysis") }

    val exportedContent = remember(selectedFormat, reportTitle, events) {
        when (selectedFormat) {
            TimelineExportFormat.MARKDOWN -> IncidentReportExporter.generateMarkdownReport(events, reportTitle)
            TimelineExportFormat.CONSOLIDATED_LOG -> IncidentReportExporter.generateConsolidatedLog(events)
            TimelineExportFormat.JSON_ARCHIVE -> IncidentReportExporter.generateJsonArchive(events)
        }
    }

    UmbrixModalCard(
        title = "Export Incident Timeline (${events.size} Events)",
        onDismiss = onDismiss,
        width = 800.dp,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            DefaultButton(onClick = {
                onCopyToClipboard(exportedContent)
                onDismiss()
            }) {
                Text("Copy to Clipboard")
            }
            DefaultButton(onClick = {
                val dialog = FileDialog(null as Frame?, "Save Incident Report", FileDialog.SAVE)
                dialog.file = "incident-timeline${selectedFormat.extension}"
                dialog.isVisible = true
                val dir = dialog.directory
                val filename = dialog.file
                if (dir != null && filename != null) {
                    val file = File(dir, filename)
                    file.writeText(exportedContent, Charsets.UTF_8)
                }
                onDismiss()
            }) {
                Text("Save to File...")
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            // Header configuration: Title & Format Picker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedFormat == TimelineExportFormat.MARKDOWN) {
                    TextField(
                        value = reportTitle,
                        onValueChange = { reportTitle = it },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TimelineExportFormat.entries.forEach { format ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            RadioButton(
                                selected = selectedFormat == format,
                                onClick = { selectedFormat = format }
                            )
                            Text(format.label, fontSize = 11.sp, color = UmbrixTheme.colors.textPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Live Preview Monospace Pane
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surface, RoundedCornerShape(6.dp))
                    .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
                    item {
                        Text(
                            text = exportedContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = UmbrixTheme.colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}
