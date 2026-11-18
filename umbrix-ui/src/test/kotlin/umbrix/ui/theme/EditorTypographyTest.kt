package umbrix.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorTypographyTest {

    @Test
    fun testResolvedEditorFontFamilyPrepend() {
        val customFont = EditorTypography.resolvedEditorFontFamily("Fira Code")
        assertNotNull(customFont)

        val defaultFont = EditorTypography.resolvedEditorFontFamily("JetBrains Mono")
        assertNotNull(defaultFont)

        val blankChoiceFont = EditorTypography.resolvedEditorFontFamily("  ")
        assertNotNull(blankChoiceFont)
    }

    @Test
    fun testResolvedUiFontFamilyPrepend() {
        val customFont = EditorTypography.resolvedUiFontFamily("Roboto")
        assertNotNull(customFont)

        val defaultFont = EditorTypography.resolvedUiFontFamily("Inter")
        assertNotNull(defaultFont)
    }

    @Test
    fun testResolveFontFeatureSettings() {
        val enabled = EditorTypography.resolveFontFeatureSettings(true)
        assertEquals("\"liga\" 1, \"calt\" 1", enabled)

        val disabled = EditorTypography.resolveFontFeatureSettings(false)
        assertEquals("\"liga\" 0, \"calt\" 0", disabled)
    }

    @Test
    fun testAvailableSystemMonospaceFonts() {
        val fonts = EditorTypography.availableSystemMonospaceFonts
        assertNotNull(fonts)
        assertTrue(fonts.isNotEmpty())
        assertTrue(fonts.contains("JetBrains Mono"))
    }

    @Test
    fun testAvailableSystemProportionalFonts() {
        val fonts = EditorTypography.availableSystemProportionalFonts
        assertNotNull(fonts)
        assertTrue(fonts.contains("Inter"))
    }
}
