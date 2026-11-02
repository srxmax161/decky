package com.dashdeck.phone

import android.graphics.Rect
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.dashdeck.data.Telemetry
import com.dashdeck.model.DashConfig
import com.dashdeck.render.DashboardRenderer
import kotlinx.coroutines.delay

/** Width of a typical car display in dp; the preview is scaled so proportions match the car. */
private const val CAR_WIDTH_DP = 800f

@Composable
fun DashboardPreview(config: DashConfig, pageIndex: Int, telemetry: Telemetry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember { DashboardRenderer() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            now = System.currentTimeMillis()
        }
    }
    renderer.is24Hour = DateFormat.is24HourFormat(context)

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            renderer.draw(
                canvas.nativeCanvas,
                Rect(0, 0, size.width.toInt(), size.height.toInt()),
                size.width / CAR_WIDTH_DP,
                config, pageIndex, telemetry, now,
            )
        }
    }
}
