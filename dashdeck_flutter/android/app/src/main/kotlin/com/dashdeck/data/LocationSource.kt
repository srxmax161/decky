package com.dashdeck.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat

/** Position, speed, heading, altitude and trip distance from the phone's location services. */
class LocationSource(private val context: Context, private val hub: TelemetryHub) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListenerCompat(::onLocation)
    private var lastTripPoint: Location? = null
    private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || manager == null || !Permissions.hasLocation(context)) return
        val request = LocationRequestCompat.Builder(1_000L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(500L)
            .build()
        // Fused blends satellites with Wi-Fi and cell towers, so there's a position indoors and under
        // cover too; GPS alone can go minutes without one.
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        runCatching {
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, listener, Looper.getMainLooper())
            running = true
        }
        showLastKnown()
    }

    /** Puts the map somewhere sensible straight away, before the first fresh fix arrives. */
    @SuppressLint("MissingPermission")
    private fun showLastKnown() {
        val last = manager?.allProviders.orEmpty()
            .mapNotNull { runCatching { manager?.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        hub.update { t ->
            if (t.latitude != null) t else t.copy(latitude = last.latitude, longitude = last.longitude)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        manager?.let { LocationManagerCompat.removeUpdates(it, listener) }
        running = false
        lastTripPoint = null
    }

    private fun onLocation(location: Location) {
        val moving = location.hasSpeed() && location.speed > MIN_MOVING_SPEED_MPS
        val accurate = location.accuracy <= MAX_TRIP_ACCURACY_M
        val previous = lastTripPoint
        val step = if (previous != null && moving && accurate) previous.distanceTo(location).toDouble() else 0.0
        if (accurate) lastTripPoint = location

        hub.update { t ->
            t.copy(
                gpsSpeedMps = if (location.hasSpeed()) location.speed else t.gpsSpeedMps,
                // Bearing is noise when crawling, so hold the last good heading.
                headingDeg = if (location.hasBearing() && moving) location.bearing else t.headingDeg,
                altitudeM = if (location.hasAltitude()) location.altitude else t.altitudeM,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = location.accuracy,
                lastFixMillis = System.currentTimeMillis(),
                tripDistanceM = t.tripDistanceM + if (t.tripStartMillis != null) step else 0.0,
            )
        }
        hub.route.onFix(location)
    }

    private companion object {
        const val MIN_MOVING_SPEED_MPS = 1.0f
        const val MAX_TRIP_ACCURACY_M = 30f
    }
}
