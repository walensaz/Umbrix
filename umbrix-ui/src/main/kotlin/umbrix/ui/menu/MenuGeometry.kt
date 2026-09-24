package umbrix.ui.menu

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Deterministic bounding-box math for dropdown placement (Phase 4 §5).
 *
 * All inputs are window-space pixels as delivered by [PopupPositionProvider], so the same functions
 * serve every display density and are unit-testable without a Compose runtime.
 */
object MenuGeometry {

    /**
     * Root dropdown: left-aligned with the anchor and dropped [verticalGapPx] below it, clamped so the
     * panel never leaves the window by less than [edgeMarginPx].
     */
    fun calculateRootMenuPosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        popupContentSize: IntSize,
        verticalGapPx: Int,
        edgeMarginPx: Int
    ): IntOffset {
        val maxX = maxOf(edgeMarginPx, windowSize.width - popupContentSize.width - edgeMarginPx)
        val maxY = maxOf(edgeMarginPx, windowSize.height - popupContentSize.height - edgeMarginPx)
        return IntOffset(
            x = anchorBounds.left.coerceIn(edgeMarginPx, maxX),
            y = (anchorBounds.bottom + verticalGapPx).coerceIn(0, maxY)
        )
    }

    /**
     * Cascading submenu:
     * - X opens rightward overlapping the parent by [overlapPx]; flips leftward when the right edge
     *   would cross `windowWidth - edgeMarginPx`.
     * - Y aligns with the parent row minus [overlapPx], clamped against the bottom edge.
     */
    fun calculateSubmenuPosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        popupContentSize: IntSize,
        overlapPx: Int,
        edgeMarginPx: Int
    ): IntOffset {
        val rightwardX = anchorBounds.right - overlapPx
        val fitsRightward = rightwardX + popupContentSize.width <= windowSize.width - edgeMarginPx
        val preferredX = if (fitsRightward) rightwardX else anchorBounds.left - popupContentSize.width + overlapPx
        val maxX = maxOf(edgeMarginPx, windowSize.width - popupContentSize.width - edgeMarginPx)

        val preferredY = minOf(anchorBounds.top - overlapPx, windowSize.height - popupContentSize.height - edgeMarginPx)

        return IntOffset(
            x = preferredX.coerceIn(edgeMarginPx, maxX),
            y = preferredY.coerceAtLeast(edgeMarginPx)
        )
    }
}

/** Anchors the root hamburger dropdown directly below its trigger button. */
internal class RootMenuPositionProvider(
    private val verticalGapPx: Int,
    private val edgeMarginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = MenuGeometry.calculateRootMenuPosition(anchorBounds, windowSize, popupContentSize, verticalGapPx, edgeMarginPx)
}

/** Places a cascading submenu beside its parent row with edge-collision flipping. */
internal class SubmenuPositionProvider(
    private val overlapPx: Int,
    private val edgeMarginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = MenuGeometry.calculateSubmenuPosition(anchorBounds, windowSize, popupContentSize, overlapPx, edgeMarginPx)
}
