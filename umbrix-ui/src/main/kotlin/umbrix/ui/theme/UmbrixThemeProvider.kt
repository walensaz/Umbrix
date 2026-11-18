package umbrix.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import umbrix.ui.theme.themes.DarkCarbonTheme

/**
 * CompositionLocal key holding the active [UmbrixThemeSpec].
 * Defaults to [DarkCarbonTheme] if accessed outside an explicit provider.
 */
val LocalUmbrixTheme = staticCompositionLocalOf<UmbrixThemeSpec> {
    DarkCarbonTheme
}

/**
 * Ambient composition local holding active typography configuration.
 */
val LocalUmbrixFonts = staticCompositionLocalOf<FontSpec> {
    FontSpec()
}

/**
 * Accessor object for querying current typography within composables.
 */
object UmbrixFonts {
    val current: FontSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalUmbrixFonts.current
}

/**
 * Public accessor object providing idiomatic syntax for theme tokens in composable functions:
 * `UmbrixTheme.colors.surfacePanel`, `UmbrixTheme.fonts.editorFontSizePt`
 */
object UmbrixTheme {
    val colors: UmbrixThemeSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalUmbrixTheme.current

    val fonts: FontSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalUmbrixFonts.current
}
