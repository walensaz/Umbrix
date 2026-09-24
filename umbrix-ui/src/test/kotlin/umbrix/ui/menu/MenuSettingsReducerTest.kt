package umbrix.ui.menu

import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.settings.WorkstationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuSettingsReducerTest {

    private val defaults = WorkstationSettings()

    @Test
    fun testSetThemeResolvesCanonicalName() {
        val updated = MenuSettingsReducer.reduce(MenuAction.SetTheme("dracula"), defaults)
        assertEquals("Dracula", updated?.themeName)
    }

    @Test
    fun testSetThemeIgnoresUnknownOrUnchangedTheme() {
        assertNull(MenuSettingsReducer.reduce(MenuAction.SetTheme("does-not-exist"), defaults))
        assertNull(MenuSettingsReducer.reduce(MenuAction.SetTheme("Dark Carbon"), defaults))
    }

    @Test
    fun testSetViewportMode() {
        val updated = MenuSettingsReducer.reduce(MenuAction.SetViewportMode(ViewportLayoutMode.COMPACT), defaults)
        assertEquals(ViewportLayoutMode.COMPACT, updated?.viewportMode)
        assertNull(MenuSettingsReducer.reduce(MenuAction.SetViewportMode(ViewportLayoutMode.EXPANDABLE), defaults))
    }

    @Test
    fun testEveryViewToggleFlipsItsCheckmark() {
        for (viewToggle in ViewToggle.entries) {
            val before = MenuSettingsReducer.isToggleEnabled(viewToggle, defaults)
            val updated = MenuSettingsReducer.reduce(MenuAction.ToggleView(viewToggle), defaults)!!
            assertEquals(!before, MenuSettingsReducer.isToggleEnabled(viewToggle, updated), "Toggle $viewToggle")
            val restored = MenuSettingsReducer.reduce(MenuAction.ToggleView(viewToggle), updated)!!
            assertEquals(before, MenuSettingsReducer.isToggleEnabled(viewToggle, restored), "Toggle $viewToggle round trip")
        }
    }

    @Test
    fun testClearRecentFiles() {
        val withRecent = defaults.copy(recentFiles = listOf("a.log", "b.log"))
        assertTrue(MenuSettingsReducer.reduce(MenuAction.ClearRecentFiles, withRecent)!!.recentFiles.isEmpty())
        assertNull(MenuSettingsReducer.reduce(MenuAction.ClearRecentFiles, defaults))
    }

    @Test
    fun testNonSettingsActionsAreIgnored() {
        val nonSettingsActions = listOf(
            MenuAction.OpenFile, MenuAction.OpenRecentFile("x.log"), MenuAction.Find, MenuAction.GoToLineTimestamp,
            MenuAction.TemporalRange, MenuAction.OpenSettings, MenuAction.DiagnosticReport, MenuAction.About, MenuAction.Exit
        )
        for (action in nonSettingsActions) {
            assertNull(MenuSettingsReducer.reduce(action, defaults), "Action $action")
        }
    }

    @Test
    fun testRecentFileLabelPutsFileNameFirst() {
        val label = recentFileLabel("/var/log/app/server.log")
        assertTrue(label.startsWith("server.log"))
        assertFalse(recentFileLabel("server.log").contains("—"))
    }
}
