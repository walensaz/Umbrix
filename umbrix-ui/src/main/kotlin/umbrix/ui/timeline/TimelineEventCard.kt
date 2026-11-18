package umbrix.ui.timeline

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.util.formatEpochMicros
import umbrix.ui.util.formatNumberWithCommas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.style.TextOverflow

/**
 * Multi-Row Batch Operations Bar displayed when >= 1 timeline events are selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineBatchOperationsBar(
    selectedCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onSetBatchTag: (EventTag) -> Unit,
    onDeleteSelected: () -> Unit,
    onDeselectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfaceElevated.copy(alpha = 0.95f))
            .border(1.dp, UmbrixTheme.colors.accentPrimary.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$selectedCount Selected",
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = UmbrixTheme.colors.accentPrimary,
                maxLines = 1,
                softWrap = false
            )

            OutlinedButton(onClick = onMoveUp) {
                Text("▲ Move Up", fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onMoveDown) {
                Text("▼ Move Down", fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onMoveToTop) {
                Text("⇪ Top", fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onMoveToBottom) {
                Text("⇩ Bottom", fontSize = 10.sp, maxLines = 1, softWrap = false)
            }

            EventTag.entries.forEach { tag ->
                val tagColor = Color(tag.colorHex)
                Box(
                    modifier = Modifier
                        .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                        .border(1.dp, tagColor.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                        .clickable { onSetBatchTag(tag) }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("Tag: ${tag.displayName}", fontSize = 9.sp, color = tagColor, maxLines = 1, softWrap = false)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDeleteSelected) {
                Text("🗑 Delete ($selectedCount)", fontSize = 10.sp, color = UmbrixTheme.colors.severityError, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onDeselectAll) {
                Text("Deselect", fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * Individual timeline event card with jump-to-source, notes, and tag chips.
 */
@Composable
fun TimelineEventCard(
    event: TimelineEvent,
    index: Int,
    deltaMicros: Long,
    isSelected: Boolean,
    onToggleSelection: (isShiftPressed: Boolean) -> Unit,
    onJumpToSource: () -> Unit,
    onUpdateNote: (String) -> Unit,
    onUpdateTag: (EventTag) -> Unit,
    onDelete: () -> Unit
) {
    var isEditingNote by remember { mutableStateOf(false) }
    var noteText by remember(event.userNote) { mutableStateOf(event.userNote) }
    val tagColor = Color(event.tag.colorHex)

    val deltaBadgeColor = when {
        deltaMicros < 0L -> UmbrixTheme.colors.textSecondary
        deltaMicros == 0L -> UmbrixTheme.colors.textSecondary
        deltaMicros < 1_000_000L -> UmbrixTheme.colors.severityInfo
        deltaMicros < 5_000_000L -> UmbrixTheme.colors.severityWarn
        else -> UmbrixTheme.colors.severityError
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) UmbrixTheme.colors.accentPrimary.copy(alpha = 0.12f)
                else UmbrixTheme.colors.surfaceElevated,
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (isSelected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.border,
                RoundedCornerShape(6.dp)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header: Index #, Document title, Line #, Delta, Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection(false) }
                    )
                    Badge(label = "#$index", textColor = UmbrixTheme.colors.accentPrimary, backgroundColor = UmbrixTheme.colors.surfacePanel)
                    Text(
                        text = "${event.sourceDocumentTitle}:${formatNumberWithCommas(event.lineIndex + 1L)}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = UmbrixTheme.colors.accentPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable { onJumpToSource() }
                    )
                    if (event.timestampMicros > 0L) {
                        Text(
                            text = formatEpochMicros(event.timestampMicros),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = UmbrixTheme.colors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (deltaMicros != 0L) {
                        val deltaSign = if (deltaMicros > 0) "+" else ""
                        val deltaSec = deltaMicros.toDouble() / 1_000_000.0
                        Badge(
                            label = "$deltaSign${String.format(java.util.Locale.US, "%.3fs", deltaSec)}",
                            textColor = deltaBadgeColor,
                            backgroundColor = UmbrixTheme.colors.surfacePanel
                        )
                    }

                    // Event Tag Chip
                    Box(
                        modifier = Modifier
                            .background(tagColor.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                            .border(1.dp, tagColor.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(event.tag.displayName, fontSize = 10.sp, color = tagColor, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }

                    Text(
                        text = "✕",
                        fontSize = 11.sp,
                        color = UmbrixTheme.colors.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.clickable { onDelete() }.padding(2.dp)
                    )
                }
            }

            // Line Content Snippet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surface, RoundedCornerShape(4.dp))
                    .padding(8.dp)
                    .clickable { onToggleSelection(false) }
            ) {
                Text(
                    text = event.lineContent,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = UmbrixTheme.colors.textPrimary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // User Note & Tag Selection Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditingNote) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            onUpdateNote(noteText)
                            isEditingNote = false
                        }) {
                            Text("Save", fontSize = 10.sp, maxLines = 1, softWrap = false)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .clickable { isEditingNote = true }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📝", fontSize = 10.sp, maxLines = 1, softWrap = false)
                        Text(
                            text = if (event.userNote.isNotBlank()) event.userNote else "Add a note or hypothesis...",
                            fontSize = 11.sp,
                            color = if (event.userNote.isNotBlank()) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textMuted,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Tag switcher pills
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EventTag.entries.forEach { tag ->
                        val isCurrentTag = event.tag == tag
                        val pillColor = Color(tag.colorHex)
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isCurrentTag) pillColor.copy(alpha = 0.35f) else Color.Transparent,
                                    RoundedCornerShape(3.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isCurrentTag) pillColor else UmbrixTheme.colors.border,
                                    RoundedCornerShape(3.dp)
                                )
                                .clickable { onUpdateTag(tag) }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = tag.displayName.take(3),
                                fontSize = 9.sp,
                                color = if (isCurrentTag) pillColor else UmbrixTheme.colors.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}
