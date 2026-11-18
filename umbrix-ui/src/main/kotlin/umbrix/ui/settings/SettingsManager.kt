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
        val previous = currentSettings
        currentSettings = newSettings
        persistSettings(newSettings)
        if (previous.disabledPluginIds != newSettings.disabledPluginIds) {
            try {
                for (id in newSettings.disabledPluginIds) {
                    if (umbrix.core.plugin.PluginManager.instance().isPluginEnabled(id)) {
                        umbrix.core.plugin.PluginManager.instance().disablePlugin(id)
                    }
                }
                for (installed in umbrix.core.plugin.PluginManager.instance().allInstalledPlugins()) {
                    val id = installed.manifest().id()
                    if (!newSettings.disabledPluginIds.contains(id) && !umbrix.core.plugin.PluginManager.instance().isPluginEnabled(id)) {
                        umbrix.core.plugin.PluginManager.instance().enablePlugin(id)
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to synchronize plugin state: ${t.message}")
            }
        }
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
        val parentDir = settingsFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        val tmpFile = File(parentDir, ".settings.json.tmp")
        try {
            val json = buildSettingsJson(settings)
            tmpFile.writeText(json, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmpFile.toPath(),
                    settingsFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmpFile.toPath(),
                    settingsFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            logger.info("Persisted workstation settings to {}", settingsFile.absolutePath)
        } catch (e: Throwable) {
            logger.error("Failed to persist settings to {}: {}", settingsFile.absolutePath, e.message)
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
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

        var statusBarShowPosition = true
        var statusBarShowDocumentStats = true
        var statusBarShowMode = true
        var statusBarShowIndexingDetails = false
        var statusBarShowMemory = false

        var commandPaletteStyle = CommandPaletteStyle.OMNI
        var commandPaletteShowShortcuts = true
        var enableVimColonTrigger = true
        var enableDoubleShiftTrigger = true

        var isMcpServerEnabled = true
        var mcpServerPort = 4242
        var aiProvider = "Ollama (Local)"
        var aiModelName = "llama3.2"
        var aiEndpointUrl = "http://localhost:11434"
        var aiApiKey = ""

        var restoreSessionOnStartup = true
        var lastOpenedFiles = emptyList<String>()
        var lastActiveScratchpadId: String? = null
        var disabledPluginIds = emptySet<String>()

        val lines = json.lines()
        for (line in lines) {
            val trimmed = line.trim().trimEnd(',')
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim().trim('"')
                val value = trimmed.substring(colonIdx + 1).trim().trim('"')
                when (key) {
                    "themeName" -> themeName = value
                    "isMinimapEnabled", "isMinimapVisible" -> isMinimapEnabled = value.toBooleanStrictOrNull() ?: true
                    "minimapWidthDp" -> minimapWidthDp = value.toIntOrNull()?.coerceIn(24, 120) ?: 48
                    "isSearchHighlightingEnabled" -> isSearchHighlightingEnabled = value.toBooleanStrictOrNull() ?: true
                    "activeLanguageProfileId", "languageProfile" -> activeLanguageProfileId = value
                    "customErrorKeywords" -> customErrorKeywords = value
                    "customWarnKeywords" -> customWarnKeywords = value
                    "maxShapedCharsPerLine" -> maxShapedCharsPerLine = value.toIntOrNull()?.coerceIn(512, 16384) ?: 4096
                    "enableSimdAcceleration", "isSimdEnabled" -> enableSimdAcceleration = value.toBooleanStrictOrNull() ?: true
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
                    "showWhitespaceChars", "showWhitespace" -> showWhitespaceChars = value.toBooleanStrictOrNull() ?: false
                    "statusBarShowPosition" -> statusBarShowPosition = value.toBooleanStrictOrNull() ?: true
                    "statusBarShowDocumentStats" -> statusBarShowDocumentStats = value.toBooleanStrictOrNull() ?: true
                    "statusBarShowMode" -> statusBarShowMode = value.toBooleanStrictOrNull() ?: true
                    "statusBarShowIndexingDetails" -> statusBarShowIndexingDetails = value.toBooleanStrictOrNull() ?: false
                    "statusBarShowMemory" -> statusBarShowMemory = value.toBooleanStrictOrNull() ?: false
                    "commandPaletteStyle" -> commandPaletteStyle = CommandPaletteStyle.fromId(value)
                    "commandPaletteShowShortcuts" -> commandPaletteShowShortcuts = value.toBooleanStrictOrNull() ?: true
                    "enableVimColonTrigger" -> enableVimColonTrigger = value.toBooleanStrictOrNull() ?: true
                    "enableDoubleShiftTrigger" -> enableDoubleShiftTrigger = value.toBooleanStrictOrNull() ?: true
                    "isMcpServerEnabled" -> isMcpServerEnabled = value.toBooleanStrictOrNull() ?: true
                    "mcpServerPort" -> mcpServerPort = value.toIntOrNull()?.coerceIn(1024, 65535) ?: 4242
                    "aiProvider" -> aiProvider = value
                    "aiModelName" -> aiModelName = value
                    "aiEndpointUrl" -> aiEndpointUrl = value
                    "aiApiKey" -> aiApiKey = value
                    "restoreSessionOnStartup" -> restoreSessionOnStartup = value.toBooleanStrictOrNull() ?: true
                    "lastActiveScratchpadId" -> lastActiveScratchpadId = if (value.isBlank()) null else value
                    "lastOpenedFiles" -> {
                        val rawEscapedValue = trimmed.substring(colonIdx + 1).trim().removeSurrounding("\"")
                        lastOpenedFiles = unescapeJsonString(rawEscapedValue)
                            .split(RECENT_FILES_SEPARATOR)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                    }
                    "disabledPluginIds" -> {
                        val rawEscapedValue = trimmed.substring(colonIdx + 1).trim().removeSurrounding("\"")
                        disabledPluginIds = unescapeJsonString(rawEscapedValue)
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }
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
            recentFiles = recentFiles,
            statusBarShowPosition = statusBarShowPosition,
            statusBarShowDocumentStats = statusBarShowDocumentStats,
            statusBarShowMode = statusBarShowMode,
            statusBarShowIndexingDetails = statusBarShowIndexingDetails,
            statusBarShowMemory = statusBarShowMemory,
            commandPaletteStyle = commandPaletteStyle,
            commandPaletteShowShortcuts = commandPaletteShowShortcuts,
            enableVimColonTrigger = enableVimColonTrigger,
            enableDoubleShiftTrigger = enableDoubleShiftTrigger,
            isMcpServerEnabled = isMcpServerEnabled,
            mcpServerPort = mcpServerPort,
            aiProvider = aiProvider,
            aiModelName = aiModelName,
            aiEndpointUrl = aiEndpointUrl,
            aiApiKey = aiApiKey,
            restoreSessionOnStartup = restoreSessionOnStartup,
            lastOpenedFiles = lastOpenedFiles,
            lastActiveScratchpadId = lastActiveScratchpadId,
            disabledPluginIds = disabledPluginIds
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
            appendLine("  \"statusBarShowPosition\": ${s.statusBarShowPosition},")
            appendLine("  \"statusBarShowDocumentStats\": ${s.statusBarShowDocumentStats},")
            appendLine("  \"statusBarShowMode\": ${s.statusBarShowMode},")
            appendLine("  \"statusBarShowIndexingDetails\": ${s.statusBarShowIndexingDetails},")
            appendLine("  \"statusBarShowMemory\": ${s.statusBarShowMemory},")
            appendLine("  \"commandPaletteStyle\": \"${s.commandPaletteStyle.id}\",")
            appendLine("  \"enableVimColonTrigger\": ${s.enableVimColonTrigger},")
            appendLine("  \"enableDoubleShiftTrigger\": ${s.enableDoubleShiftTrigger},")
            appendLine("  \"isMcpServerEnabled\": ${s.isMcpServerEnabled},")
            appendLine("  \"mcpServerPort\": ${s.mcpServerPort},")
            appendLine("  \"aiProvider\": \"${s.aiProvider.replace("\"", "\\\"")}\",")
            appendLine("  \"aiModelName\": \"${s.aiModelName.replace("\"", "\\\"")}\",")
            appendLine("  \"aiEndpointUrl\": \"${s.aiEndpointUrl.replace("\"", "\\\"")}\",")
            appendLine("  \"aiApiKey\": \"${s.aiApiKey.replace("\"", "\\\"")}\",")
            appendLine("  \"restoreSessionOnStartup\": ${s.restoreSessionOnStartup},")
            appendLine("  \"lastActiveScratchpadId\": \"${s.lastActiveScratchpadId ?: ""}\",")
            appendLine("  \"lastOpenedFiles\": \"${escapeJsonString(s.lastOpenedFiles.joinToString(RECENT_FILES_SEPARATOR))}\",")
            appendLine("  \"disabledPluginIds\": \"${escapeJsonString(s.disabledPluginIds.joinToString(","))}\",")
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
