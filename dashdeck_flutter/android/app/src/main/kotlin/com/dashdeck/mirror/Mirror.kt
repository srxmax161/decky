package com.dashdeck.mirror

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copies the phone's screen into bitmaps the car renderer can draw, using the screen-casting
 * permission the user grants in the phone app. Frames arrive on a background thread; [draw] is safe
 * to call from any thread.
 */
object Mirror {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Bumped for every new frame so the car screen knows to repaint. */
    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames.asStateFlow()

    private val handler by lazy { Handler(HandlerThread("DashDeck mirror").apply { start() }.looper) }
    private val grabber by lazy { FrameGrabber("Mirror", handler) { _frames.value++ } }
    private var appContext: Context? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    fun start(context: Context, projection: MediaProjection) {
        stop()
        appContext = context.applicationContext
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stop() }
            }

            // Rotating the phone or switching apps changes the shape of what's captured.
            override fun onCapturedContentResize(width: Int, height: Int) {
                handler.post { resize(width, height) }
            }
        }, handler)

        val size = phoneScreenSize(context)
        val (w, h) = scaled(size.x, size.y)
        display = projection.createVirtualDisplay(
            "DashDeck mirror", w, h, DPI, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            grabber.surface(w, h), null, handler,
        )
        // Turning the phone sideways for a game changes the screen's shape; follow it.
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(rotationListener, handler)
        _running.value = true
        Log.i("DashDeck", "Mirroring started at ${w}x$h")
    }

    fun stop() {
        val context = appContext
        val wasRunning = projection != null
        context?.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(rotationListener)
        display?.release()
        display = null
        grabber.close()
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        _running.value = false
        if (wasRunning && context != null) {
            context.stopService(Intent(context, MirrorService::class.java))
            Log.i("DashDeck", "Mirroring stopped")
        }
    }

    /**
     * Draws the latest frame as large as fits in [bounds], centred. Returns where it landed, or null
     * when there's no frame yet.
     */
    fun draw(canvas: Canvas, bounds: RectF): RectF? = grabber.draw(canvas, bounds)

    /** Where a point in the mirrored frame (0–1 on each axis) is on the phone's screen right now. */
    fun toPhone(context: Context, nx: Float, ny: Float): Point {
        val size = phoneScreenSize(context)
        return Point((nx * size.x).roundToInt(), (ny * size.y).roundToInt())
    }

    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            val context = appContext ?: return
            if (displayId != Display.DEFAULT_DISPLAY) return
            val size = phoneScreenSize(context)
            resize(size.x, size.y)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private fun resize(width: Int, height: Int) {
        val display = display ?: return
        if (width <= 0 || height <= 0) return
        val (w, h) = scaled(width, height)
        if (grabber.width == w && grabber.height == h) return
        display.resize(w, h, DPI)
        display.surface = grabber.surface(w, h)
        Log.i("DashDeck", "Mirror resized to ${w}x$h")
    }

    /** Fits the capture within [MAX_SIDE] pixels: plenty for a car screen, and cheap to copy. */
    private fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(width, height))
        // Even sizes keep some GPUs' buffer allocation happy.
        return ((width * scale).roundToInt() and 1.inv()).coerceAtLeast(2) to
            ((height * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
    }

    private fun phoneScreenSize(context: Context): Point {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        return Point().also { display.getRealSize(it) }
    }

    private const val MAX_SIDE = 1280
    private const val DPI = 320
}
