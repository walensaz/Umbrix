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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme

/**
 * Standardized modal dialog for renaming virtual scratchpads.
 */
@Composable
fun RenameScratchpadDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var textFieldValue by remember {
        val extIndex = currentTitle.lastIndexOf('.')
        val selectEnd = if (extIndex > 0) extIndex else currentTitle.length
        mutableStateOf(
            TextFieldValue(
                text = currentTitle,
                selection = TextRange(0, selectEnd)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Throwable) {}
    }

    fun submit() {
        val trimmed = textFieldValue.text.trim()
        if (trimmed.isNotBlank()) {
            onConfirm(trimmed)
        }
    }

    UmbrixModalCard(
        title = "Rename Scratchpad",
        onDismiss = onDismiss,
        width = 420.dp,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            DefaultButton(
                onClick = { submit() },
                enabled = textFieldValue.text.trim().isNotBlank()
            ) {
                Text("Rename")
            }
        }
    ) {
        Column {
            Text(
                text = "Enter a new title for this scratchpad buffer:",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textMuted
            )
            Spacer(modifier = Modifier.height(10.dp))

            TextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.Enter -> {
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
            )
        }
    }
}
