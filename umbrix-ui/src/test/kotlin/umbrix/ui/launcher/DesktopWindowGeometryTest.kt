package umbrix.ui.launcher

import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowGeometryTest {

    @Test
    fun usableBoundsExcludeAllScreenInsets() {
        val bounds = DesktopWindowGeometry.usableBounds(
            screenBounds = Rectangle(100, 50, 1920, 1080),
            screenInsets = Insets(24, 8, 40, 12)
        )

        assertEquals(Rectangle(108, 74, 1900, 1016), bounds)
    }

    @Test
    fun startupWindowSizeIsCappedToWorkArea() {
        val size = DesktopWindowGeometry.initialSize(Rectangle(0, 0, 1366, 728))

        assertEquals(1200, size.width)
        assertEquals(655, size.height)
    }

    @Test
    fun startupWindowFitsSmallWorkArea() {
        val size = DesktopWindowGeometry.initialSize(Rectangle(0, 0, 800, 600))

        assertEquals(720, size.width)
        assertEquals(540, size.height)
    }
}
