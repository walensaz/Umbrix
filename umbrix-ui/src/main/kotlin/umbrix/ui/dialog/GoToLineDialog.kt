package umbrix.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatNumberWithCommas

/**
 * Standardized modal dialog for sub-millisecond seeking to an absolute physical line number.
 * Activated via Ctrl+G / Cmd+G.
 */
@Composable
fun GoToLineDialog(
    totalLines: Long,
    currentLine: Long,
    onGoToLine: (targetPhysicalLine: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var lineInputText by remember { 
        mutableStateOf((currentLine + 1L).coerceIn(1L, totalLines.coerceAtLeast(1L)).toString()) 
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val inputFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            inputFocusRequester.requestFocus()
        } catch (_: Throwable) {}
    }

    fun submit() {
        val trimmed = lineInputText.trim()
        val parsed = trimmed.toLongOrNull()
        if (parsed == null || parsed < 1L || parsed > totalLines) {
            errorMessage = "Please enter a valid line between 1 and ${formatNumberWithCommas(totalLines)}"
        } else {
            onGoToLine(parsed - 1L)
            onDismiss()
        }
    }

    UmbrixModalCard(
        title = "Go to Line",
        onDismiss = onDismiss,
        width = 440.dp,
        showCloseButton = true,
        isScrollable = false,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            DefaultButton(
                onClick = { submit() },
                enabled = lineInputText.isNotBlank() && totalLines > 0L
            ) {
                Text("Go")
            }
        }
    ) {
        Text(
            text = "Enter line number (1 – ${formatNumberWithCommas(totalLines)}):",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(10.dp))

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
                .focusRequester(inputFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && 
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        submit()
                        true
                    } else false
                }
        )

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
