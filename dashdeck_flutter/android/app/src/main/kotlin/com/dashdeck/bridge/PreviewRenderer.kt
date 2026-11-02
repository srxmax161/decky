package com.dashdeck.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import com.dashdeck.dashDeck
import com.dashdeck.render.DashboardRenderer
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Draws the phone preview with the same [DashboardRenderer] the car uses and hands Flutter a PNG,
 * so the preview can never drift from what the car shows. Encoding runs off the UI thread.
 */
class PreviewRenderer(private val context: Context) {
    private val renderer = DashboardRenderer(context).apply { liveMirror = false }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null

    fun render(pageIndex: Int, width: Int, height: Int, highlightTileId: String?, result: MethodChannel.Result) {
        val app = context.dashDeck
        val config = app.configRepository.config.value
        val telemetry = app.telemetry.state.value
        val is24Hour = DateFormat.is24HourFormat(context)
        executor.execute {
            val png = runCatching {
                val w = width.coerceIn(100, MAX_WIDTH)
                val h = (height.toLong() * w / width.coerceAtLeast(1)).toInt().coerceAtLeast(50)
                val target = bitmap?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
                renderer.is24Hour = is24Hour
                renderer.draw(
                    Canvas(target), Rect(0, 0, w, h), w / CAR_WIDTH_DP,
                    config, pageIndex, telemetry, System.currentTimeMillis(), highlightTileId,
                )
                ByteArrayOutputStream().use { out ->
                    target.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }
            main.post {
                png.onSuccess(result::success).onFailure { result.error("render_failed", it.message, null) }
            }
        }
    }

    fun close() = executor.shutdown()

    private companion object {
        /** Width of a typical car display in dp; scales the preview so proportions match the car. */
        const val CAR_WIDTH_DP = 800f
        const val MAX_WIDTH = 1200
    }
}
