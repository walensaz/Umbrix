package umbrix.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportHitTestFallbackTest {

    @Test
    fun fallbackCharOffsetMapsProportionallyWithPositiveInputs() {
        assertEquals(0, fallbackCharOffset(0f, 8f))
        assertEquals(10, fallbackCharOffset(80f, 8f))
        assertEquals(25, fallbackCharOffset(200f, 8f))
        assertEquals(1, fallbackCharOffset(11.9f, 8f))
    }

    @Test
    fun fallbackCharOffsetClampsNegativeAndZeroWidthGracefully() {
        assertEquals(0, fallbackCharOffset(-50f, 8f))
        assertEquals(0, fallbackCharOffset(100f, 0f))
        assertEquals(0, fallbackCharOffset(100f, -5f))
        assertEquals(0, fallbackCharOffset(Float.NaN, 8f))
        assertEquals(0, fallbackCharOffset(100f, Float.NaN))
    }

    @Test
    fun fallbackCharOffsetRespectsMaxShapedCharsCap() {
        val maxChars = ViewportState.MAX_SHAPED_CHARS
        assertEquals(maxChars, fallbackCharOffset(100_000f, 8f, maxChars))
        assertEquals(100, fallbackCharOffset(2_000f, 8f, 100))
    }

    @Test
    fun computeFallbackOffsetUsesLocalXWhenCoordinatesAreAttached() {
        val offset = computeFallbackOffset(
            pointerX = 500f,
            viewportWidthPx = 800f,
            scrollX = 200,
            localX = 80f,
            dynamicCharWidthPx = 8f
        )
        assertEquals(10, offset)
    }

    @Test
    fun computeFallbackOffsetClampsToViewportWhenCoordinatesAreMissing() {
        // Within viewport with horizontal scroll
        val offsetInViewport = computeFallbackOffset(
            pointerX = 160f,
            viewportWidthPx = 800f,
            scrollX = 40,
            localX = null,
            dynamicCharWidthPx = 8f
        )
        assertEquals(25, offsetInViewport)

        // Pointer dragged to the left beyond viewport (negative pointerX)
        val offsetLeftClamp = computeFallbackOffset(
            pointerX = -100f,
            viewportWidthPx = 800f,
            scrollX = 40,
            localX = null,
            dynamicCharWidthPx = 8f
        )
        // Clamped pointerX = 0, + scrollX 40 = 40 -> 40 / 8 = 5
        assertEquals(5, offsetLeftClamp)

        // Pointer dragged to the right beyond viewport
        val offsetRightClamp = computeFallbackOffset(
            pointerX = 1200f,
            viewportWidthPx = 800f,
            scrollX = 0,
            localX = null,
            dynamicCharWidthPx = 8f
        )
        // Clamped pointerX = 799 -> 799 / 8 = 99
        assertEquals(99, offsetRightClamp)
    }

    @Test
    fun computeFallbackOffsetNeverThrowsOnExtremeOrNanValues() {
        assertEquals(0, computeFallbackOffset(Float.NaN, 800f, 0, null, 8f))
        assertEquals(0, computeFallbackOffset(100f, Float.NaN, 0, null, 8f))
        assertEquals(0, computeFallbackOffset(100f, 800f, -100, null, 0f))
        assertEquals(99, computeFallbackOffset(Float.POSITIVE_INFINITY, 800f, 0, null, 8f))
        assertEquals(0, computeFallbackOffset(Float.NEGATIVE_INFINITY, 800f, 0, null, 8f))
    }

    @Test
    fun resolveRowByTopsHandlesDynamicHeightIntervals() {
        // Row 0 = 100px, Row 1 = 40px, Row 2 = 20px -> tops 0, 100, 140
        val tops = floatArrayOf(0f, 100f, 140f)
        assertEquals(0, resolveRowByTops(60f, 3) { tops[it] })
        assertEquals(0, resolveRowByTops(99.9f, 3) { tops[it] })
        assertEquals(1, resolveRowByTops(100f, 3) { tops[it] })
        assertEquals(1, resolveRowByTops(139f, 3) { tops[it] })
        assertEquals(2, resolveRowByTops(140f, 3) { tops[it] })
    }

    @Test
    fun resolveRowByTopsClampsOutOfBoundsPointer() {
        val tops = floatArrayOf(0f, 100f, 140f)
        assertEquals(0, resolveRowByTops(-50f, 3) { tops[it] })
        assertEquals(2, resolveRowByTops(5_000f, 3) { tops[it] })
    }

    @Test
    fun resolveRowByTopsSkipsUnmeasuredRowsAndReportsNoneWhenEmpty() {
        val tops = floatArrayOf(Float.NaN, 40f, Float.NaN, 90f)
        assertEquals(1, resolveRowByTops(10f, 4) { tops[it] })
        assertEquals(1, resolveRowByTops(80f, 4) { tops[it] })
        assertEquals(3, resolveRowByTops(95f, 4) { tops[it] })
        assertEquals(-1, resolveRowByTops(10f, 2) { Float.NaN })
        assertEquals(-1, resolveRowByTops(Float.NaN, 2) { 0f })
    }

    @Test
    fun resolveRowAtPointerFallsBackToFixedHeightWhenCoordinatesAreNull() {
        assertEquals(3, resolveRowAtPointer(70f, 0f, 20f, 10, emptyMap(), null))
        assertEquals(3, resolveRowAtPointer(50f, 10f, 20f, 10, emptyMap(), null))
        assertEquals(0, resolveRowAtPointer(-30f, 0f, 20f, 10, emptyMap(), null))
        assertEquals(9, resolveRowAtPointer(9_999f, 0f, 20f, 10, emptyMap(), null))
        assertEquals(0, resolveRowAtPointer(50f, 0f, 20f, 0, emptyMap(), null))
        assertEquals(0, resolveRowAtPointer(Float.NaN, 0f, 20f, 10, emptyMap(), null))
    }
}
