package umbrix.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont

/**
 * Resolves crisp, hardware-rendered monospace typography across Windows, macOS, and Linux.
 *
 * Employs standard developer monospace system fonts in order of platform preference:
 * JetBrains Mono -> Cascadia Code -> Consolas -> SF Mono -> Menlo -> Monaco -> DejaVu Sans Mono.
 */
@OptIn(ExperimentalTextApi::class)
object EditorTypography {
    val editorFontFamily: FontFamily = FontFamily(
        SystemFont("JetBrains Mono"),
        SystemFont("Cascadia Code"),
        SystemFont("Consolas"),
        SystemFont("SF Mono"),
        SystemFont("Menlo"),
        SystemFont("Monaco"),
        SystemFont("DejaVu Sans Mono")
    )
}
