package umbrix.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import java.awt.GraphicsEnvironment

/**
 * Resolves hardware-rendered monospace and UI typography across Windows, macOS, and Linux.
 */
@OptIn(ExperimentalTextApi::class)
object EditorTypography {

    private val FallbackEditorMonospaceFonts = listOf(
        "JetBrains Mono",
        "Cascadia Code",
        "Consolas",
        "SF Mono",
        "Menlo",
        "Monaco",
        "DejaVu Sans Mono"
    )

    private val FallbackUiProportionalFonts = listOf(
        "Inter",
        "Segoe UI",
        "SF Pro Text",
        "Ubuntu",
        "Cantarell",
        "Helvetica Neue",
        "Arial"
    )

    /**
     * Default monospace editor font family, falling back across common desktop OS developer fonts.
     */
    val editorFontFamily: FontFamily
        get() = resolvedEditorFontFamily("JetBrains Mono")

    /**
     * Resolves the monospace editor font family, prepending [userChoice] before
     * the platform fallback chain.
     */
    fun resolvedEditorFontFamily(userChoice: String): FontFamily {
        val fonts = buildList {
            val trimmed = userChoice.trim()
            if (trimmed.isNotBlank()) {
                add(SystemFont(trimmed))
            }
            FallbackEditorMonospaceFonts.forEach { fallbackName ->
                if (!fallbackName.equals(trimmed, ignoreCase = true)) {
                    add(SystemFont(fallbackName))
                }
            }
        }
        return FontFamily(fonts)
    }

    /**
     * Resolves the proportional UI font family for chrome elements, prepending [userChoice].
     */
    fun resolvedUiFontFamily(userChoice: String): FontFamily {
        val fonts = buildList {
            val trimmed = userChoice.trim()
            if (trimmed.isNotBlank()) {
                add(SystemFont(trimmed))
            }
            FallbackUiProportionalFonts.forEach { fallbackName ->
                if (!fallbackName.equals(trimmed, ignoreCase = true)) {
                    add(SystemFont(fallbackName))
                }
            }
        }
        return FontFamily(fonts)
    }

    /**
     * Configures OpenType font feature settings based on ligature preference.
     * When disabled, explicitly turns off 'liga' and 'calt' to ensure operators
     * like '!=', '->', and '<=' render as individual distinct glyphs.
     */
    fun resolveFontFeatureSettings(enableLigatures: Boolean): String {
        return if (enableLigatures) {
            "\"liga\" 1, \"calt\" 1"
        } else {
            "\"liga\" 0, \"calt\" 0"
        }
    }

    /**
     * Queries the local JVM graphics environment for all installed system monospace fonts.
     * Caches results in a lazy property to avoid redundant IPC calls to font subsystems.
     */
    val availableSystemMonospaceFonts: List<String> by lazy {
        queryMonospaceFontFamilies()
    }

    /**
     * Recommended system proportional font family names for UI chrome.
     */
    val availableSystemProportionalFonts: List<String> by lazy {
        listOf("Inter", "Segoe UI", "SF Pro Text", "Ubuntu", "Roboto", "Helvetica Neue", "Arial")
    }

    private fun queryMonospaceFontFamilies(): List<String> {
        val allFonts = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        val knownMonoIdentifiers = setOf(
            "mono", "code", "console", "consolas", "typewriter", "fixed",
            "courier", "source code", "fira", "jetbrains", "cascadia", "hack",
            "inconsolata", "menlo", "monaco", "dejavu sans mono"
        )

        val detectedMono = allFonts.filter { fontName ->
            val lower = fontName.lowercase()
            knownMonoIdentifiers.any { identifier -> lower.contains(identifier) }
        }.sorted()

        return (listOf("JetBrains Mono", "Cascadia Code", "Consolas") + detectedMono).distinct()
    }
}
