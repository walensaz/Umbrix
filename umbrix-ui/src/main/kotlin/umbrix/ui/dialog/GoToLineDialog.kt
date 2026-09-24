package umbrix.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatNumberWithCommas

/**
 * Modal dialog for sub-millisecond seeking and jumping directly to a physical line number.
 * Activated via Ctrl+G / Cmd+G.
 */
@Composable
fun GoToLineDialog(
    totalLines: Long,
    currentLine: Long,
    onGoToLine: (targetPhysicalLine: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var lineInputText by remember { mutableStateOf((currentLine + 1L).coerceIn(1L, totalLines.coerceAtLeast(1L)).toString()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Throwable) {}
    }

    fun submit() {
        val trimmed = lineInputText.trim()
        val parsed = trimmed.toLongOrNull()
        if (parsed == null || parsed < 1L || parsed > totalLines) {
            errorMessage = "Please enter a valid line between 1 and ${formatNumberWithCommas(totalLines)}"
        } else {
            onGoToLine(parsed - 1L)
        }
    }

    // Modal Scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // Dialog Card
        Box(
            modifier = Modifier
                .width(420.dp)
                .background(UmbrixTheme.colors.surfacePanel, shape = RoundedCornerShape(8.dp))
                .padding(20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume clicks inside card */ }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                submit()
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            Column {
                Text(
                    text = "Go to Line",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = UmbrixTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Enter line number (1 – ${formatNumberWithCommas(totalLines)}):",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = lineInputText,
                    onValueChange = { newValue ->
                        // Only allow numeric input
                        if (newValue.all { it.isDigit() }) {
                            lineInputText = newValue
                            errorMessage = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage!!,
                        fontSize = 11.sp,
                        color = UmbrixTheme.colors.badgeErrorText
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    DefaultButton(
                        onClick = { submit() },
                        enabled = lineInputText.isNotBlank() && totalLines > 0L
                    ) {
                        Text("Go")
                    }
                }
            }
        }
    }
}
