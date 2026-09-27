package umbrix.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.core.session.LogSession
import umbrix.ui.components.Badge
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatByteSize
import umbrix.ui.util.formatNumberWithCommas
import java.io.File

enum class ExportFormat(val extension: String, val label: String) {
    RAW_LOG(".log", "Raw Monospace Log (*.log)"),
    GZIP_LOG(".log.gz", "Gzip Compressed (*.log.gz)"),
    JSON_LINES(".jsonl", "JSON Lines Array (*.jsonl)")
}

/**
 * Forensic extraction modal dialog for slicing off-heap log segments into standalone sub-files.
 */
@Composable
fun ExportSubFileDialog(
    session: LogSession,
    currentLine: Long,
    sourceFilePath: String? = null,
    onExportRequested: (startLine: Long, endLine: Long, destination: File, format: String) -> Unit,
    onDismiss: () -> Unit
) {
    val totalLines = (session.totalLines() as Number).toLong()
    val totalBytes = session.fileSizeBytes()
    val docTitle = if (!sourceFilePath.isNullOrBlank()) File(sourceFilePath).name else "Log"

    val initialStart = (currentLine + 1L).coerceIn(1L, totalLines.coerceAtLeast(1L))
    val initialEnd = minOf(initialStart + 9_999L, totalLines.coerceAtLeast(1L))
    var startLineText by remember(currentLine) { mutableStateOf(initialStart.toString()) }
    var endLineText by remember(currentLine) { mutableStateOf(initialEnd.toString()) }
    var selectedFormat by remember { mutableStateOf(ExportFormat.RAW_LOG) }

    val initialDestination = remember(sourceFilePath) {
        if (!sourceFilePath.isNullOrBlank()) {
            val src = File(sourceFilePath)
            val parent = src.parentFile?.takeIf { it.exists() && it.canWrite() }
                ?: File(System.getProperty("user.home"))
            val baseName = src.nameWithoutExtension.ifBlank { "export" }
            File(parent, "${baseName}_slice.log").absolutePath
        } else {
            System.getProperty("user.home") + File.separator + "export_slice.log"
        }
    }
    var destinationPath by remember(initialDestination) { mutableStateOf(initialDestination) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val startLine = startLineText.trim().toLongOrNull() ?: -1L
    val endLine = endLineText.trim().toLongOrNull() ?: -1L
    val lineCount = if (startLine in 1..endLine && endLine <= totalLines) (endLine - startLine + 1L) else 0L

    // Linear byte approximation: byteRatio = (lines / totalLines) * totalBytes
    val estimatedBytes = if (totalLines > 0L && lineCount > 0L) {
        ((lineCount.toDouble() / totalLines.toDouble()) * totalBytes).toLong()
    } else 0L

    fun handleExport() {
        if (startLine < 1L || endLine < startLine || endLine > totalLines) {
            validationError = "Invalid range: must be between 1 and ${formatNumberWithCommas(totalLines)}"
            return
        }
        val targetFile = File(destinationPath)
        if (targetFile.isDirectory) {
            validationError = "Destination path must include a filename, not just a folder."
            return
        }
        onExportRequested(startLine - 1L, endLine - 1L, targetFile, selectedFormat.extension)
        onDismiss()
    }

    UmbrixModalCard(
        title = "Export Sub-File — $docTitle",
        onDismiss = onDismiss,
        width = 540.dp,
        showCloseButton = true,
        isScrollable = true,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            DefaultButton(
                onClick = { handleExport() },
                enabled = lineCount > 0L && destinationPath.isNotBlank()
            ) {
                Text("Export Slice")
            }
        }
    ) {
        Text(
            text = "Specify line slice bounds to extract from memory-mapped off-heap buffer:",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Line Range Inputs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start Line:", fontSize = 11.5.sp, color = UmbrixTheme.colors.textMuted)
                Spacer(modifier = Modifier.height(4.dp))
                TextField(
                    value = startLineText,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() }) {
                            startLineText = it
                            validationError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("End Line:", fontSize = 11.5.sp, color = UmbrixTheme.colors.textMuted)
                Spacer(modifier = Modifier.height(4.dp))
                TextField(
                    value = endLineText,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() }) {
                            endLineText = it
                            validationError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Estimated Output Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UmbrixTheme.colors.surfacePanel, shape = RoundedCornerShape(6.dp))
                .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Estimated Payload:",
                fontSize = 11.5.sp,
                color = UmbrixTheme.colors.textSecondary
            )

            Badge(
                label = "${formatNumberWithCommas(lineCount)} lines (~${formatByteSize(estimatedBytes)})",
                textColor = UmbrixTheme.colors.accentPrimary,
                backgroundColor = UmbrixTheme.colors.surfaceElevated
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Export Format Selector
        Text("Export Format:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UmbrixTheme.colors.textPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (format in ExportFormat.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedFormat = format },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(format.label, fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Destination Path Field
        Text("Destination File Path:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UmbrixTheme.colors.textPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = destinationPath,
            onValueChange = {
                destinationPath = it
                validationError = null
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (validationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = validationError!!,
                fontSize = 11.sp,
                color = UmbrixTheme.colors.badgeErrorText
            )
        }
    }
}
