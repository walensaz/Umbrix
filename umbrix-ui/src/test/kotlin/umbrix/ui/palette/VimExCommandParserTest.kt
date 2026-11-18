package umbrix.ui.palette

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import umbrix.ui.settings.SettingsManager
import umbrix.ui.settings.ViewportLayoutMode

class VimExCommandParserTest {

    @Test
    fun testNonColonQueryReturnsNull() {
        assertEquals(VimCommandResult.NotVimCommand, VimExCommandParser.parse("open file"))
        assertEquals(VimCommandResult.NotVimCommand, VimExCommandParser.parse(">actions"))
    }

    @Test
    fun testEmptyColonPrompt() {
        val res = VimExCommandParser.parse(":")
        assertTrue(res is VimCommandResult.Error)
        assertTrue((res as VimCommandResult.Error).message.contains("Enter a Vim command"))
    }

    @Test
    fun testNumericLineJump() {
        var jumpedLine = -1L
        val res = VimExCommandParser.parse(":42", onJumpToLine = { jumpedLine = it }, onCloseCurrentTab = {})
        assertTrue(res is VimCommandResult.Execute)
        assertEquals("Jump to line 42", (res as VimCommandResult.Execute).description)
        res.action()
        assertEquals(41L, jumpedLine) // 0-indexed line 41
    }

    @Test
    fun testInvalidLineNumber() {
        val res = VimExCommandParser.parse(":0")
        assertTrue(res is VimCommandResult.Error)
        assertEquals("Line number must be >= 1", (res as VimCommandResult.Error).message)
    }

    @Test
    fun testQuitTabCommands() {
        var closedTab = false
        val res = VimExCommandParser.parse(":q", onCloseCurrentTab = { closedTab = true })
        assertTrue(res is VimCommandResult.Execute)
        (res as VimCommandResult.Execute).action()
        assertTrue(closedTab)
    }

    @Test
    fun testWriteNoOp() {
        val res = VimExCommandParser.parse(":w")
        assertTrue(res is VimCommandResult.Execute)
        assertEquals("Open Export Sub-File dialog", (res as VimCommandResult.Execute).description)
    }

    @Test
    fun testSetMinimapToggle() {
        val initial = SettingsManager.currentSettings.isMinimapEnabled
        val res = VimExCommandParser.parse(":set minimap!")
        assertTrue(res is VimCommandResult.Execute)
        (res as VimCommandResult.Execute).action()
        assertEquals(!initial, SettingsManager.currentSettings.isMinimapEnabled)
        // Revert
        SettingsManager.updateSettings(SettingsManager.currentSettings.copy(isMinimapEnabled = initial))
    }

    @Test
    fun testSetViewportMode() {
        val res = VimExCommandParser.parse(":set mode=compact")
        assertTrue(res is VimCommandResult.Execute)
        (res as VimCommandResult.Execute).action()
        assertEquals(ViewportLayoutMode.COMPACT, SettingsManager.currentSettings.viewportMode)
        // Revert
        SettingsManager.updateSettings(SettingsManager.currentSettings.copy(viewportMode = ViewportLayoutMode.EXPANDABLE))
    }

    @Test
    fun testUnknownCommand() {
        val res = VimExCommandParser.parse(":nonexistent123")
        assertTrue(res is VimCommandResult.Error)
        assertTrue((res as VimCommandResult.Error).message.contains("Not an editor command"))
    }
}
