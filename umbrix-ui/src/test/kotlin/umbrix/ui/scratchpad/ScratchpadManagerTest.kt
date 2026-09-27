package umbrix.ui.scratchpad

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ScratchpadManagerTest {

    @Test
    fun testCreateAndAppendSnippet() {
        val manager = ScratchpadManager()
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
    }

    @Test
    fun testSaveToDisk() {
        val manager = ScratchpadManager()
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
}
