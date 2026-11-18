package umbrix.ui.launcher

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle

/** Work-area-aware sizing for the borderless desktop window. */
internal object DesktopWindowGeometry {
    private const val INITIAL_WINDOW_WIDTH_DP = 1200
    private const val INITIAL_WINDOW_HEIGHT_DP = 760
    private const val INITIAL_WORK_AREA_FRACTION = 0.9

    /** Returns the monitor's usable area after excluding taskbars, docks, and other screen insets. */
    fun usableBounds(screenBounds: Rectangle, screenInsets: Insets): Rectangle {
        val leftInset = screenInsets.left.coerceIn(0, screenBounds.width)
        val rightInset = screenInsets.right.coerceIn(0, screenBounds.width - leftInset)
        val topInset = screenInsets.top.coerceIn(0, screenBounds.height)
        val bottomInset = screenInsets.bottom.coerceIn(0, screenBounds.height - topInset)

        return Rectangle(
            screenBounds.x + leftInset,
            screenBounds.y + topInset,
            (screenBounds.width - leftInset - rightInset).coerceAtLeast(1),
            (screenBounds.height - topInset - bottomInset).coerceAtLeast(1)
        )
    }

    /** Caps startup dimensions at 90% of the work area while retaining a comfortable default size. */
    fun initialSize(workArea: Rectangle): Dimension = Dimension(
        minOf(INITIAL_WINDOW_WIDTH_DP, (workArea.width * INITIAL_WORK_AREA_FRACTION).toInt().coerceAtLeast(1)),
        minOf(INITIAL_WINDOW_HEIGHT_DP, (workArea.height * INITIAL_WORK_AREA_FRACTION).toInt().coerceAtLeast(1))
    )
}
