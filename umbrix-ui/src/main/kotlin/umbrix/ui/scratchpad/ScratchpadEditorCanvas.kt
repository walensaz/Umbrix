package umbrix.ui.scratchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import umbrix.ui.theme.UmbrixTheme
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * In-app editable scratchpad canvas for drafting notes, comparing log snippets,
 * and staging text across multiple files.
 */
@Composable
fun ScratchpadEditorCanvas(
    session: ScratchpadSession,
    onSaveRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UmbrixTheme.colors.surface)
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UmbrixTheme.colors.surfaceElevated)
                .border(1.dp, UmbrixTheme.colors.border)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📝", fontSize = 14.sp)
                Text(
                    session.title + if (session.isDirty) " *" else "",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
                if (session.diskPath != null) {
                    Text(
                        "(${session.diskPath})",
                        fontSize = 10.sp,
                        color = UmbrixTheme.colors.textSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultButton(onClick = {
                    if (session.diskPath != null) {
                        session.saveToDisk(File(session.diskPath!!))
                    } else {
                        val dialog = FileDialog(null as Frame?, "Save Scratchpad", FileDialog.SAVE)
                        dialog.file = session.title
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val filename = dialog.file
                        if (dir != null && filename != null) {
                            session.saveToDisk(File(dir, filename))
                        }
                    }
                }) {
                    Text("Save (Ctrl+S)", fontSize = 11.sp)
                }

                OutlinedButton(onClick = {
                    session.content = ""
                    session.isDirty = false
                }) {
                    Text("Clear", fontSize = 11.sp)
                }
            }
        }

        // Editable Text Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
        ) {
            TextArea(
                value = session.content,
                onValueChange = {
                    session.content = it
                    session.isDirty = true
                },
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
            )
        }
    }
}
