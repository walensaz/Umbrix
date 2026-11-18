package umbrix.ui.theme

import androidx.compose.ui.text.font.FontWeight
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontSpecTest {

    @Test
    fun testDefaultFontSpecValues() {
        val spec = FontSpec()
        assertEquals("JetBrains Mono", spec.editorFontFamily)
        assertEquals(13.0f, spec.editorFontSizePt)
        assertEquals(1.4f, spec.lineHeightMultiplier)
        assertEquals(400, spec.editorFontWeight)
        assertEquals("Inter", spec.uiFontFamily)
        assertEquals(13.0f, spec.uiFontSizePt)
        assertTrue(spec.enableLigatures)
        assertEquals(FontWeight.Normal, spec.resolvedEditorWeight)
    }

    @Test
    fun testFontWeightMapping() {
        assertEquals(FontWeight.Light, FontSpec(editorFontWeight = 300).resolvedEditorWeight)
        assertEquals(FontWeight.Normal, FontSpec(editorFontWeight = 400).resolvedEditorWeight)
        assertEquals(FontWeight.Medium, FontSpec(editorFontWeight = 500).resolvedEditorWeight)
        assertEquals(FontWeight.SemiBold, FontSpec(editorFontWeight = 600).resolvedEditorWeight)
        assertEquals(FontWeight.Bold, FontSpec(editorFontWeight = 700).resolvedEditorWeight)
        assertEquals(FontWeight.W400, FontSpec(editorFontWeight = 999).resolvedEditorWeight)
    }

    @Test
    fun testValidationBounds() {
        // editorFontSizePt bounds [8.0, 32.0]
        assertThrows<IllegalArgumentException> {
            FontSpec(editorFontSizePt = 7.9f)
        }
        assertThrows<IllegalArgumentException> {
            FontSpec(editorFontSizePt = 32.1f)
        }

        // lineHeightMultiplier bounds [1.0, 2.0]
        assertThrows<IllegalArgumentException> {
            FontSpec(lineHeightMultiplier = 0.9f)
        }
        assertThrows<IllegalArgumentException> {
            FontSpec(lineHeightMultiplier = 2.1f)
        }

        // uiFontSizePt bounds [10.0, 20.0]
        assertThrows<IllegalArgumentException> {
            FontSpec(uiFontSizePt = 9.9f)
        }
        assertThrows<IllegalArgumentException> {
            FontSpec(uiFontSizePt = 20.1f)
        }
    }
}
