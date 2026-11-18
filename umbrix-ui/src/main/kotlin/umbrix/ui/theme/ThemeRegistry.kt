package umbrix.ui.theme

import umbrix.ui.theme.themes.DarkCarbonTheme
import umbrix.ui.theme.themes.DraculaTheme
import umbrix.ui.theme.themes.GitHubLightTheme
import umbrix.ui.theme.themes.HighContrastTheme
import umbrix.ui.theme.themes.LightIvoryTheme
import umbrix.ui.theme.themes.MidnightBlueTheme
import umbrix.ui.theme.themes.NordTheme
import umbrix.ui.theme.themes.SolarizedDarkTheme
import umbrix.ui.theme.themes.SolarizedLightTheme
import umbrix.ui.theme.themes.SublimeMonokaiTheme

/**
 * Registry of built-in UI color themes for Umbrix Workstation.
 * Provides lookup by name, categorization into dark and light styles, and fallback defaults.
 */
object ThemeRegistry {

    private val builtInThemes: List<UmbrixThemeSpec> = listOf(
        DarkCarbonTheme,
        MidnightBlueTheme,
        SublimeMonokaiTheme,
        DraculaTheme,
        NordTheme,
        SolarizedDarkTheme,
        LightIvoryTheme,
        SolarizedLightTheme,
        GitHubLightTheme,
        HighContrastTheme
    )

    /** Returns all available built-in themes in display order. */
    fun allThemes(): List<UmbrixThemeSpec> = builtInThemes

    /** Returns only themes where isDark is true. */
    fun darkThemes(): List<UmbrixThemeSpec> = builtInThemes.filter { it.isDark }

    /** Returns only themes where isDark is false. */
    fun lightThemes(): List<UmbrixThemeSpec> = builtInThemes.filter { !it.isDark }

    /** Finds a theme by its unique name or display name, or null if not found. */
    fun findByName(name: String): UmbrixThemeSpec? =
        builtInThemes.find { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }

    /** The canonical fallback theme when no preference is configured. */
    fun defaultTheme(): UmbrixThemeSpec = DarkCarbonTheme
}
