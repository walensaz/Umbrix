package umbrix.ui.dialog

/**
 * Controls how format detection behaves when opening a file.
 */
enum class FormatOpenPolicy {
    AUTO,
    FORCE_FORMAT,
    FORCE_PLAIN_LOG,
    AUTO_PLAIN_IF_AMBIGUOUS
}

/**
 * Unified state model for all mutually-exclusive modal dialogs in the Umbrix workstation.
 * Replaces 20+ scattered boolean flags and eliminates parameter explosion in MainScreenModalHost.
 */
sealed interface ActiveDialog {
    data object None : ActiveDialog
    data object Settings : ActiveDialog
    data object GoToLineTimestamp : ActiveDialog
    data object About : ActiveDialog
    data object KeyboardShortcuts : ActiveDialog
    data object ExportSubFile : ActiveDialog
    data object ScratchpadManager : ActiveDialog
    data object BookmarkManager : ActiveDialog
    data object TimelineExport : ActiveDialog
    data class CommandPalette(val initialQuery: String = "") : ActiveDialog
    data class RenameScratchpad(val tabIndex: Int) : ActiveDialog
    data class ConfirmFormatOpen(
        val filePath: String,
        val asNewTab: Boolean,
        val formatDisplayName: String
    ) : ActiveDialog
}
