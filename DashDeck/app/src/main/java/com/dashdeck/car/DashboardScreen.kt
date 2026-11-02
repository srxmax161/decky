package com.dashdeck.car

import android.text.format.DateFormat
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dashdeck.R
import com.dashdeck.dashDeck
import com.dashdeck.render.DashboardRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DashboardScreen(carContext: CarContext) : Screen(carContext) {
    private val app = carContext.dashDeck
    private val renderer = DashboardRenderer()
    private var pageIndex = 0

    private val surface = SurfaceRenderer(carContext, lifecycle, onTap = ::nextPage) { canvas, area, density ->
        renderer.is24Hour = DateFormat.is24HourFormat(carContext)
        renderer.draw(
            canvas, area, density,
            app.configRepository.config.value, pageIndex,
            app.telemetry.state.value, System.currentTimeMillis(),
        )
    }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.configRepository.config.collect { config ->
                        pageIndex = pageIndex.coerceIn(0, config.pages.lastIndex)
                        invalidate()
                        surface.render()
                    }
                }
                launch { app.telemetry.state.collect { surface.render() } }
                launch {
                    // Keeps the clock and trip timer ticking even when no sensor data arrives.
                    while (true) {
                        delay(1_000L - System.currentTimeMillis() % 1_000L)
                        surface.render()
                    }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val config = app.configRepository.config.value
        val page = config.pages[pageIndex.coerceIn(0, config.pages.lastIndex)]

        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(page.name.ifBlank { "Page ${pageIndex + 1}" })
                    .setOnClickListener(::nextPage)
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_deck))
                    .setOnClickListener { screenManager.push(ShortcutsScreen(carContext)) }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_reset))
                    .setOnClickListener {
                        app.telemetry.resetTrip()
                        CarToast.makeText(carContext, "Trip reset", CarToast.LENGTH_SHORT).show()
                    }
                    .build(),
            )
            .build()

        return NavigationTemplate.Builder().setActionStrip(actions).build()
    }

    private fun nextPage() {
        val count = app.configRepository.config.value.pages.size
        if (count <= 1) return
        pageIndex = (pageIndex + 1) % count
        invalidate()
        surface.render()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
