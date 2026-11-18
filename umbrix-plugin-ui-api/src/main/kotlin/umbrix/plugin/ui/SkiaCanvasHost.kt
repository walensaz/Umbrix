package umbrix.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import umbrix.plugin.api.ui.DockViewContext
import umbrix.plugin.api.ui.SkiaCanvasDockView
import umbrix.plugin.api.ui.SkiaCanvasSurface

/**
 * Reusable Compose Canvas adapter for embedding native off-heap [SkiaCanvasDockView]s.
 *
 * Provides a clean bridge between Jetpack Compose Desktop canvas drawing and
 * high-performance direct Skia surface operations.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SkiaCanvasHost(
    view: SkiaCanvasDockView,
    context: DockViewContext,
    repaintTick: Int = 0,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .onPointerEvent(PointerEventType.Press) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val isSecondary = event.buttons.isSecondaryPressed
                view.onMouseClick(change.position.x, change.position.y, isSecondary, context)
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                view.onMouseMove(change.position.x, change.position.y, context)
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                view.onMouseScroll(
                    change.position.x,
                    change.position.y,
                    change.scrollDelta.x,
                    change.scrollDelta.y,
                    context
                )
            }
    ) {
        val observedRepaint = repaintTick // observe for repaints
        val canvasSurface = object : SkiaCanvasSurface {
            override fun widthPx(): Float = size.width
            override fun heightPx(): Float = size.height
            override fun density(): Float = density

            override fun drawRect(left: Float, top: Float, width: Float, height: Float, colorArgb: Int) {
                drawRect(
                    color = Color(colorArgb),
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )
            }

            override fun drawRoundedRect(
                left: Float,
                top: Float,
                width: Float,
                height: Float,
                cornerRadiusPx: Float,
                colorArgb: Int
            ) {
                drawRoundRect(
                    color = Color(colorArgb),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            }

            override fun drawLine(
                startX: Float,
                startY: Float,
                endX: Float,
                endY: Float,
                strokeWidthPx: Float,
                colorArgb: Int
            ) {
                drawLine(
                    color = Color(colorArgb),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = strokeWidthPx
                )
            }

            override fun drawCircle(centerX: Float, centerY: Float, radiusPx: Float, colorArgb: Int) {
                drawCircle(
                    color = Color(colorArgb),
                    radius = radiusPx,
                    center = Offset(centerX, centerY)
                )
            }

            override fun drawText(
                text: String,
                x: Float,
                y: Float,
                fontSizeSp: Float,
                colorArgb: Int,
                monospace: Boolean
            ) {
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val paint = Paint().apply {
                    color = colorArgb
                    isAntiAlias = true
                }
                val font = Font().apply {
                    size = fontSizeSp * density
                }
                nativeCanvas.drawString(text, x, y, font, paint)
            }

            override fun clipRect(left: Float, top: Float, width: Float, height: Float, drawBlock: Runnable) {
                clipRect(left, top, left + width, top + height) {
                    drawBlock.run()
                }
            }
        }

        view.renderCanvas(canvasSurface, context)
    }
}
