package com.dashdeck.car

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the raw car-screen surface that navigation-category apps get, and repaints it on demand.
 * [visibleArea] excludes the host's action strip so tiles never sit underneath buttons.
 */
class SurfaceRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
    private val onTap: () -> Unit,
    private val drawFrame: (canvas: Canvas, area: Rect, density: Float) -> Unit,
) : SurfaceCallback {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var density = 1f
    private var visibleArea: Rect? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@SurfaceRenderer)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                surface = null
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        density = surfaceContainer.dpi.takeIf { it > 0 }?.div(160f) ?: 1f
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onClick(x: Float, y: Float) = onTap()

    fun render() {
        val target = surface?.takeIf { it.isValid } ?: return
        val area = visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, width, height)
        val canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return
        try {
            drawFrame(canvas, area, density)
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }
}
