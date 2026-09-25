package umbrix.ui.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import umbrix.ui.settings.ViewportLayoutMode
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.ThemeRegistry
import umbrix.ui.theme.UmbrixTheme
import java.io.File

/**
 * Declarative content tree of the workstation hamburger menu (Phase 4 §4).
 *
 * All checkmark/radio state is derived from the [settings] snapshot, so the menu always reflects
 * persisted preferences. Theme entries are generated from [ThemeRegistry] (Dark → Light → High Contrast)
 * so newly registered presets appear without touching this file.
 *
 * @param settings Current workstation settings (theme, viewport mode, view toggles, recent files).
 * @param onAction Invoked for every terminal (leaf) menu row.
 */
@Composable
fun ColumnScope.HamburgerMenuContent(
    settings: WorkstationSettings,
    onAction: (MenuAction) -> Unit
) {
    // --- Section 1: File Operations ---
    UmbrixMenuItem(
        text = "Open File...",
        shortcut = "Ctrl+O",
        icon = { UmbrixMenuGlyph("📂") },
        onClick = { onAction(MenuAction.OpenFile) }
    )

    UmbrixSubMenu(
        text = "Open Recent",
        icon = { UmbrixMenuGlyph("🕘") }
    ) {
        if (settings.recentFiles.isEmpty()) {
            UmbrixMenuItem(
                text = "(No Recent Files)",
                enabled = false,
                onClick = {}
            )
        } else {
            settings.recentFiles.take(WorkstationSettings.MAX_RECENT_FILES).forEach { recentFilePath ->
                UmbrixMenuItem(
                    text = recentFileLabel(recentFilePath),
                    hoverKey = recentFilePath,
                    onClick = { onAction(MenuAction.OpenRecentFile(recentFilePath)) }
                )
            }
            UmbrixMenuSeparator()
            UmbrixMenuItem(
                text = "Clear Recent Files",
                onClick = { onAction(MenuAction.ClearRecentFiles) }
            )
        }
    }

    UmbrixMenuItem(
        text = "Export Sub-File...",
        shortcut = "Ctrl+Shift+S",
        icon = { UmbrixMenuGlyph("⤓") },
        onClick = { onAction(MenuAction.ExportSubFile) }
    )

    UmbrixMenuSeparator()

    // --- Section 2: Search & Navigation ---
    UmbrixMenuItem(
        text = "Find...",
        shortcut = "Ctrl+F",
        icon = { UmbrixMenuGlyph("🔍") },
        onClick = { onAction(MenuAction.Find) }
    )

    UmbrixMenuItem(
        text = "Go To Line / Timestamp...",
        shortcut = "Ctrl+G",
        icon = { UmbrixMenuGlyph("⏩") },
        onClick = { onAction(MenuAction.GoToLineTimestamp) }
    )

    UmbrixMenuItem(
        text = "Temporal Range...",
        shortcut = "Ctrl+T",
        icon = { UmbrixMenuGlyph("🕐") },
        onClick = { onAction(MenuAction.TemporalRange) }
    )

    UmbrixMenuSeparator()

    // --- Section 3: View Options ---
    UmbrixSubMenu(
        text = "View",
        icon = { UmbrixMenuGlyph("📋") }
    ) {
        ViewportLayoutMode.entries.forEach { layoutMode ->
            UmbrixMenuItem(
                text = layoutMode.displayLabel,
                selected = settings.viewportMode == layoutMode,
                onClick = { onAction(MenuAction.SetViewportMode(layoutMode)) }
            )
        }

        UmbrixMenuSeparator()

        ViewToggle.entries.forEach { viewToggle ->
            UmbrixMenuItem(
                text = viewToggle.displayLabel,
                checked = MenuSettingsReducer.isToggleEnabled(viewToggle, settings),
                onClick = { onAction(MenuAction.ToggleView(viewToggle)) }
            )
        }
    }

    // --- Section 4: Theme Switching ---
    UmbrixSubMenu(
        text = "Theme",
        icon = { UmbrixMenuGlyph("🎨") }
    ) {
        val activeThemeName = (ThemeRegistry.findByName(settings.themeName) ?: ThemeRegistry.defaultTheme()).name
        val themeGroups = remember {
            val highContrastThemes = ThemeRegistry.allThemes().filter { it.name.contains("High Contrast", ignoreCase = true) }
            listOf(
                ThemeRegistry.darkThemes() - highContrastThemes.toSet(),
                ThemeRegistry.lightThemes() - highContrastThemes.toSet(),
                highContrastThemes
            ).filter { it.isNotEmpty() }
        }

        themeGroups.forEachIndexed { groupIndex, themesInGroup ->
            if (groupIndex > 0) {
                UmbrixMenuSeparator()
            }
            themesInGroup.forEach { theme ->
                UmbrixMenuItem(
                    text = theme.displayName,
                    hoverKey = theme.name,
                    selected = theme.name == activeThemeName,
                    onClick = { onAction(MenuAction.SetTheme(theme.name)) }
                )
            }
        }
    }

    UmbrixMenuSeparator()

    // --- Section 5: Settings & Diagnostics ---
    UmbrixMenuItem(
        text = "Settings...",
        shortcut = "Ctrl+,",
        icon = { UmbrixMenuGlyph("⚙") },
        onClick = { onAction(MenuAction.OpenSettings) }
    )

    UmbrixMenuItem(
        text = "Diagnostic Report",
        icon = { UmbrixMenuGlyph("📊") },
        onClick = { onAction(MenuAction.DiagnosticReport) }
    )

    UmbrixMenuSeparator()

    // --- Section 6: About & Exit ---
    UmbrixMenuItem(
        text = "Keyboard Shortcuts",
        shortcut = "F1",
        icon = { UmbrixMenuGlyph("⌨") },
        onClick = { onAction(MenuAction.KeyboardShortcuts) }
    )

    UmbrixMenuItem(
        text = "About Umbrix",
        icon = { UmbrixMenuGlyph("❓") },
        onClick = { onAction(MenuAction.About) }
    )

    UmbrixMenuItem(
        text = "Exit",
        shortcut = "Alt+F4",
        icon = { UmbrixMenuGlyph("✕", tint = UmbrixTheme.colors.severityError) },
        onClick = { onAction(MenuAction.Exit) }
    )
}

/** "file.log — C:\parent\dir": file name first so ellipsis truncation hides the least useful part. */
internal fun recentFileLabel(filePath: String): String {
    val recentFile = File(filePath)
    val parentDirectory = recentFile.parent
    return if (parentDirectory.isNullOrEmpty()) recentFile.name else "${recentFile.name}  —  $parentDirectory"
}
