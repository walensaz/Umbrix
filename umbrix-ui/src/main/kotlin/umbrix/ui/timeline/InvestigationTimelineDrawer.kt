package umbrix.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.theme.UmbrixTheme

/**
 * Bottom drawer displaying the chronological Incident Timeline reconstructed across all files.
 * Provides multi-row selection, batch movement, Delta-t cascade visualization, and report export.
 */
@Composable
fun InvestigationTimelineDrawer(
    timelineManager: InvestigationTimelineManager,
    onJumpToEvent: (TimelineEvent) -> Unit,
    onOpenExportDialog: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tagFilter by remember { mutableStateOf<EventTag?>(null) }
    var lastClickedId by remember { mutableStateOf<String?>(null) }

    val filteredEvents = timelineManager.events.filter { event ->
        tagFilter == null || event.tag == tagFilter
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .background(UmbrixTheme.colors.surfaceElevated)
            .border(1.dp, UmbrixTheme.colors.border)
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            // Header Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Title + Event count badge + Filter
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Incident Timeline",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = UmbrixTheme.colors.textPrimary
                    )

                    Box(
                        modifier = Modifier
                            .background(UmbrixTheme.colors.accentPrimary.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${timelineManager.events.size} Events",
                            fontSize = 10.sp,
                            color = UmbrixTheme.colors.accentPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(onClick = { timelineManager.sortChronologically() }) {
                        Text("⇅ Chronological Sort", fontSize = 11.sp)
                    }

                    // Filter pill: All
                    Box(
                        modifier = Modifier
                            .background(
                                if (tagFilter == null) UmbrixTheme.colors.accentPrimary.copy(alpha = 0.2f) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (tagFilter == null) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.border,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { tagFilter = null }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text("All", fontSize = 10.sp, color = UmbrixTheme.colors.textPrimary)
                    }

                    EventTag.entries.forEach { tag ->
                        val isSelected = tagFilter == tag
                        val tagColor = Color(tag.colorHex)
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isSelected) tagColor.copy(alpha = 0.25f) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) tagColor else UmbrixTheme.colors.border,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { tagFilter = if (isSelected) null else tag }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(tag.displayName, fontSize = 10.sp, color = tagColor)
                        }
                    }
                }

                // Right: Export, Clear, Close
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DefaultButton(onClick = onOpenExportDialog) {
                        Text("Export Report 📤", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { timelineManager.clear() }) {
                        Text("Clear", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onCloseDrawer) {
                        Text("✕", fontSize = 11.sp)
                    }
                }
            }

            // Multi-Row Batch Operations Bar (Visible when >= 1 event is selected)
            if (timelineManager.selectedEventIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UmbrixTheme.colors.surfaceElevated.copy(alpha = 0.95f))
                        .border(1.dp, UmbrixTheme.colors.accentPrimary.copy(alpha = 0.4f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${timelineManager.selectedEventIds.size} Selected",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = UmbrixTheme.colors.accentPrimary
                        )

                        // Multi-row Movement Controls
                        OutlinedButton(onClick = { timelineManager.moveSelectedUp() }) {
                            Text("▲ Move Up", fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = { timelineManager.moveSelectedDown() }) {
                            Text("▼ Move Down", fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = { timelineManager.moveSelectedToTop() }) {
                            Text("⇪ Top", fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = { timelineManager.moveSelectedToBottom() }) {
                            Text("⇩ Bottom", fontSize = 10.sp)
                        }

                        // Batch Tag Buttons
                        EventTag.entries.forEach { tag ->
                            val tagColor = Color(tag.colorHex)
                            Box(
                                modifier = Modifier
                                    .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                    .border(1.dp, tagColor.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                                    .clickable { timelineManager.setBatchTag(tag) }
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text("Tag: ${tag.displayName}", fontSize = 9.sp, color = tagColor)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { timelineManager.removeSelected() }) {
                            Text("🗑 Delete (${timelineManager.selectedEventIds.size})", fontSize = 10.sp, color = UmbrixTheme.colors.severityError)
                        }
                        OutlinedButton(onClick = { timelineManager.deselectAll() }) {
                            Text("Deselect", fontSize = 10.sp)
                        }
                    }
                }
            }

            // Timeline Event Card List
            if (filteredEvents.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (timelineManager.events.isEmpty()) "Timeline is empty. Right-click any log row and select 'Add to Incident Timeline' to reconstruct event sequence."
                        else "No events match the selected tag filter.",
                        color = UmbrixTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(filteredEvents, key = { _, it -> it.id }) { index, event ->
                        val prevTimestamp = if (index > 0) filteredEvents[index - 1].timestampMicros else 0L
                        val deltaMicros = if (index > 0 && prevTimestamp > 0L && event.timestampMicros > 0L) {
                            event.timestampMicros - prevTimestamp
                        } else 0L

                        TimelineEventCard(
                            event = event,
                            index = index + 1,
                            deltaMicros = deltaMicros,
                            isSelected = timelineManager.isSelected(event.id),
                            onToggleSelection = { isShiftPressed ->
                                if (isShiftPressed && lastClickedId != null) {
                                    timelineManager.selectRange(lastClickedId!!, event.id)
                                } else {
                                    timelineManager.toggleSelection(event.id)
                                }
                                lastClickedId = event.id
                            },
                            onJumpToSource = { onJumpToEvent(event) },
                            onUpdateNote = { note -> timelineManager.updateNote(event.id, note) },
                            onUpdateTag = { tag -> timelineManager.updateTag(event.id, tag) },
                            onDelete = { timelineManager.removeEvent(event.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventCard(
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
        deltaMicros < 1_000_000L -> UmbrixTheme.colors.severityInfo     // < 1s: Greenish/Blue
        deltaMicros < 5_000_000L -> UmbrixTheme.colors.severityWarn     // 1-5s: Amber
        else -> UmbrixTheme.colors.severityError                        // > 5s: Red
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) UmbrixTheme.colors.accentPrimary.copy(alpha = 0.15f)
                else UmbrixTheme.colors.surface,
                RoundedCornerShape(4.dp)
            )
            .border(
                1.dp,
                if (isSelected) UmbrixTheme.colors.accentPrimary
                else UmbrixTheme.colors.border,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Row 1: Checkbox, #, Time, Delta-t, File/Line, Tag, Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection(false) }
                    )

                    Text(
                        "#$index",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = UmbrixTheme.colors.textSecondary
                    )

                    if (event.timestampMicros > 0L) {
                        Text(
                            TimelineFormatters.formatTimestamp(event.timestampMicros, includeDate = true),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = UmbrixTheme.colors.textPrimary
                        )

                        // Delta badge
                        if (deltaMicros != 0L) {
                            Box(
                                modifier = Modifier
                                    .background(deltaBadgeColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                    .border(1.dp, deltaBadgeColor.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    TimelineFormatters.formatDelta(deltaMicros),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = deltaBadgeColor
                                )
                            }
                        }
                    }

                    // Provenance pill (file:line)
                    Box(
                        modifier = Modifier
                            .background(UmbrixTheme.colors.surfaceElevated, RoundedCornerShape(3.dp))
                            .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${event.sourceDocumentTitle} : L${event.lineIndex + 1}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = UmbrixTheme.colors.textPrimary
                        )
                    }

                    // Tag Badge
                    Box(
                        modifier = Modifier
                            .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                            .border(1.dp, tagColor.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(event.tag.displayName, fontSize = 10.sp, color = tagColor, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Actions: Jump & Delete
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DefaultButton(onClick = onJumpToSource) {
                        Text("Jump", fontSize = 10.sp)
                    }
                    OutlinedButton(onClick = onDelete) {
                        Text("✕", fontSize = 10.sp, color = UmbrixTheme.colors.severityError)
                    }
                }
            }

            // Row 2: Code Snippet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surfaceElevated, RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = event.lineContent.take(180) + if (event.lineContent.length > 180) "..." else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
            }

            // Row 3: User Note
            if (isEditingNote) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = { Text("Note...") },
                        modifier = Modifier.weight(1f)
                    )
                    DefaultButton(onClick = {
                        onUpdateNote(noteText)
                        isEditingNote = false
                    }) {
                        Text("Save", fontSize = 10.sp)
                    }
                    OutlinedButton(onClick = { isEditingNote = false }) {
                        Text("Cancel", fontSize = 10.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isEditingNote = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (event.userNote.isNotBlank()) "📝 ${event.userNote}" else "+ Add causal note / hypothesis...",
                        fontSize = 10.sp,
                        color = if (event.userNote.isNotBlank()) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                    )
                }
            }
        }
    }
}
