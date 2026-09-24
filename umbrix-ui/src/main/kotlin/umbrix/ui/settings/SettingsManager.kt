package umbrix.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.slf4j.LoggerFactory
import umbrix.ui.theme.FontSpec
import java.io.File

/**
 * Manages loading, updating, and persisting user settings to disk at ~/.umbrix/settings.json.
 */
object SettingsManager {

    private val logger = LoggerFactory.getLogger(SettingsManager::class.java)

    private val settingsFile: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val umbrixDir = File(userHome, ".umbrix")
        if (!umbrixDir.exists()) {
            umbrixDir.mkdirs()
        }
        File(umbrixDir, "settings.json")
    }

    var currentSettings by mutableStateOf(loadInitialSettings())
        private set

    fun updateSettings(newSettings: WorkstationSettings) {
        currentSettings = newSettings
        persistSettings(newSettings)
    }

    /** Promotes [filePath] to the top of the persisted Open Recent list. */
    fun recordRecentFile(filePath: String) {
        val updated = currentSettings.withRecentFile(filePath)
        if (updated != currentSettings) {
            updateSettings(updated)
        }
    }

    /** Removes every entry from the persisted Open Recent list. */
    fun clearRecentFiles() {
        if (currentSettings.recentFiles.isNotEmpty()) {
            updateSettings(currentSettings.copy(recentFiles = emptyList()))
        }
    }

    private fun loadInitialSettings(): WorkstationSettings {
        return try {
            if (settingsFile.exists() && settingsFile.isFile) {
                val content = settingsFile.readText(Charsets.UTF_8)
                parseJsonToSettings(content)
            } else {
                WorkstationSettings()
            }
        } catch (e: Throwable) {
            logger.warn("Failed to load settings from {}: {}", settingsFile.absolutePath, e.message)
            WorkstationSettings()
        }
    }

    private fun persistSettings(settings: WorkstationSettings) {
        try {
            val json = buildSettingsJson(settings)
            settingsFile.writeText(json, Charsets.UTF_8)
            logger.info("Persisted workstation settings to {}", settingsFile.absolutePath)
        } catch (e: Throwable) {
            logger.error("Failed to persist settings to {}: {}", settingsFile.absolutePath, e.message)
        }
    }

    internal fun parseJsonToSettings(json: String): WorkstationSettings {
        var themeName = "Dark Carbon"
        var isMinimapEnabled = true
        var minimapWidthDp = 48
        var isSearchHighlightingEnabled = true
        var activeLanguageProfileId = "auto"
        var customErrorKeywords = ""
        var customWarnKeywords = ""
        var maxShapedCharsPerLine = 4096
        var enableSimdAcceleration = true
        var isLatencyGapHighlightingEnabled = true
        var latencyGapThresholdMs = 1000L
        var latencyGapSevereThresholdMs = 5000L

        var editorFontFamily = "JetBrains Mono"
        var editorFontSizePt = 13.0f
        var lineHeightMultiplier = 1.4f
        var editorFontWeight = 400
        var uiFontFamily = "Inter"
        var uiFontSizePt = 13.0f
        var enableLigatures = true

        var viewportMode = ViewportLayoutMode.EXPANDABLE
        var showLineNumbers = true
        var showWhitespaceChars = false
        var recentFiles = emptyList<String>()

        val lines = json.lines()
        for (line in lines) {
            val trimmed = line.trim().trimEnd(',')
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim().trim('"')
                val value = trimmed.substring(colonIdx + 1).trim().trim('"')
                when (key) {
                    "themeName" -> themeName = value
                    "isMinimapEnabled" -> isMinimapEnabled = value.toBooleanStrictOrNull() ?: true
                    "minimapWidthDp" -> minimapWidthDp = value.toIntOrNull()?.coerceIn(24, 120) ?: 48
                    "isSearchHighlightingEnabled" -> isSearchHighlightingEnabled = value.toBooleanStrictOrNull() ?: true
                    "activeLanguageProfileId" -> activeLanguageProfileId = value
                    "customErrorKeywords" -> customErrorKeywords = value
                    "customWarnKeywords" -> customWarnKeywords = value
                    "maxShapedCharsPerLine" -> maxShapedCharsPerLine = value.toIntOrNull()?.coerceIn(512, 16384) ?: 4096
                    "enableSimdAcceleration" -> enableSimdAcceleration = value.toBooleanStrictOrNull() ?: true
                    "isLatencyGapHighlightingEnabled" -> isLatencyGapHighlightingEnabled = value.toBooleanStrictOrNull() ?: true
                    "latencyGapThresholdMs" -> latencyGapThresholdMs = value.toLongOrNull()?.coerceIn(10L, 60000L) ?: 1000L
                    "latencyGapSevereThresholdMs" -> latencyGapSevereThresholdMs = value.toLongOrNull()?.coerceIn(50L, 300000L) ?: 5000L
                    "editorFontFamily" -> editorFontFamily = value
                    "editorFontSizePt" -> editorFontSizePt = value.toFloatOrNull()?.coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE) ?: 13.0f
                    "lineHeightMultiplier" -> lineHeightMultiplier = value.toFloatOrNull()?.coerceIn(FontSpec.MIN_LINE_HEIGHT_MULT, FontSpec.MAX_LINE_HEIGHT_MULT) ?: 1.4f
                    "editorFontWeight" -> editorFontWeight = value.toIntOrNull()?.let { if (it in FontSpec.SUPPORTED_WEIGHTS) it else 400 } ?: 400
                    "uiFontFamily" -> uiFontFamily = value
                    "uiFontSizePt" -> uiFontSizePt = value.toFloatOrNull()?.coerceIn(FontSpec.MIN_UI_FONT_SIZE, FontSpec.MAX_UI_FONT_SIZE) ?: 13.0f
                    "enableLigatures" -> enableLigatures = value.toBooleanStrictOrNull() ?: true
                    "viewportMode" -> viewportMode = ViewportLayoutMode.fromId(value) ?: ViewportLayoutMode.EXPANDABLE
                    "showLineNumbers" -> showLineNumbers = value.toBooleanStrictOrNull() ?: true
                    "showWhitespaceChars" -> showWhitespaceChars = value.toBooleanStrictOrNull() ?: false
                    "recentFiles" -> {
                        // Raw value keeps escaped quotes intact; only the enclosing quote pair is stripped
                        val rawEscapedValue = trimmed.substring(colonIdx + 1).trim().removeSurrounding("\"")
                        recentFiles = unescapeJsonString(rawEscapedValue)
                            .split(RECENT_FILES_SEPARATOR)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .take(WorkstationSettings.MAX_RECENT_FILES)
                    }
                }
            }
        }

        return WorkstationSettings(
            themeName = themeName,
            isMinimapEnabled = isMinimapEnabled,
            minimapWidthDp = minimapWidthDp,
            isSearchHighlightingEnabled = isSearchHighlightingEnabled,
            activeLanguageProfileId = activeLanguageProfileId,
            customErrorKeywords = customErrorKeywords,
            customWarnKeywords = customWarnKeywords,
            maxShapedCharsPerLine = maxShapedCharsPerLine,
            enableSimdAcceleration = enableSimdAcceleration,
            isLatencyGapHighlightingEnabled = isLatencyGapHighlightingEnabled,
            latencyGapThresholdMs = latencyGapThresholdMs,
            latencyGapSevereThresholdMs = latencyGapSevereThresholdMs,
            editorFontFamily = editorFontFamily,
            editorFontSizePt = editorFontSizePt,
            lineHeightMultiplier = lineHeightMultiplier,
            editorFontWeight = editorFontWeight,
            uiFontFamily = uiFontFamily,
            uiFontSizePt = uiFontSizePt,
            enableLigatures = enableLigatures,
            viewportMode = viewportMode,
            showLineNumbers = showLineNumbers,
            showWhitespaceChars = showWhitespaceChars,
            recentFiles = recentFiles
        )
    }

    internal fun buildSettingsJson(s: WorkstationSettings): String {
        return buildString {
            appendLine("{")
            appendLine("  \"themeName\": \"${s.themeName.replace("\"", "\\\"")}\",")
            appendLine("  \"isMinimapEnabled\": ${s.isMinimapEnabled},")
            appendLine("  \"minimapWidthDp\": ${s.minimapWidthDp},")
            appendLine("  \"isSearchHighlightingEnabled\": ${s.isSearchHighlightingEnabled},")
            appendLine("  \"activeLanguageProfileId\": \"${s.activeLanguageProfileId}\",")
            appendLine("  \"customErrorKeywords\": \"${s.customErrorKeywords.replace("\"", "\\\"")}\",")
            appendLine("  \"customWarnKeywords\": \"${s.customWarnKeywords.replace("\"", "\\\"")}\",")
            appendLine("  \"maxShapedCharsPerLine\": ${s.maxShapedCharsPerLine},")
            appendLine("  \"enableSimdAcceleration\": ${s.enableSimdAcceleration},")
            appendLine("  \"isLatencyGapHighlightingEnabled\": ${s.isLatencyGapHighlightingEnabled},")
            appendLine("  \"latencyGapThresholdMs\": ${s.latencyGapThresholdMs},")
            appendLine("  \"latencyGapSevereThresholdMs\": ${s.latencyGapSevereThresholdMs},")
            appendLine("  \"editorFontFamily\": \"${s.editorFontFamily.replace("\"", "\\\"")}\",")
            appendLine("  \"editorFontSizePt\": ${s.editorFontSizePt},")
            appendLine("  \"lineHeightMultiplier\": ${s.lineHeightMultiplier},")
            appendLine("  \"editorFontWeight\": ${s.editorFontWeight},")
            appendLine("  \"uiFontFamily\": \"${s.uiFontFamily.replace("\"", "\\\"")}\",")
            appendLine("  \"uiFontSizePt\": ${s.uiFontSizePt},")
            appendLine("  \"enableLigatures\": ${s.enableLigatures},")
            appendLine("  \"viewportMode\": \"${s.viewportMode.id}\",")
            appendLine("  \"showLineNumbers\": ${s.showLineNumbers},")
            appendLine("  \"showWhitespaceChars\": ${s.showWhitespaceChars},")
            appendLine("  \"recentFiles\": \"${escapeJsonString(s.recentFiles.joinToString(RECENT_FILES_SEPARATOR))}\"")
            appendLine("}")
        }
    }

    // Newline cannot appear in real file paths and survives the line-oriented parser once escaped as \n
    private const val RECENT_FILES_SEPARATOR = "\n"

    internal fun escapeJsonString(rawText: String): String = buildString(rawText.length + 8) {
        for (character in rawText) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    internal fun unescapeJsonString(escapedText: String): String = buildString(escapedText.length) {
        var characterIndex = 0
        while (characterIndex < escapedText.length) {
            val character = escapedText[characterIndex]
            if (character == '\\' && characterIndex + 1 < escapedText.length) {
                when (val escapeCode = escapedText[characterIndex + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(escapeCode)
                }
                characterIndex += 2
            } else {
                append(character)
                characterIndex += 1
            }
        }
    }
}
