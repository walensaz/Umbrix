package umbrix.ui.scratchpad

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ScratchpadManagerTest {

    @Test
    fun testCreateAndAppendSnippet() {
        val tempDir = Files.createTempDirectory("scratchpad_test_dir").toFile()
        try {
            val manager = ScratchpadManager(tempDir)
            assertEquals(0, manager.sessions.size)

            val session = manager.appendToActiveOrNew(
                sourceTitle = "web-access.log",
                lineIndex = 499L,
                timestampMicros = 1727366400000000L,
                snippet = "GET /api/v1/health 500 Internal Server Error"
            )

            assertEquals(1, manager.sessions.size)
            assertTrue(session.content.contains("--- web-access.log:500"))
            assertTrue(session.content.contains("GET /api/v1/health 500 Internal Server Error"))
            assertTrue(session.isDirty)

            // Append second snippet to active
            manager.appendToActiveOrNew(
                sourceTitle = "worker.log",
                lineIndex = 20L,
                timestampMicros = 0L,
                snippet = "Worker died unexpectedly"
            )

            assertEquals(1, manager.sessions.size)
            assertTrue(session.content.contains("--- worker.log:21"))
            assertTrue(session.content.contains("Worker died unexpectedly"))

            // Verify persistence to disk
            val reloadedManager = ScratchpadManager(tempDir)
            assertEquals(1, reloadedManager.sessions.size)
            val reloadedSession = reloadedManager.sessions.first()
            assertEquals(session.id, reloadedSession.id)
            assertEquals(session.title, reloadedSession.title)
            assertTrue(reloadedSession.content.contains("GET /api/v1/health 500 Internal Server Error"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSaveToDisk() {
        val manager = ScratchpadManager(null)
        val session = manager.createSession("Sample draft notes")
        assertTrue(session.diskPath == null)

        val tempFile = Files.createTempFile("scratchpad_test", ".log").toFile()
        try {
            session.saveToDisk(tempFile)
            assertFalse(session.isDirty)
            assertEquals(tempFile.absolutePath, session.diskPath)
            assertEquals(tempFile.name, session.title)
            assertEquals("Sample draft notes", tempFile.readText())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testSearchAndDeletion() {
        val manager = ScratchpadManager(null)
        val s1 = manager.createSession(initialContent = "Fatal DB deadlock on connection pool", customTitle = "db-investigation.log")
        val s2 = manager.createSession(initialContent = "HTTP 504 Gateway Timeout during deployment", customTitle = "gateway-notes.log")
        val s3 = manager.createSession(initialContent = "GC pause 4500ms in young gen", customTitle = "gc-analysis.log")

        assertEquals(3, manager.sessions.size)

        // Search by title
        val titleMatch = manager.findSessions("gateway")
        assertEquals(1, titleMatch.size)
        assertEquals(s2.id, titleMatch.first().id)

        // Search by content
        val contentMatch = manager.findSessions("deadlock")
        assertEquals(1, contentMatch.size)
        assertEquals(s1.id, contentMatch.first().id)

        // Metrics verification
        assertEquals(1, s1.lineCount)
        assertTrue(s1.charCount > 0)
        assertTrue(s1.previewSnippet.contains("deadlock"))

        // Rename
        val renamed = manager.renameSession(s1.id, "renamed-deadlock.log")
        assertTrue(renamed)
        assertEquals("renamed-deadlock.log", s1.title)

        // Deletion
        val deleted = manager.deleteSession(s2.id)
        assertTrue(deleted)
        assertEquals(2, manager.sessions.size)
        assertNull(manager.getSession(s2.id))
    }
}
