package umbrix.plugin.api.ui

import umbrix.plugin.api.{PluginSessionView, PluginWorkspaceController}

/**
 * Execution context provided to a [[CustomDockView]] by the host desktop application.
 *
 * Exposes methods to query active session state, navigate records/timestamps,
 * run search queries, and request asynchronous canvas repaints.
 */
trait DockViewContext {
  def activeSession: PluginSessionView
  def workspace: PluginWorkspaceController
  def selectedRecordIndex: Long
  def topVisibleRecordIndex: Long
  def navigateToRecord(recordIndex: Long): Unit
  def navigateToTimestampMicros(epochMicros: Long): Unit
  def executeSearchQuery(queryText: String): Unit
  def requestRepaint(): Unit
}

/**
 * Hardware-accelerated Skia 2D rendering surface exposed to [[SkiaCanvasDockView]].
 *
 * Provides high-performance primitive drawing routines (rectangles, lines, text, clipping)
 * that execute directly against GPU-backed Skia contexts without JVM heap thrashing.
 */
trait SkiaCanvasSurface {
  def widthPx: Float
  def heightPx: Float
  def density: Float

  def drawRect(left: Float, top: Float, width: Float, height: Float, colorArgb: Int): Unit
  def drawRoundedRect(left: Float, top: Float, width: Float, height: Float, cornerRadiusPx: Float, colorArgb: Int): Unit
  def drawLine(startX: Float, startY: Float, endX: Float, endY: Float, strokeWidthPx: Float, colorArgb: Int): Unit
  def drawCircle(centerX: Float, centerY: Float, radiusPx: Float, colorArgb: Int): Unit
  def drawText(text: String, x: Float, y: Float, fontSizeSp: Float, colorArgb: Int, monospace: Boolean = true): Unit
  def clipRect(left: Float, top: Float, width: Float, height: Float)(drawBlock: Runnable): Unit
}

/**
 * Base contract for custom pluggable dock views hosted in the Umbrix UI grid.
 *
 * Lifecycle methods notify the view when attached to a window, when the active log session
 * changes, and when the view is closed/detached.
 */
trait CustomDockView extends AutoCloseable {
  def viewId: String
  def title: String
  def onAttach(context: DockViewContext): Unit = {}
  def onSessionChanged(newSession: PluginSessionView): Unit = {}
  def close(): Unit = {}
}

/**
 * Custom dock view rendering directly via Skia canvas surface primitives.
 *
 * Receives user input events (clicks, movement, scrolling) and repaints on demand.
 */
trait SkiaCanvasDockView extends CustomDockView {
  def renderCanvas(surface: SkiaCanvasSurface, context: DockViewContext): Unit
  def onMouseClick(xPx: Float, yPx: Float, isSecondaryButton: Boolean, context: DockViewContext): Unit = {}
  def onMouseMove(xPx: Float, yPx: Float, context: DockViewContext): Unit = {}
  def onMouseScroll(xPx: Float, yPx: Float, deltaX: Float, deltaY: Float, context: DockViewContext): Unit = {}
}

/**
 * Factory service descriptor for instantiating custom dock views on demand.
 *
 * Registered with the host environment during plugin initialization.
 */
trait DockViewFactory {
  def viewId: String
  def displayName: String
  def defaultDropZone: String = "RIGHT"
  def createView(initialSession: PluginSessionView): CustomDockView
}
