package com.dashdeck.car

import android.content.Intent
import android.util.Log
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dashdeck.dashDeck
import com.dashdeck.data.Permissions
import com.dashdeck.video.VideoBrowser

class DashDeckSession : Session() {
    private var hardware: CarHardwareSource? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val app = carContext.dashDeck
                app.telemetry.acquire()
                app.telemetry.onCarConnected()
                hardware = CarHardwareSource(carContext, app.telemetry).also { it.start() }

                // Don't ask from the car: Android 16 refuses to open the permission prompt on the car
                // display and the app crashes. The phone app asks instead.
                if (Permissions.missing(carContext).isNotEmpty()) {
                    CarToast.makeText(
                        carContext, "Open DashDeck on your phone to allow location and car data", CarToast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                VideoBrowser.close()
                hardware?.stop()
                hardware = null
                carContext.dashDeck.telemetry.onCarDisconnected()
                carContext.dashDeck.telemetry.release()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = DashboardScreen(carContext)

    override fun onNewIntent(intent: Intent) {
        Log.i("DashDeck", "Session got $intent ${intent.data}")
    }
}
