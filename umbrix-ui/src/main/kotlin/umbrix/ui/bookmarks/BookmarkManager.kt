package umbrix.ui.bookmarks

import androidx.compose.runtime.mutableStateListOf

/**
 * Manages bookmarks for a document session.
 * Fully thread-safe on the Compose UI thread, maintains sorted order by lineIndex for fast binary lookup.
 */
class BookmarkManager {
    val bookmarks = mutableStateListOf<DocumentBookmark>()

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
        }
    }

    fun updateTag(lineIndex: Long, newTag: BookmarkTag) {
        val idx = bookmarks.indexOfFirst { it.lineIndex == lineIndex }
        if (idx >= 0) {
            val current = bookmarks[idx]
            bookmarks[idx] = current.copy(tag = newTag)
        }
    }

    fun remove(lineIndex: Long): Boolean {
        val idx = bookmarks.indexOfFirst { it.lineIndex == lineIndex }
        return if (idx >= 0) {
            bookmarks.removeAt(idx)
            true
        } else {
            false
        }
    }

    fun clear() {
        bookmarks.clear()
    }

    fun hasBookmarks(): Boolean = bookmarks.isNotEmpty()

    /**
     * Converts all bookmarked line indices into an off-heap Roaring64NavigableMap
     * suitable for zero-copy FilteredLineView rendering.
     */
    fun toBitmap(): org.roaringbitmap.longlong.Roaring64NavigableMap {
        val bitmap = org.roaringbitmap.longlong.Roaring64NavigableMap()
        for (bm in bookmarks) {
            bitmap.addLong(bm.lineIndex)
        }
        return bitmap
    }
}
