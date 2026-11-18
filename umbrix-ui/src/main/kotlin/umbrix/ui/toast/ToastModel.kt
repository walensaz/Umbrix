package umbrix.ui.toast

/**
 * Visual severity/style categories for non-blocking workstation toast alerts.
 */
enum class ToastType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * Immutable descriptor for an active toast notification.
 *
 * @property id Unique sequential identifier.
 * @property message The text content displayed to the user.
 * @property type Category determining background tint and icon.
 * @property durationMs Display duration before auto-dismissal.
 */
data class ToastMessage(
    val id: Long,
    val message: String,
    val type: ToastType = ToastType.INFO,
    val durationMs: Long = 2500L
)
