package com.dashdeck.car

import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dashdeck.R
import com.dashdeck.dashDeck
import com.dashdeck.render.DashboardRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The car's driver display. Android Auto hands it to whichever navigation app is open, so while
 * DashDeck is in front this draws its map there instead of leaving it blank.
 */
class ClusterSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Log.i("DashDeck", "Driver display: given to DashDeck")
                carContext.dashDeck.telemetry.acquire()
            }

            override fun onStart(owner: LifecycleOwner) {
                Log.i("DashDeck", "Driver display: showing DashDeck")
            }

            override fun onStop(owner: LifecycleOwner) {
                Log.i("DashDeck", "Driver display: hidden (another app took it?)")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i("DashDeck", "Driver display: taken back from DashDeck")
                carContext.dashDeck.telemetry.release()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = ClusterScreen(carContext)
}

private class ClusterScreen(carContext: CarContext) : Screen(carContext) {
    private val app = carContext.dashDeck
    private val renderer = DashboardRenderer(carContext).apply { is24Hour = DateFormat.is24HourFormat(carContext) }

    private val surface = SurfaceRenderer(carContext, lifecycle, onTap = { _, _ -> }) { canvas, area, density ->
        renderer.drawCluster(
            canvas, area, density, app.configRepository.config.value,
            app.telemetry.state.value, System.currentTimeMillis(),
        )
    }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.telemetry.state.collect { surface.render() } }
                launch {
                    // Map tiles arrive in the background; pick them up even when parked.
                    while (true) {
                        delay(1_000L)
                        surface.render()
                    }
                }
            }
        }
    }

    // The driver display shows no buttons, but this template insists on an action strip.
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_deck)).build())
                        .setOnClickListener {}
                        .build(),
                )
                .build(),
        )
        .build()
}
