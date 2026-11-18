package umbrix.ui.state

import umbrix.core.session.LogSession
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceTabProgressTest {

    @Test
    fun testWorkspaceTabTracksProgressiveIndexingState() {
        val tempFile = File.createTempFile("umbrix-tab-test-", ".log")
        tempFile.deleteOnExit()
        val content = (1..500).joinToString("\n") { "line $it timestamp=2026-09-22 ERROR service failed" } + "\n"
        Files.write(tempFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))

        val session = LogSession.open(tempFile.toPath(), false)
        try {
            val tab = WorkspaceTab(
                session = session,
                filePath = tempFile.absolutePath,
                initialTopVisibleLine = 0L
            )

            // Verify initial telemetry
            assertTrue(tab.liveTotalLines >= 500L, "Live total lines must be at least 500")
            assertTrue(tab.indexingProgress in 0.0..100.0, "Progress must be between 0.0 and 100.0")
            assertTrue(tab.indexingStatus.isNotEmpty(), "Indexing status text must not be empty")

            // Simulate progress update
            tab.indexingProgress = 42.5
            tab.indexingStatus = "Indexing test.log: 42% (1.2 MB / 3.0 MB — 25,000 lines)"
            tab.isIndexing = true

            assertEquals(42.5, tab.indexingProgress)
            assertEquals("Indexing test.log: 42% (1.2 MB / 3.0 MB — 25,000 lines)", tab.indexingStatus)
            assertTrue(tab.isIndexing)

            // Mark completed
            tab.isIndexing = false
            tab.indexingProgress = 100.0
            assertFalse(tab.isIndexing)
            assertEquals(100.0, tab.indexingProgress)
        } finally {
            session.close()
        }
    }
}
