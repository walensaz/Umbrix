package umbrix.ui.settings

import umbrix.ui.theme.FontSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsManagerTest {

    @Test
    fun testSettingsDefaultValues() {
        val settings = WorkstationSettings()
        assertEquals("Dark Carbon", settings.themeName)
        assertTrue(settings.isMinimapEnabled)
        assertEquals(48, settings.minimapWidthDp)
        assertTrue(settings.isSearchHighlightingEnabled)
        assertEquals("auto", settings.activeLanguageProfileId)
        assertEquals(4096, settings.maxShapedCharsPerLine)
        assertTrue(settings.enableSimdAcceleration)
        assertTrue(settings.isLatencyGapHighlightingEnabled)
        assertEquals(1000L, settings.latencyGapThresholdMs)
        assertEquals(5000L, settings.latencyGapSevereThresholdMs)

        // Typography defaults
        assertEquals("JetBrains Mono", settings.editorFontFamily)
        assertEquals(13.0f, settings.editorFontSizePt)
        assertEquals(1.4f, settings.lineHeightMultiplier)
        assertEquals(400, settings.editorFontWeight)
        assertEquals("Inter", settings.uiFontFamily)
        assertEquals(13.0f, settings.uiFontSizePt)
        assertTrue(settings.enableLigatures)
    }

    @Test
    fun testToFontSpec() {
        val settings = WorkstationSettings(
            editorFontFamily = "Cascadia Code",
            editorFontSizePt = 15.0f,
            lineHeightMultiplier = 1.5f,
            editorFontWeight = 500,
            uiFontFamily = "Segoe UI",
            uiFontSizePt = 14.0f,
            enableLigatures = false
        )
        val fontSpec = settings.toFontSpec()
        assertEquals("Cascadia Code", fontSpec.editorFontFamily)
        assertEquals(15.0f, fontSpec.editorFontSizePt)
        assertEquals(1.5f, fontSpec.lineHeightMultiplier)
        assertEquals(500, fontSpec.editorFontWeight)
        assertEquals("Segoe UI", fontSpec.uiFontFamily)
        assertEquals(14.0f, fontSpec.uiFontSizePt)
        assertEquals(false, fontSpec.enableLigatures)
    }

    @Test
    fun testSettingsUpdate() {
        val updated = WorkstationSettings(
            themeName = "GitHub Light",
            isMinimapEnabled = false,
            minimapWidthDp = 64,
            isSearchHighlightingEnabled = false,
            activeLanguageProfileId = "python",
            editorFontFamily = "Consolas",
            editorFontSizePt = 14.0f,
            lineHeightMultiplier = 1.3f,
            editorFontWeight = 700,
            enableLigatures = false
        )
        assertEquals("GitHub Light", updated.themeName)
        assertEquals(false, updated.isMinimapEnabled)
        assertEquals(64, updated.minimapWidthDp)
        assertEquals(false, updated.isSearchHighlightingEnabled)
        assertEquals("python", updated.activeLanguageProfileId)
        assertEquals("Consolas", updated.editorFontFamily)
        assertEquals(14.0f, updated.editorFontSizePt)
        assertEquals(1.3f, updated.lineHeightMultiplier)
        assertEquals(700, updated.editorFontWeight)
        assertEquals(false, updated.enableLigatures)
    }

    @Test
    fun testPhase4Defaults() {
        val settings = WorkstationSettings()
        assertEquals(ViewportLayoutMode.EXPANDABLE, settings.viewportMode)
        assertTrue(settings.showLineNumbers)
        assertEquals(false, settings.showWhitespaceChars)
        assertTrue(settings.recentFiles.isEmpty())
    }

    @Test
    fun testWithRecentFilePromotesDeduplicatesAndCaps() {
        val seeded = (1..WorkstationSettings.MAX_RECENT_FILES).fold(WorkstationSettings()) { settings, fileNumber ->
            settings.withRecentFile("/logs/file$fileNumber.log")
        }
        assertEquals("/logs/file8.log", seeded.recentFiles.first())

        val promoted = seeded.withRecentFile("/logs/file3.log")
        assertEquals("/logs/file3.log", promoted.recentFiles.first())
        assertEquals(WorkstationSettings.MAX_RECENT_FILES, promoted.recentFiles.size)
        assertEquals(1, promoted.recentFiles.count { it == "/logs/file3.log" })

        val overflowed = promoted.withRecentFile("/logs/new.log")
        assertEquals(WorkstationSettings.MAX_RECENT_FILES, overflowed.recentFiles.size)
        assertEquals("/logs/new.log", overflowed.recentFiles.first())
        assertEquals(overflowed, overflowed.withRecentFile("   "))
    }

    @Test
    fun testJsonRoundTripPreservesPhase4Fields() {
        val original = WorkstationSettings(
            themeName = "Nord",
            viewportMode = ViewportLayoutMode.FULL_WRAP,
            showLineNumbers = false,
            showWhitespaceChars = true,
            recentFiles = listOf("C:\\Logs\\production-error.log", "/var/log/app/server \"quoted\".log")
        )
        val parsed = SettingsManager.parseJsonToSettings(SettingsManager.buildSettingsJson(original))
        assertEquals(original, parsed)
    }

    @Test
    fun testJsonParsingToleratesLegacyAndInvalidPhase4Values() {
        val legacyJson = "{\n  \"themeName\": \"Dracula\",\n  \"viewportMode\": \"bogus\"\n}"
        val parsed = SettingsManager.parseJsonToSettings(legacyJson)
        assertEquals("Dracula", parsed.themeName)
        assertEquals(ViewportLayoutMode.EXPANDABLE, parsed.viewportMode)
        assertTrue(parsed.recentFiles.isEmpty())
    }

    @Test
    fun testSettingsManagerRoundTrip() {
        val initial = SettingsManager.currentSettings
        try {
            val testSettings = initial.copy(
                themeName = "Dracula",
                minimapWidthDp = 80,
                editorFontFamily = "Consolas",
                editorFontSizePt = 16.0f,
                lineHeightMultiplier = 1.6f,
                editorFontWeight = 500,
                enableLigatures = false
            )
            SettingsManager.updateSettings(testSettings)
            assertEquals("Dracula", SettingsManager.currentSettings.themeName)
            assertEquals(80, SettingsManager.currentSettings.minimapWidthDp)
            assertEquals("Consolas", SettingsManager.currentSettings.editorFontFamily)
            assertEquals(16.0f, SettingsManager.currentSettings.editorFontSizePt)
            assertEquals(1.6f, SettingsManager.currentSettings.lineHeightMultiplier)
            assertEquals(500, SettingsManager.currentSettings.editorFontWeight)
            assertEquals(false, SettingsManager.currentSettings.enableLigatures)
        } finally {
            SettingsManager.updateSettings(initial)
        }
    }

    @Test
    fun testStatusBarTelemetryJsonRoundTrip() {
        val original = WorkstationSettings(
            statusBarShowPosition = false,
            statusBarShowDocumentStats = false,
            statusBarShowMode = false,
            statusBarShowIndexingDetails = true,
            statusBarShowMemory = true
        )
        val json = SettingsManager.buildSettingsJson(original)
        val parsed = SettingsManager.parseJsonToSettings(json)
        assertEquals(false, parsed.statusBarShowPosition)
        assertEquals(false, parsed.statusBarShowDocumentStats)
        assertEquals(false, parsed.statusBarShowMode)
        assertEquals(true, parsed.statusBarShowIndexingDetails)
        assertEquals(true, parsed.statusBarShowMemory)
    }

    @Test
    fun testLegacyKeyAliasesParsing() {
        val jsonWithAliases = """
            {
              "isMinimapVisible": false,
              "languageProfile": "syslog",
              "isSimdEnabled": false,
              "showWhitespace": true
            }
        """.trimIndent()
        val parsed = SettingsManager.parseJsonToSettings(jsonWithAliases)
        assertEquals(false, parsed.isMinimapEnabled)
        assertEquals("syslog", parsed.activeLanguageProfileId)
        assertEquals(false, parsed.enableSimdAcceleration)
        assertEquals(true, parsed.showWhitespaceChars)
    }
}
