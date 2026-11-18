package umbrix.ui.scratchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.showNativeSaveFileDialog
import java.io.File

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow

/**
 * Top control toolbar for [ScratchpadEditorCanvas] displaying session title,
 * dirty status, line count badge, and find/replace/save/clear action buttons.
 */
@Composable
fun ScratchpadToolbar(
    session: ScratchpadSession,
    lineCount: Int,
    isSearchBarOpen: Boolean,
    onToggleSearchBar: () -> Unit,
    isReplaceBarOpen: Boolean,
    onToggleReplaceBar: () -> Unit,
    onSaveRequested: () -> Unit,
    onClearRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = UmbrixTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated)
            .border(1.dp, colors.border)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📝", fontSize = 13.sp, maxLines = 1, softWrap = false)
            Text(
                session.title + if (session.isDirty) " *" else "",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
            Badge(
                label = "$lineCount lines",
                textColor = colors.badgeLinesText,
                backgroundColor = colors.badgeLinesBackground
            )
            if (session.diskPath != null) {
                Text(
                    "(${session.diskPath})",
                    fontSize = 10.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onToggleSearchBar) {
                Text("🔍 Find (Ctrl+F)", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }

            OutlinedButton(onClick = onToggleReplaceBar) {
                Text("⇄ Replace (Ctrl+R)", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }

            DefaultButton(onClick = {
                if (session.diskPath != null) {
                    session.saveToDisk(File(session.diskPath!!))
                    onSaveRequested()
                } else {
                    val chosenPath = showNativeSaveFileDialog(session.title)
                    if (chosenPath != null) {
                        session.saveToDisk(File(chosenPath))
                        onSaveRequested()
                    }
                }
            }) {
                Text("Save (Ctrl+S)", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }

            OutlinedButton(onClick = onClearRequested) {
                Text("Clear", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}
