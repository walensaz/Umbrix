package umbrix.ui.icon

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * High-performance, multi-resolution application icon manager for Umbrix Workstation.
 *
 * Employs the official brushed titanium dual-tone (dark indigo to glowing mint) Umbrix
 * monogram badge across all operating system resolutions (16x16 to 1024x1024) for the application window,
 * OS taskbar, Alt+Tab switcher, and dock.
 */
object UmbrixIcon {

    private val iconCache = ConcurrentHashMap<Int, BufferedImage>()

    private fun loadResourceIcon(name: String): BufferedImage? {
        return try {
            UmbrixIcon::class.java.getResourceAsStream("/icons/$name")?.use { stream ->
                ImageIO.read(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private val masterIcon: BufferedImage? by lazy {
        loadResourceIcon("umbrix_512x512_transparent.png")
            ?: loadResourceIcon("umbrix_icon.png")
    }

    /**
     * Resolves a crisp, anti-aliased icon image at the specified pixel dimensions.
     * Directly returns pre-rendered, hand-optimized icon assets when available (16, 24, 32, 48, 64, 96, 128, 256, 512),
     * or performs progressive multi-step downsampling from the nearest higher resolution.
     */
    fun createAwtImage(size: Int): BufferedImage {
        return iconCache.computeIfAbsent(size) { s ->
            // 1. Direct exact pre-rendered asset match
            loadResourceIcon("umbrix_${s}x${s}_transparent.png")
                ?: run {
                    // 2. High-quality downsampling from the nearest larger pre-rendered asset
                    val base = when {
                        s <= 20 -> loadResourceIcon("umbrix_24x24_transparent.png")
                            ?: loadResourceIcon("umbrix_48x48_transparent.png")
                        s in 21..30 -> loadResourceIcon("umbrix_32x32_transparent.png")
                        s in 33..45 -> loadResourceIcon("umbrix_48x48_transparent.png")
                        s in 49..60 -> loadResourceIcon("umbrix_64x64_transparent.png")
                        s in 65..90 -> loadResourceIcon("umbrix_96x96_transparent.png")
                        s in 97..120 -> loadResourceIcon("umbrix_128x128_transparent.png")
                        s in 129..240 -> loadResourceIcon("umbrix_256x256_transparent.png")
                        else -> masterIcon
                    } ?: masterIcon

                    if (base != null) {
                        progressiveDownsample(base, s, s)
                    } else {
                        generateProceduralFallback(s)
                    }
                }
        }
    }

    private fun progressiveDownsample(src: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        var current = src
        var w = src.width
        var h = src.height

        // Progressive step-down (halving) to prevent high-frequency aliasing on steep downscales
        while (w > targetWidth * 2 || h > targetHeight * 2) {
            w = maxOf(targetWidth, w / 2)
            h = maxOf(targetHeight, h / 2)
            val step = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = step.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(current, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
            current = step
        }

        // Final scaling to exact target dimensions with high-quality bicubic filtering
        val finalImg = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val g = finalImg.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(current, 0, 0, targetWidth, targetHeight, null)
        } finally {
            g.dispose()
        }
        return finalImg
    }

    private fun generateProceduralFallback(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val cornerRadius = size * 0.22f
            val bgRect = RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), cornerRadius, cornerRadius)

            // Deep obsidian charcoal background
            val bgGradient = GradientPaint(0f, 0f, Color(25, 27, 34), size.toFloat(), size.toFloat(), Color(14, 15, 19))
            g2d.paint = bgGradient
            g2d.fill(bgRect)

            // Titanium border
            val borderGradient = GradientPaint(0f, 0f, Color(75, 82, 98, 190), 0f, size.toFloat(), Color(25, 28, 35, 120))
            g2d.paint = borderGradient
            g2d.stroke = BasicStroke(maxOf(1f, size * 0.025f))
            g2d.draw(bgRect)

            // Dual-tone monogram U: left arm indigo, right arm mint
            val strokeWidth = size * 0.16f
            val leftX = size * 0.28f
            val rightX = size * 0.72f
            val topY = size * 0.22f
            val arcTopY = size * 0.48f
            val bottomY = size * 0.76f

            // Left arm (dark indigo)
            val leftPath = java.awt.geom.Path2D.Float()
            leftPath.moveTo(leftX, topY)
            leftPath.lineTo(leftX, arcTopY)
            leftPath.curveTo(leftX, bottomY, size * 0.5f, bottomY + (size * 0.02f), size * 0.5f, bottomY)
            g2d.paint = GradientPaint(leftX, topY, Color(52, 56, 82), size * 0.5f, bottomY, Color(34, 37, 56))
            g2d.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2d.draw(leftPath)

            // Right arm (glowing mint/cyan)
            val rightPath = java.awt.geom.Path2D.Float()
            rightPath.moveTo(size * 0.5f, bottomY)
            rightPath.curveTo(size * 0.5f, bottomY + (size * 0.02f), rightX, bottomY, rightX, arcTopY)
            rightPath.lineTo(rightX, topY)
            g2d.paint = GradientPaint(size * 0.5f, bottomY, Color(66, 200, 183), rightX, topY, Color(126, 232, 214))
            g2d.draw(rightPath)
        } finally {
            g2d.dispose()
        }
        return image
    }

    /**
     * Standard multi-resolution icon ladder for Windows, macOS, and Linux window managers.
     */
    val iconImages: List<BufferedImage> by lazy {
        listOf(16, 20, 24, 32, 48, 64, 96, 128, 256, 512).map { createAwtImage(it) }
    }

    /**
     * Compose Desktop Painter representation for the application window and large surfaces.
     */
    val painter: Painter by lazy {
        BitmapPainter(createAwtImage(256).toComposeImageBitmap(), filterQuality = FilterQuality.High)
    }

    /**
     * Dedicated crisp title bar icon painter (scaled for 20dp at up to 2.5x HiDPI).
     */
    val titleBarPainter: Painter by lazy {
        BitmapPainter(createAwtImage(48).toComposeImageBitmap(), filterQuality = FilterQuality.High)
    }

    /**
     * Installs the multi-resolution Umbrix icons onto a native AWT Window and OS Taskbar.
     */
    fun applyToWindow(window: java.awt.Window) {
        try {
            window.iconImages = iconImages
        } catch (_: Throwable) {}

        try {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    val taskbarImg = loadResourceIcon("umbrix_taskbar_48x48.png")
                        ?: createAwtImage(256)
                    taskbar.iconImage = taskbarImg
                }
            }
        } catch (_: Throwable) {}
    }
}
