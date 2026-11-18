package umbrix.ui.palette

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRegistryTest {

    private fun createDummyContext(
        tabs: List<String> = emptyList(),
        activeTab: Int = -1
    ): CommandExecutionContext {
        return CommandExecutionContext(
            onOpenFile = {},
            onOpenRecentFile = {},
            onExportSubFile = {},
            onGoToLine = {},
            onJumpToDirectLine = {},
            onGoToTimestamp = {},
            onOpenSearch = {},
            onClearSearch = {},
            onNavigateSearch = {},
            onNavigateAnomaly = {},
            onOpenSettings = {},
            onOpenShortcuts = {},
            onOpenAbout = {},
            onCaptureDiagnosticReport = {},
            onCloseCurrentTab = {},
            onCloseAllTabs = {},
            onNextTab = {},
            onPrevTab = {},
            onSwitchToTab = {},
            onZoomIn = {},
            onZoomOut = {},
            onResetZoom = {},
            openTabTitles = tabs,
            activeTabIndex = activeTab
        )
    }

    @Test
    fun testCommandRegistryPopulatesCoreCommands() {
        val ctx = createDummyContext(listOf("system.log", "audit.log"), 0)
        val commands = CommandRegistry.getAvailableCommands(ctx)

        assertTrue(commands.isNotEmpty())
        assertTrue(commands.size > 20)

        // Verify categories exist
        val categories = commands.map { it.category }.toSet()
        assertTrue(categories.contains(CommandCategory.ACTIONS))
        assertTrue(categories.contains(CommandCategory.FILES))
        assertTrue(categories.contains(CommandCategory.NAVIGATION))
        assertTrue(categories.contains(CommandCategory.VIEW))
        assertTrue(categories.contains(CommandCategory.THEMES))
        assertTrue(categories.contains(CommandCategory.SETTINGS))
    }

    @Test
    fun testOpenTabsRegisteredDynamically() {
        val ctx = createDummyContext(listOf("alpha.log", "beta.log"), 1)
        val commands = CommandRegistry.getAvailableCommands(ctx)

        val tabCmds = commands.filter { it.id.startsWith("tab.switch.") }
        assertTrue(tabCmds.size == 2)
        assertTrue(tabCmds.any { it.title.contains("alpha.log") })
        assertTrue(tabCmds.any { it.title.contains("beta.log") && it.title.contains("(Active)") })
    }

    @Test
    fun testCategoryPillMapping() {
        val catAll = CommandCategory.fromPillLabel("All")
        assertNull(catAll)

        val catActions = CommandCategory.fromPillLabel("Actions")
        assertNotNull(catActions)
        assertTrue(catActions == CommandCategory.ACTIONS)

        val catFiles = CommandCategory.fromPillLabel("Files")
        assertNotNull(catFiles)
        assertTrue(catFiles == CommandCategory.FILES)

        val catSettings = CommandCategory.fromPillLabel("Settings")
        assertNotNull(catSettings)
        assertTrue(catSettings == CommandCategory.SETTINGS)
    }

    @Test
    fun testPrefixCategoryMapping() {
        assertTrue(CommandCategory.fromPrefixChar('>') == CommandCategory.ACTIONS)
        assertTrue(CommandCategory.fromPrefixChar('#') == CommandCategory.NAVIGATION)
        assertTrue(CommandCategory.fromPrefixChar('@') == CommandCategory.FILES)
        assertTrue(CommandCategory.fromPrefixChar('?') == CommandCategory.ACTIONS)
        assertNull(CommandCategory.fromPrefixChar('x'))
    }

    @Test
    fun testWorkstationAndRawRecordCommands() {
        var rawRecordsCalled = false
        val ctxWithRaw = createDummyContext().copy(onShowRawRecords = { rawRecordsCalled = true })
        val commandsWithRaw = CommandRegistry.getAvailableCommands(ctxWithRaw)

        val showRawCmd = commandsWithRaw.find { it.id == "view.show_raw_records" }
        assertNotNull(showRawCmd)
        showRawCmd?.action?.invoke()
        assertTrue(rawRecordsCalled)

        val ctxWithoutRaw = createDummyContext()
        val commandsWithoutRaw = CommandRegistry.getAvailableCommands(ctxWithoutRaw)
        assertNull(commandsWithoutRaw.find { it.id == "view.show_raw_records" })

        assertNull(commandsWithRaw.find { it.id == "view.open_hprof_workstation" })
        assertNull(commandsWithRaw.find { it.id == "view.open_profiler_workstation" })
    }
}
