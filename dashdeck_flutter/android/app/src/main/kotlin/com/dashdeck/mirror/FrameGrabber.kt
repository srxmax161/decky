package com.dashdeck.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * Turns whatever a virtual display shows into bitmaps the car renderer can draw. Frames arrive on
 * [handler]'s thread; [draw] is safe from any thread. Used for both phone mirroring and the video page.
 */
class FrameGrabber(private val name: String, private val handler: Handler, private val onFrame: () -> Unit) {
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var reader: ImageReader? = null

    // Double-buffered so the car never draws a half-copied frame.
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var frameWidth = 0
    private var frameHeight = 0

    val width get() = reader?.width ?: 0
    val height get() = reader?.height ?: 0

    /**
     * A fresh surface of this size for the virtual display to draw into. The old one closes a moment
     * later, once the display has switched over.
     */
    fun surface(width: Int, height: Int): Surface {
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener(::onImage, handler)
        reader?.let { old -> handler.post { old.close() } }
        reader = newReader
        return newReader.surface
    }

    fun close() {
        reader?.close()
        reader = null
        synchronized(lock) {
            front = null
            back = null
        }
    }

    val hasFrame: Boolean get() = synchronized(lock) { front != null }

    /**
     * Draws the latest frame (or just [crop] of it, in frame pixels) as large as fits in [bounds],
     * centred. Returns where it landed, or null when there's no frame yet.
     */
    fun draw(canvas: Canvas, bounds: RectF, crop: Rect? = null, fill: Boolean = false): RectF? = synchronized(lock) {
        val frame = front ?: return null
        val src = crop?.takeIf { it.width() > 0 && it.height() > 0 && it.right <= frameWidth && it.bottom <= frameHeight }
            ?: Rect(0, 0, frameWidth, frameHeight)
        val sx = bounds.width() / src.width()
        val sy = bounds.height() / src.height()
        val scale = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        val w = src.width() * scale
        val h = src.height() * scale
        val dst = RectF(bounds.centerX() - w / 2, bounds.centerY() - h / 2, bounds.centerX() + w / 2, bounds.centerY() + h / 2)
        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(frame, src, dst, paint)
        canvas.restore()
        dst
    }

    private fun onImage(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            // Rows can be padded; copy whole rows into a wider bitmap and only draw the real width.
            val rowPixels = plane.rowStride / plane.pixelStride
            val target = back?.takeIf { it.width == rowPixels && it.height == image.height }
                ?: Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            target.copyPixelsFromBuffer(plane.buffer)
            if (image.width != frameWidth || image.height != frameHeight) {
                Log.i("DashDeck", "$name frames are ${image.width}x${image.height}")
            }
            synchronized(lock) {
                back = front
                front = target
                frameWidth = image.width
                frameHeight = image.height
            }
        } finally {
            image.close()
        }
        onFrame()
    }
}
