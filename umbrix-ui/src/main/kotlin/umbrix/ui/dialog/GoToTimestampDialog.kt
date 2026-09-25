package umbrix.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.core.session.LogSession
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatEpochMicros
import umbrix.ui.util.formatNumberWithCommas
import umbrix.ui.util.parseTimestampStringToMicros

enum class JumpMode {
    LINE,
    TIMESTAMP
}

/**
 * Standardized modal dialog for line and temporal wall-clock timestamp seeking.
 * Activated via Ctrl+G / Cmd+G.
 */
@Composable
fun GoToTimestampDialog(
    session: LogSession,
    currentLine: Long,
    onGoToLine: (targetPhysicalLine: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val totalLines = (session.totalLines() as Number).toLong()
    val isTemporalAvailable = session.isTemporalDataAvailable()
    val firstTimestamp = session.firstTimestamp()
    val lastTimestamp = session.lastTimestamp()

    var activeMode by remember { mutableStateOf(JumpMode.LINE) }
    var lineInputText by remember { 
        mutableStateOf((currentLine + 1L).coerceIn(1L, totalLines.coerceAtLeast(1L)).toString()) 
    }
    var timestampInputText by remember {
        mutableStateOf(
            if (firstTimestamp.isDefined) formatEpochMicros((firstTimestamp.get() as Number).toLong(), includeDate = true) else ""
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(activeMode) {
        try {
            focusRequester.requestFocus()
        } catch (_: Throwable) {}
    }

    fun submitLine() {
        val trimmed = lineInputText.trim()
        val parsed = trimmed.toLongOrNull()
        if (parsed == null || parsed < 1L || parsed > totalLines) {
            errorMessage = "Please enter a valid line between 1 and ${formatNumberWithCommas(totalLines)}"
        } else {
            onGoToLine(parsed - 1L)
            onDismiss()
        }
    }

    fun submitTimestamp() {
        val baseDateMicros = if (firstTimestamp.isDefined) (firstTimestamp.get() as Number).toLong() else null
        val parsedMicros = parseTimestampStringToMicros(timestampInputText, baseDateMicros)
        if (parsedMicros == null) {
            errorMessage = "Invalid timestamp. Use 'HH:mm:ss', 'HH:mm:ss.SSS', or 'YYYY-MM-DD HH:mm:ss'"
        } else {
            val targetLine = (session.seekNearestLine(parsedMicros) as Number).toLong()
            onGoToLine(targetLine)
            onDismiss()
        }
    }

    fun submit() {
        if (activeMode == JumpMode.LINE) {
            submitLine()
        } else {
            submitTimestamp()
        }
    }

    UmbrixModalCard(
        title = "Go To Line / Timestamp",
        onDismiss = onDismiss,
        width = 520.dp,
        showCloseButton = true,
        isScrollable = false,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            DefaultButton(
                onClick = { submit() },
                enabled = if (activeMode == JumpMode.LINE) {
                    lineInputText.isNotBlank() && totalLines > 0L
                } else {
                    timestampInputText.isNotBlank() && isTemporalAvailable
                }
            ) {
                Text("Seek & Jump")
            }
        }
    ) {
        // Mode Selector Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Target Mode:",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSecondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        activeMode = JumpMode.LINE
                        errorMessage = null
                    },
                    enabled = activeMode != JumpMode.LINE
                ) {
                    Text("Physical Line #")
                }

                OutlinedButton(
                    onClick = {
                        if (isTemporalAvailable) {
                            activeMode = JumpMode.TIMESTAMP
                            errorMessage = null
                        }
                    },
                    enabled = isTemporalAvailable && activeMode != JumpMode.TIMESTAMP
                ) {
                    Text("Temporal Timestamp")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (activeMode == JumpMode.LINE) {
            Text(
                text = "Enter line number (1 – ${formatNumberWithCommas(totalLines)}):",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = lineInputText,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() }) {
                        lineInputText = newValue
                        errorMessage = null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && 
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            submit()
                            true
                        } else false
                    }
            )
        } else {
            Text(
                text = "Enter timestamp (e.g. '14:02:15.200' or '2026-09-23 14:02:15'):",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = timestampInputText,
                onValueChange = { newValue ->
                    timestampInputText = newValue
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && 
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            submit()
                            true
                        } else false
                    }
            )

            if (firstTimestamp.isDefined && lastTimestamp.isDefined) {
                Spacer(modifier = Modifier.height(6.dp))
                val startStr = formatEpochMicros((firstTimestamp.get() as Number).toLong(), includeDate = true)
                val endStr = formatEpochMicros((lastTimestamp.get() as Number).toLong(), includeDate = true)
                Text(
                    text = "Temporal Span: $startStr  →  $endStr",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                fontSize = 11.sp,
                color = UmbrixTheme.colors.badgeErrorText
            )
        }
    }
}
