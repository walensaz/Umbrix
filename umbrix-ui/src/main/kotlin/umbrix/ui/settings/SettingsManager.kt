package umbrix.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.slf4j.LoggerFactory
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

    private fun parseJsonToSettings(json: String): WorkstationSettings {
        var isMinimapEnabled = true
        var minimapWidthDp = 48
        var isSearchHighlightingEnabled = true
        var activeLanguageProfileId = "auto"
        var customErrorKeywords = ""
        var customWarnKeywords = ""
        var maxShapedCharsPerLine = 4096
        var enableSimdAcceleration = true

        val lines = json.lines()
        for (line in lines) {
            val trimmed = line.trim().trimEnd(',')
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim().trim('"')
                val value = trimmed.substring(colonIdx + 1).trim().trim('"')
                when (key) {
                    "isMinimapEnabled" -> isMinimapEnabled = value.toBooleanStrictOrNull() ?: true
                    "minimapWidthDp" -> minimapWidthDp = value.toIntOrNull()?.coerceIn(24, 120) ?: 48
                    "isSearchHighlightingEnabled" -> isSearchHighlightingEnabled = value.toBooleanStrictOrNull() ?: true
                    "activeLanguageProfileId" -> activeLanguageProfileId = value
                    "customErrorKeywords" -> customErrorKeywords = value
                    "customWarnKeywords" -> customWarnKeywords = value
                    "maxShapedCharsPerLine" -> maxShapedCharsPerLine = value.toIntOrNull()?.coerceIn(512, 16384) ?: 4096
                    "enableSimdAcceleration" -> enableSimdAcceleration = value.toBooleanStrictOrNull() ?: true
                }
            }
        }

        return WorkstationSettings(
            isMinimapEnabled = isMinimapEnabled,
            minimapWidthDp = minimapWidthDp,
            isSearchHighlightingEnabled = isSearchHighlightingEnabled,
            activeLanguageProfileId = activeLanguageProfileId,
            customErrorKeywords = customErrorKeywords,
            customWarnKeywords = customWarnKeywords,
            maxShapedCharsPerLine = maxShapedCharsPerLine,
            enableSimdAcceleration = enableSimdAcceleration
        )
    }

    private fun buildSettingsJson(s: WorkstationSettings): String {
        return buildString {
            appendLine("{")
            appendLine("  \"isMinimapEnabled\": ${s.isMinimapEnabled},")
            appendLine("  \"minimapWidthDp\": ${s.minimapWidthDp},")
            appendLine("  \"isSearchHighlightingEnabled\": ${s.isSearchHighlightingEnabled},")
            appendLine("  \"activeLanguageProfileId\": \"${s.activeLanguageProfileId}\",")
            appendLine("  \"customErrorKeywords\": \"${s.customErrorKeywords.replace("\"", "\\\"")}\",")
            appendLine("  \"customWarnKeywords\": \"${s.customWarnKeywords.replace("\"", "\\\"")}\",")
            appendLine("  \"maxShapedCharsPerLine\": ${s.maxShapedCharsPerLine},")
            appendLine("  \"enableSimdAcceleration\": ${s.enableSimdAcceleration}")
            appendLine("}")
        }
    }
}
