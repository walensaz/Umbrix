package umbrix.ui.bookmarks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BookmarkManagerTest {

    @Test
    fun testToggleAddsAndRemoves() {
        val manager = BookmarkManager()
        assertFalse(manager.isBookmarked(100L))

        // Toggle add
        val added = manager.toggle(100L, 1727366400000000L, "Sample log line 100", BookmarkTag.IMPORTANT, "Check memory")
        assertTrue(added)
        assertTrue(manager.isBookmarked(100L))
        assertEquals(1, manager.bookmarks.size)

        val bookmark = manager.getBookmark(100L)
        assertNotNull(bookmark)
        assertEquals("Sample log line 100", bookmark?.lineSnippet)
        assertEquals("Check memory", bookmark?.note)
        assertEquals(BookmarkTag.IMPORTANT, bookmark?.tag)

        // Toggle remove
        val removed = manager.toggle(100L)
        assertFalse(removed)
        assertFalse(manager.isBookmarked(100L))
        assertEquals(0, manager.bookmarks.size)
    }

    @Test
    fun testBookmarksMaintainSortedOrder() {
        val manager = BookmarkManager()
        manager.toggle(500L)
        manager.toggle(100L)
        manager.toggle(300L)

        assertEquals(3, manager.bookmarks.size)
        assertEquals(100L, manager.bookmarks[0].lineIndex)
        assertEquals(300L, manager.bookmarks[1].lineIndex)
        assertEquals(500L, manager.bookmarks[2].lineIndex)
    }

    @Test
    fun testNextAndPreviousBookmarkNavigation() {
        val manager = BookmarkManager()
        manager.toggle(100L)
        manager.toggle(300L)
        manager.toggle(500L)

        // Next bookmark after 100 is 300
        assertEquals(300L, manager.nextBookmark(100L))
        // Next bookmark after 300 is 500
        assertEquals(500L, manager.nextBookmark(300L))
        // Next bookmark after 500 wraps around to 100
        assertEquals(100L, manager.nextBookmark(500L))

        // Previous bookmark before 500 is 300
        assertEquals(300L, manager.previousBookmark(500L))
        // Previous bookmark before 300 is 100
        assertEquals(100L, manager.previousBookmark(300L))
        // Previous bookmark before 100 wraps around to 500
        assertEquals(500L, manager.previousBookmark(100L))
    }

    @Test
    fun testUpdateNoteAndTag() {
        val manager = BookmarkManager()
        manager.toggle(200L, lineSnippet = "Error connecting to Redis")

        manager.updateNote(200L, "Potential root cause")
        manager.updateTag(200L, BookmarkTag.ERROR)

        val bm = manager.getBookmark(200L)
        assertNotNull(bm)
        assertEquals("Potential root cause", bm?.note)
        assertEquals(BookmarkTag.ERROR, bm?.tag)
    }

    private infix fun BookmarkTag.ifExistsOr(fallback: BookmarkTag): BookmarkTag = this
}
