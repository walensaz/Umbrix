package umbrix.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeRegistryTest {

    @Test
    fun testAllThemesRegistered() {
        val themes = ThemeRegistry.allThemes()
        assertEquals(10, themes.size, "ThemeRegistry must contain exactly 10 built-in themes")
    }

    @Test
    fun testDarkAndLightCounts() {
        val dark = ThemeRegistry.darkThemes()
        val light = ThemeRegistry.lightThemes()
        assertEquals(7, dark.size, "Must have 7 dark theme presets")
        assertEquals(3, light.size, "Must have 3 light theme presets")
        assertEquals(10, dark.size + light.size)
        assertTrue(dark.all { it.isDark })
        assertTrue(light.all { !it.isDark })
    }

    @Test
    fun testFindByName() {
        assertNotNull(ThemeRegistry.findByName("Dark Carbon"))
        assertNotNull(ThemeRegistry.findByName("dark carbon"))
        assertNotNull(ThemeRegistry.findByName("Midnight Blue"))
        assertNotNull(ThemeRegistry.findByName("Sublime Monokai"))
        assertNotNull(ThemeRegistry.findByName("Dracula"))
        assertNotNull(ThemeRegistry.findByName("Nord"))
        assertNotNull(ThemeRegistry.findByName("Solarized Dark"))
        assertNotNull(ThemeRegistry.findByName("Light Ivory"))
        assertNotNull(ThemeRegistry.findByName("Solarized Light"))
        assertNotNull(ThemeRegistry.findByName("GitHub Light"))
        assertNotNull(ThemeRegistry.findByName("High Contrast"))
        assertNotNull(ThemeRegistry.findByName("High Contrast (AAA)"))
        assertNull(ThemeRegistry.findByName("NonExistentTheme"))
    }

    @Test
    fun testDefaultTheme() {
        val defaultTheme = ThemeRegistry.defaultTheme()
        assertEquals("Dark Carbon", defaultTheme.name)
        assertTrue(defaultTheme.isDark)
    }

    @Test
    fun testThemeNamesAreUnique() {
        val names = ThemeRegistry.allThemes().map { it.name }
        assertEquals(names.distinct().size, names.size, "All theme names must be distinct")
        val displayNames = ThemeRegistry.allThemes().map { it.displayName }
        assertEquals(displayNames.distinct().size, displayNames.size, "All display names must be distinct")
    }

    @Test
    fun testThemesTokenCompleteness() {
        for (theme in ThemeRegistry.allThemes()) {
            assertNotNull(theme.name)
            assertNotNull(theme.displayName)
            assertNotNull(theme.surface)
            assertNotNull(theme.surfacePanel)
            assertNotNull(theme.surfaceElevated)
            assertNotNull(theme.surfaceGutter)
            assertNotNull(theme.surfaceScrollbarTrack)
            assertNotNull(theme.textPrimary)
            assertNotNull(theme.textEditor)
            assertNotNull(theme.textMuted)
            assertNotNull(theme.textDimmed)
            assertNotNull(theme.accentPrimary)
            assertNotNull(theme.accentSecondary)
            assertNotNull(theme.severityError)
            assertNotNull(theme.severityWarn)
            assertNotNull(theme.severityInfo)
            assertNotNull(theme.severityDebug)
            assertNotNull(theme.severityTrace)
            assertNotNull(theme.searchHighlightActive)
            assertNotNull(theme.searchHighlightInactive)
            assertNotNull(theme.searchTextActive)
            assertNotNull(theme.searchTextInactive)
            assertNotNull(theme.selectionBackground)
        }
    }
}
