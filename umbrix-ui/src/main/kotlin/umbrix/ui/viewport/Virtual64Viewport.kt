package umbrix.ui.viewport
import umbrix.ui.settings.ViewportLayoutMode

/**
 * Backward-compatible type alias for [ViewportLayoutMode].
 */
typealias ViewportMode = ViewportLayoutMode

/**
 * 64-bit Viewport state completely immune to 32-bit integer overflow and Float scroll jitter.
 *
 * @property topVisibleLine 64-bit index of the topmost visible line in the viewport.
 * @property subPixelOffsetY Sub-pixel float offset within the top line [0.0, lineHeight).
 * @property mode Active layout mode.
 * @property baseRowHeight Base row height in pixels (default 20.0f).
 */
data class ViewportState(
    val topVisibleLine: Long = 0L,
    val subPixelOffsetY: Float = 0.0f,
    val mode: ViewportLayoutMode = ViewportLayoutMode.EXPANDABLE,
    val baseRowHeight: Float = 20.0f
) {
    companion object {
        /** Hard clamp limit for Skia text shaping to protect against multi-megabyte single lines. */
        const val MAX_SHAPED_CHARS: Int = 4096

        /**
         * Clamps raw line string if it exceeds [MAX_SHAPED_CHARS] to prevent Skia text layout stalls.
         * Authoritative line normalization (tabs, carriage returns, null bytes, unprintable control characters)
         * is performed directly by [umbrix.index.PhysicalLineIndexerImpl.getLineText].
         */
        fun clampForDisplay(text: String): String {
            return if (text.length > MAX_SHAPED_CHARS) {
                text.substring(0, MAX_SHAPED_CHARS) + " [… +${text.length - MAX_SHAPED_CHARS} chars clamped]"
            } else {
                text
            }
        }
    }
}
