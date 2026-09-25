package umbrix.ui.viewport

import kotlin.math.max
import kotlin.math.min

/** Frame-sized selection scrolling math; vertical positions remain 64-bit document coordinates. */
internal object SelectionEdgeScroll {
    private const val EDGE_ZONE_PX = 48f
    private const val MAX_SPEED_PX_PER_SECOND = 720f

    fun edgeVelocity(positionPx: Float, extentPx: Float): Float {
        if (extentPx <= 0f) return 0f
        val zone = min(EDGE_ZONE_PX, extentPx / 2f)
        return when {
            positionPx < zone -> -MAX_SPEED_PX_PER_SECOND * (1f - positionPx.coerceIn(0f, zone) / zone)
            positionPx > extentPx - zone -> MAX_SPEED_PX_PER_SECOND * (1f - (extentPx - positionPx).coerceIn(0f, zone) / zone)
            else -> 0f
        }
    }

    fun clampHorizontal(current: Float, deltaPx: Float, maxScroll: Int): Float =
        (current.toDouble() + deltaPx.toDouble()).coerceIn(0.0, maxScroll.coerceAtLeast(0).toDouble()).toFloat()

    fun virtualRowAt(top: Long, rowOffset: Int, totalLines: Long): Long =
        if (top >= totalLines || rowOffset.toLong() >= totalLines - top) totalLines else top + rowOffset

    fun advanceVertical(
        top: Long,
        subLinePx: Float,
        deltaPx: Float,
        rowHeightPx: Float,
        totalLines: Long,
        visibleRows: Int
    ): Pair<Long, Float> {
        if (rowHeightPx <= 0f) return top to subLinePx
        val maxTop = max(0L, totalLines - visibleRows.toLong())
        var nextTop = top.coerceIn(0L, maxTop)
        var nextSubLine = subLinePx + deltaPx
        while (nextSubLine >= rowHeightPx && nextTop < maxTop) {
            nextSubLine -= rowHeightPx
            nextTop++
        }
        while (nextSubLine < 0f && nextTop > 0L) {
            nextSubLine += rowHeightPx
            nextTop--
        }
        return nextTop to if (nextTop == 0L && nextSubLine < 0f || nextTop == maxTop && nextSubLine > 0f) {
            0f
        } else {
            nextSubLine
        }
    }
}
