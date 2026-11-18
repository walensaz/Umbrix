package umbrix.ui.menu

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class MenuGeometryTest {

    private val window = IntSize(1380, 860)
    private val submenuSize = IntSize(240, 300)

    @Test
    fun testRootMenuAnchorsBelowTrigger() {
        val hamburgerButton = IntRect(left = 34, top = 4, right = 60, bottom = 30)
        val position = MenuGeometry.calculateRootMenuPosition(hamburgerButton, window, IntSize(260, 400), verticalGapPx = 4, edgeMarginPx = 8)
        assertEquals(IntOffset(34, 34), position)
    }

    @Test
    fun testRootMenuClampsToSmallWindow() {
        val hamburgerButton = IntRect(left = 34, top = 4, right = 60, bottom = 30)
        val position = MenuGeometry.calculateRootMenuPosition(hamburgerButton, IntSize(300, 300), IntSize(260, 400), verticalGapPx = 4, edgeMarginPx = 8)
        assertEquals(IntOffset(32, 8), position)
    }

    @Test
    fun testSubmenuOpensRightwardWithOverlap() {
        val parentRow = IntRect(left = 40, top = 100, right = 300, bottom = 132)
        val position = MenuGeometry.calculateSubmenuPosition(parentRow, window, submenuSize, overlapPx = 4, edgeMarginPx = 8)
        assertEquals(IntOffset(296, 96), position)
    }

    @Test
    fun testSubmenuFlipsLeftwardAtRightEdge() {
        val parentRow = IntRect(left = 1100, top = 100, right = 1360, bottom = 132)
        val position = MenuGeometry.calculateSubmenuPosition(parentRow, window, submenuSize, overlapPx = 4, edgeMarginPx = 8)
        assertEquals(IntOffset(1100 - 240 + 4, 96), position)
    }

    @Test
    fun testSubmenuClampsAgainstBottomEdge() {
        val parentRow = IntRect(left = 40, top = 800, right = 300, bottom = 832)
        val position = MenuGeometry.calculateSubmenuPosition(parentRow, window, submenuSize, overlapPx = 4, edgeMarginPx = 8)
        assertEquals(860 - 300 - 8, position.y)
    }
}
