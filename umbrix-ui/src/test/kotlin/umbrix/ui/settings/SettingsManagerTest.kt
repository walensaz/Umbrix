package umbrix.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsManagerTest {

    @Test
    fun testSettingsDefaultValues() {
        val settings = WorkstationSettings()
        assertTrue(settings.isMinimapEnabled)
        assertEquals(48, settings.minimapWidthDp)
        assertTrue(settings.isSearchHighlightingEnabled)
        assertEquals("auto", settings.activeLanguageProfileId)
        assertEquals(4096, settings.maxShapedCharsPerLine)
        assertTrue(settings.enableSimdAcceleration)
    }

    @Test
    fun testSettingsUpdate() {
        val updated = WorkstationSettings(
            isMinimapEnabled = false,
            minimapWidthDp = 64,
            isSearchHighlightingEnabled = false,
            activeLanguageProfileId = "python"
        )
        assertEquals(false, updated.isMinimapEnabled)
        assertEquals(64, updated.minimapWidthDp)
        assertEquals(false, updated.isSearchHighlightingEnabled)
        assertEquals("python", updated.activeLanguageProfileId)
    }
}
