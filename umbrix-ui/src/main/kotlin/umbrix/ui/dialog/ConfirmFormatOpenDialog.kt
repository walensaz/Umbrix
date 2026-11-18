package umbrix.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme

/**
 * Confirmation dialog shown when a file's content heuristically matches a specialized
 * format provider without a deterministic extension or magic header.
 */
@Composable
fun ConfirmFormatOpenDialog(
    filePath: String,
    formatDisplayName: String,
    onOpenAsFormat: () -> Unit,
    onOpenAsPlainLog: () -> Unit,
    onDismiss: () -> Unit
) {
    val fileName = File(filePath).name.ifBlank { filePath }

    UmbrixModalCard(
        title = "Confirm File Format",
        onDismiss = onDismiss,
        width = 480.dp,
        actions = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            OutlinedButton(onClick = onOpenAsPlainLog) {
                Text("Open as Plain Log")
            }
            DefaultButton(onClick = onOpenAsFormat) {
                Text("Open in Format View")
            }
        }
    ) {
        Column {
            Text(
                text = "Ambiguous format detected for \"$fileName\"",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Its header matches $formatDisplayName, but the file extension is not definitive. How would you like to open it?",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textMuted
            )
        }
    }
}
