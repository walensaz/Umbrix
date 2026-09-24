package umbrix.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import umbrix.ui.theme.themes.DarkCarbonTheme

/**
 * Top-level application theme wrapper.
 *
 * Configures JetBrains Jewel's [IntUiTheme] based on [UmbrixThemeSpec.isDark] and provides
 * the active [UmbrixThemeSpec] down the Compose tree via [LocalUmbrixTheme] and [FontSpec]
 * via [LocalUmbrixFonts].
 */
@Composable
fun UmbrixThemedApplication(
    theme: UmbrixThemeSpec = DarkCarbonTheme,
    fonts: FontSpec = FontSpec(),
    content: @Composable () -> Unit
) {
    IntUiTheme(isDark = theme.isDark) {
        CompositionLocalProvider(
            LocalUmbrixTheme provides theme,
            LocalUmbrixFonts provides fonts
        ) {
            content()
        }
    }
}

/**
 * Backward-compatible entrypoint matching earlier call sites.
 */
@Composable
fun UmbrixTheme(
    theme: UmbrixThemeSpec = DarkCarbonTheme,
    fonts: FontSpec = FontSpec(),
    content: @Composable () -> Unit
) {
    UmbrixThemedApplication(theme = theme, fonts = fonts, content = content)
}
