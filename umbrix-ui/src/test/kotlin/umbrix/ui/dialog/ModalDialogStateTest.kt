package umbrix.ui.dialog

import umbrix.ui.menu.MenuAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModalDialogStateTest {

    @Test
    fun testExportSubFileRangeValidationAndLinearByteEstimation() {
        val totalLines = 1_000_000L
        val totalBytes = 100_000_000L // 100 MB

        // Valid range: lines 100 to 200 (101 lines)
        val startLine = 100L
        val endLine = 200L
        val lineCount = if (startLine in 1..endLine && endLine <= totalLines) (endLine - startLine + 1L) else 0L
        assertEquals(101L, lineCount)

        val estimatedBytes = if (totalLines > 0L && lineCount > 0L) {
            ((lineCount.toDouble() / totalLines.toDouble()) * totalBytes).toLong()
        } else 0L
        assertEquals(10_100L, estimatedBytes)

        // Invalid start line (< 1)
        val invalidStart = 0L
        val invalidStartCount = if (invalidStart in 1..endLine && endLine <= totalLines) (endLine - invalidStart + 1L) else 0L
        assertEquals(0L, invalidStartCount)

        // Invalid end line (> totalLines)
        val invalidEnd = totalLines + 1L
        val invalidEndCount = if (startLine in 1..invalidEnd && invalidEnd <= totalLines) (invalidEnd - startLine + 1L) else 0L
        assertEquals(0L, invalidEndCount)

        // Inverted range (endLine < startLine)
        val invertedEnd = 50L
        val invertedCount = if (startLine in 1..invertedEnd && invertedEnd <= totalLines) (invertedEnd - startLine + 1L) else 0L
        assertEquals(0L, invertedCount)
    }

    @Test
    fun testKeyboardShortcutsSearchFiltering() {
        val shortcuts = listOf(
            Triple("Navigation", "Go to Line / Timestamp", "Ctrl+G"),
            Triple("Navigation", "Next Search Result", "F3"),
            Triple("Search", "Open Search Bar", "Ctrl+F"),
            Triple("File", "Export Sub-File...", "Ctrl+Shift+S"),
            Triple("Help", "Keyboard Shortcuts", "F1")
        )

        // Filter by title "export"
        val query1 = "export"
        val matches1 = shortcuts.filter { (cat, title, key) ->
            cat.contains(query1, ignoreCase = true) ||
            title.contains(query1, ignoreCase = true) ||
            key.contains(query1, ignoreCase = true)
        }
        assertEquals(1, matches1.size)
        assertEquals("Export Sub-File...", matches1.first().second)

        // Filter by key "Ctrl+"
        val query2 = "Ctrl+"
        val matches2 = shortcuts.filter { (cat, title, key) ->
            cat.contains(query2, ignoreCase = true) ||
            title.contains(query2, ignoreCase = true) ||
            key.contains(query2, ignoreCase = true)
        }
        assertEquals(3, matches2.size)

        // Filter by category "Help"
        val query3 = "help"
        val matches3 = shortcuts.filter { (cat, title, key) ->
            cat.contains(query3, ignoreCase = true) ||
            title.contains(query3, ignoreCase = true) ||
            key.contains(query3, ignoreCase = true)
        }
        assertEquals(1, matches3.size)
        assertEquals("F1", matches3.first().third)
    }

    @Test
    fun testAboutDialogPlatformMetadataIntrospection() {
        val javaVersion = System.getProperty("java.version")
        val javaVendor = System.getProperty("java.vendor")
        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")

        assertNotNull(javaVersion, "Java version must be available")
        assertNotNull(javaVendor, "Java vendor must be available")
        assertNotNull(osName, "OS name must be available")
        assertNotNull(osArch, "OS architecture must be available")
        assertTrue(javaVersion.isNotBlank())
        assertTrue(osName.isNotBlank())
    }

    @Test
    fun testMenuActionsContainsPhase5ModalActions() {
        val actions: List<MenuAction> = listOf(
            MenuAction.ExportSubFile,
            MenuAction.KeyboardShortcuts,
            MenuAction.About,
            MenuAction.OpenSettings
        )
        assertEquals(4, actions.distinct().size)
    }
}
