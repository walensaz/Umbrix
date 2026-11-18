package umbrix.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.components.UmbrixModalCard
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.timeline.TimelineFormatters

/**
 * Modal dialog displaying all bookmarked lines for the active document,
 * enabling searching, note editing, sequential jumping, and exporting.
 */
@Composable
fun BookmarkManagerDialog(
    bookmarkManager: BookmarkManager,
    documentTitle: String,
    onJumpToLine: (Long) -> Unit,
    onAddToTimeline: (DocumentBookmark) -> Unit,
    onDismiss: () -> Unit,
    isBookmarkFilterActive: Boolean = false,
    onToggleBookmarkFilter: (() -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTagFilter by remember { mutableStateOf<BookmarkTag?>(null) }

    val filteredBookmarks = bookmarkManager.bookmarks.filter { bm ->
        val matchesTag = selectedTagFilter == null || bm.tag == selectedTagFilter
        val matchesQuery = searchQuery.isBlank() ||
            bm.lineSnippet.contains(searchQuery, ignoreCase = true) ||
            bm.note.contains(searchQuery, ignoreCase = true) ||
            (bm.lineIndex + 1).toString().contains(searchQuery)
        matchesTag && matchesQuery
    }

    UmbrixModalCard(
        title = "Bookmarks — $documentTitle (${bookmarkManager.bookmarks.size})",
        onDismiss = onDismiss,
        width = 720.dp,
        isScrollable = false,
        actions = {
            if (onToggleBookmarkFilter != null && bookmarkManager.hasBookmarks()) {
                DefaultButton(
                    onClick = {
                        onToggleBookmarkFilter()
                        onDismiss()
                    }
                ) {
                    Text(if (isBookmarkFilterActive) "Restore Full View" else "Filter View to Bookmarks")
                }
            }
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(500.dp)) {
            // Search & Tag Filter Row
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter bookmarks by snippet, note, or line #...") },
                    modifier = Modifier.widthIn(min = 200.dp, max = 340.dp)
                )

                // Tag Filter Pills
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val allSelected = selectedTagFilter == null
                    Box(
                        modifier = Modifier
                            .background(
                                if (allSelected) UmbrixTheme.colors.accentPrimary.copy(alpha = 0.2f) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (allSelected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.border,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { selectedTagFilter = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("All", fontSize = 11.sp, color = UmbrixTheme.colors.textPrimary, maxLines = 1, softWrap = false)
                    }

                    BookmarkTag.entries.forEach { tag ->
                        val isSelected = selectedTagFilter == tag
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
                                .clickable { selectedTagFilter = if (isSelected) null else tag }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(tag.displayName, fontSize = 11.sp, color = tagColor, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredBookmarks.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (bookmarkManager.bookmarks.isEmpty()) "No bookmarks in this document yet. Click the gutter or press F2 to bookmark."
                        else "No bookmarks match the current filter.",
                        color = UmbrixTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredBookmarks, key = { it.lineIndex }) { bm ->
                        BookmarkRowCard(
                            bookmark = bm,
                            onJump = {
                                onJumpToLine(bm.lineIndex)
                                onDismiss()
                            },
                            onAddToTimeline = { onAddToTimeline(bm) },
                            onUpdateNote = { newNote -> bookmarkManager.updateNote(bm.lineIndex, newNote) },
                            onUpdateTag = { newTag -> bookmarkManager.updateTag(bm.lineIndex, newTag) },
                            onDelete = { bookmarkManager.remove(bm.lineIndex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRowCard(
    bookmark: DocumentBookmark,
    onJump: () -> Unit,
    onAddToTimeline: () -> Unit,
    onUpdateNote: (String) -> Unit,
    onUpdateTag: (BookmarkTag) -> Unit,
    onDelete: () -> Unit
) {
    var isEditingNote by remember { mutableStateOf(false) }
    var noteText by remember(bookmark.note) { mutableStateOf(bookmark.note) }
    val tagColor = Color(bookmark.tag.colorHex)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfaceElevated, RoundedCornerShape(6.dp))
            .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header Row: Line #, Timestamp, Tag, Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Line ${bookmark.lineIndex + 1}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = UmbrixTheme.colors.textPrimary,
                        maxLines = 1,
                        softWrap = false
                    )

                    if (bookmark.timestampMicros > 0L) {
                        Text(
                            TimelineFormatters.formatTimestamp(bookmark.timestampMicros, includeDate = true),
                            fontSize = 11.sp,
                            color = UmbrixTheme.colors.textSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // Tag Badge
                    Box(
                        modifier = Modifier
                            .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .border(1.dp, tagColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(bookmark.tag.displayName, fontSize = 10.sp, color = tagColor, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onAddToTimeline) {
                        Text("Add to Timeline", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                    DefaultButton(onClick = onJump) {
                        Text("Jump", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(onClick = onDelete) {
                        Text("✕", fontSize = 11.sp, color = UmbrixTheme.colors.severityError, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Line Snippet Monospace Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.surface, RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                Text(
                    text = bookmark.lineSnippet.take(200) + if (bookmark.lineSnippet.length > 200) "..." else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Note Field / Editor
            if (isEditingNote) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = { Text("Add forensic note...") },
                        modifier = Modifier.weight(1f)
                    )
                    DefaultButton(onClick = {
                        onUpdateNote(noteText)
                        isEditingNote = false
                    }) {
                        Text("Save", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(onClick = { isEditingNote = false }) {
                        Text("Cancel", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isEditingNote = true },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (bookmark.note.isNotBlank()) "📝 ${bookmark.note}" else "Click to add note...",
                        fontSize = 11.sp,
                        color = if (bookmark.note.isNotBlank()) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
