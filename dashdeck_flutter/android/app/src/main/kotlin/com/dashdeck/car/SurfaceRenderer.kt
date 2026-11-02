package com.dashdeck.car

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the raw car-screen surface that navigation-category apps get, and repaints it on demand.
 * [visibleArea] excludes the host's action strip so tiles never sit underneath buttons.
 *
 * Painting happens on its own thread with the GPU, and back-to-back requests collapse into one
 * frame, so a stream of mirrored or video frames never delays taps (which arrive on the main thread).
 */
class SurfaceRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
    private val onTap: (x: Float, y: Float) -> Unit,
    private val drawFrame: (canvas: Canvas, area: Rect, density: Float) -> Unit,
) : SurfaceCallback {
    private val lock = Any()
    private val pending = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var density = 1f
    private var visibleArea: Rect? = null
    private var hardware = true

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                thread = HandlerThread("DashDeck render").apply { start() }
                handler = Handler(thread!!.looper)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@SurfaceRenderer)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                synchronized(lock) { surface = null }
                thread?.quitSafely()
                thread = null
                handler = null
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(lock) {
            surface = surfaceContainer.surface
            width = surfaceContainer.width
            height = surfaceContainer.height
            density = surfaceContainer.dpi.takeIf { it > 0 }?.div(160f) ?: 1f
        }
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        synchronized(lock) { this.visibleArea = visibleArea }
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // Waits for a frame in progress, so the host never frees the surface mid-draw.
        synchronized(lock) { surface = null }
    }

    override fun onClick(x: Float, y: Float) {
        Log.i("DashDeck", "Surface tap at $x,$y")
        onTap(x, y)
    }

    /** Asks for a repaint; safe from any thread, and cheap to call often. */
    fun render() {
        val handler = handler ?: return
        if (pending.compareAndSet(false, true)) handler.post(::paint)
    }

    private fun paint() {
        pending.set(false)
        synchronized(lock) {
            val target = surface?.takeIf { it.isValid } ?: return
            val area = visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, width, height)
            val canvas = lockCanvas(target) ?: return
            try {
                drawFrame(canvas, area, density)
            } finally {
                runCatching { target.unlockCanvasAndPost(canvas) }
            }
        }
    }

    /** GPU drawing scales mirrored frames far faster; falls back to software if the surface refuses. */
    private fun lockCanvas(target: Surface): Canvas? {
        if (hardware) {
            runCatching { return target.lockHardwareCanvas() }
                .onFailure { Log.w("DashDeck", "Hardware canvas unavailable, drawing in software", it) }
            hardware = false
        }
        return runCatching { target.lockCanvas(null) }.getOrNull()
    }
}
