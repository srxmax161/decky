package com.dashdeck.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dashdeck.dashDeck
import com.dashdeck.data.Permissions

class DashDeckSession : Session() {
    private var hardware: CarHardwareSource? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val app = carContext.dashDeck
                app.telemetry.acquire()
                hardware = CarHardwareSource(carContext, app.telemetry).also { it.start() }

                val missing = Permissions.missing(carContext)
                if (missing.isNotEmpty()) {
                    // Shows the system prompt on the phone; the dashboard keeps running meanwhile.
                    carContext.requestPermissions(missing) { _, _ ->
                        app.telemetry.onPermissionsChanged()
                        hardware?.restart()
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                hardware?.stop()
                hardware = null
                carContext.dashDeck.telemetry.release()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = DashboardScreen(carContext)
}
