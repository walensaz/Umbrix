package umbrix.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.UmbrixTheme

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/**
 * Full-featured, dockable Incident Timeline canvas.
 *
 * Supports rendering within any [DockNode.Leaf] pane, split container, or detached window.
 * Provides chronological sorting, Delta-t cascade tracking, batch movement, tags, and report export.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvestigationTimelineCanvas(
    timelineManager: InvestigationTimelineManager,
    onJumpToEvent: (TimelineEvent) -> Unit,
    onOpenExportDialog: () -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var tagFilter by remember { mutableStateOf<EventTag?>(null) }
    var lastClickedId by remember { mutableStateOf<String?>(null) }

    val filteredEvents = remember(timelineManager.events.toList(), tagFilter) {
        timelineManager.events.filter { event ->
            tagFilter == null || event.tag == tagFilter
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UmbrixTheme.colors.surface)
    ) {
        // Header Toolbar
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(UmbrixTheme.colors.surfaceElevated)
                .border(1.dp, UmbrixTheme.colors.border)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Left: Title + Event count badge + Filter tags
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⏱️ Incident Timeline",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textPrimary,
                    maxLines = 1,
                    softWrap = false
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
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(onClick = { timelineManager.sortChronologically() }) {
                    Text("⇅ Chronological Sort", fontSize = 11.sp, maxLines = 1, softWrap = false)
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
                    Text("All", fontSize = 10.sp, color = UmbrixTheme.colors.textPrimary, maxLines = 1, softWrap = false)
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
                        Text(tag.displayName, fontSize = 10.sp, color = tagColor, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Right: Export, Clear, Close
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DefaultButton(onClick = onOpenExportDialog) {
                    Text("Export Report 📤", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { timelineManager.clear() }) {
                    Text("Clear", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
                if (onClose != null) {
                    OutlinedButton(onClick = onClose) {
                        Text("✕", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Multi-Row Batch Operations Bar (Visible when >= 1 event is selected)
        if (timelineManager.selectedEventIds.isNotEmpty()) {
            TimelineBatchOperationsBar(
                selectedCount = timelineManager.selectedEventIds.size,
                onMoveUp = { timelineManager.moveSelectedUp() },
                onMoveDown = { timelineManager.moveSelectedDown() },
                onMoveToTop = { timelineManager.moveSelectedToTop() },
                onMoveToBottom = { timelineManager.moveSelectedToBottom() },
                onSetBatchTag = { tag -> timelineManager.setBatchTag(tag) },
                onDeleteSelected = { timelineManager.removeSelected() },
                onDeselectAll = { timelineManager.deselectAll() }
            )
        }

        // Timeline Event Card List
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (timelineManager.events.isEmpty()) "Incident Timeline is empty. Right-click any log row and select 'Add to Incident Timeline' to reconstruct event sequence."
                    else "No events match the selected tag filter.",
                    color = UmbrixTheme.colors.textSecondary,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
