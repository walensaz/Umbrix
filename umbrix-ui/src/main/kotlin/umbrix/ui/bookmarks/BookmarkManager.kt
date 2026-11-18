package umbrix.ui.bookmarks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Manages bookmarks for a document session.
 * Fully thread-safe on the Compose UI thread, maintains sorted order by lineIndex for fast binary lookup.
 */
class BookmarkManager {
    val bookmarks = mutableStateListOf<DocumentBookmark>()

    /**
     * Monotonically increasing generation counter incremented on bookmark mutations.
     * Allows consumers (e.g. LogViewportCanvas) to observe bookmark changes without
     * cloning the bookmarks list into memory on every scroll frame.
     */
    var generation: Int by mutableStateOf(0)
        private set

    /**
     * Toggles a bookmark at the specified line.
     * Returns true if bookmark was added, false if it was removed.
     */
    fun toggle(
        lineIndex: Long,
        timestampMicros: Long = 0L,
        lineSnippet: String = "",
        tag: BookmarkTag = BookmarkTag.IMPORTANT,
        note: String = ""
    ): Boolean {
        val existingIndex = bookmarks.indexOfFirst { it.lineIndex == lineIndex }
        return if (existingIndex >= 0) {
            bookmarks.removeAt(existingIndex)
            generation++
            false
        } else {
            val clampedSnippet = if (lineSnippet.length > 4096) lineSnippet.substring(0, 4096) else lineSnippet
            val bookmark = DocumentBookmark(
                lineIndex = lineIndex,
                timestampMicros = timestampMicros,
                lineSnippet = clampedSnippet,
                note = note,
                tag = tag
            )
            // Insert in sorted order by lineIndex
            val insertPos = bookmarks.indexOfFirst { it.lineIndex > lineIndex }
            if (insertPos >= 0) {
                bookmarks.add(insertPos, bookmark)
            } else {
                bookmarks.add(bookmark)
            }
            generation++
            true
        }
    }

    fun isBookmarked(lineIndex: Long): Boolean {
        return bookmarks.any { it.lineIndex == lineIndex }
    }

    fun getBookmark(lineIndex: Long): DocumentBookmark? {
        return bookmarks.firstOrNull { it.lineIndex == lineIndex }
    }

    /**
     * Finds the next bookmark line after the given lineIndex (wraps around).
     */
    fun nextBookmark(afterLine: Long): Long? {
        if (bookmarks.isEmpty()) return null
        return bookmarks.firstOrNull { it.lineIndex > afterLine }?.lineIndex
            ?: bookmarks.firstOrNull()?.lineIndex
    }

    /**
     * Finds the previous bookmark line before the given lineIndex (wraps around).
     */
    fun previousBookmark(beforeLine: Long): Long? {
        if (bookmarks.isEmpty()) return null
        return bookmarks.lastOrNull { it.lineIndex < beforeLine }?.lineIndex
            ?: bookmarks.lastOrNull()?.lineIndex
    }

    fun updateNote(lineIndex: Long, newNote: String) {
        val idx = bookmarks.indexOfFirst { it.lineIndex == lineIndex }
        if (idx >= 0) {
            val current = bookmarks[idx]
            bookmarks[idx] = current.copy(note = newNote)
            generation++
        }
    }

    fun updateTag(lineIndex: Long, newTag: BookmarkTag) {
        val idx = bookmarks.indexOfFirst { it.lineIndex == lineIndex }
        if (idx >= 0) {
            val current = bookmarks[idx]
            bookmarks[idx] = current.copy(tag = newTag)
            generation++
        }
    }

    fun remove(lineIndex: Long): Boolean {
        val idx = bookmarks.indexOfFirst { it.lineIndex == lineIndex }
        return if (idx >= 0) {
            bookmarks.removeAt(idx)
            generation++
            true
        } else {
            false
        }
    }

    fun clear() {
        bookmarks.clear()
        generation++
    }

    fun hasBookmarks(): Boolean = bookmarks.isNotEmpty()

    /**
     * Converts all bookmarked line indices into an off-heap Roaring64NavigableMap
     * bounded by [minLine, maxLine], suitable for zero-copy FilteredLineView rendering.
     */
    fun toBitmap(minLine: Long = 0L, maxLine: Long = Long.MAX_VALUE): org.roaringbitmap.longlong.Roaring64NavigableMap {
        val bitmap = org.roaringbitmap.longlong.Roaring64NavigableMap()
        for (bm in bookmarks) {
            if (bm.lineIndex in minLine..maxLine) {
                bitmap.addLong(bm.lineIndex)
            }
        }
        return bitmap
    }
}
