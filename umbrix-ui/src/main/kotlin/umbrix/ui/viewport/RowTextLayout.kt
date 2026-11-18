package umbrix.ui.viewport

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult

/**
 * Encapsulates the live text layout result and its attached LayoutCoordinates
 * for a specific row in the viewport.
 */
data class RowTextLayout(
    val virtualRow: Long,
    val physicalLine: Long,
    val layout: TextLayoutResult,
    val coordinates: LayoutCoordinates
)
