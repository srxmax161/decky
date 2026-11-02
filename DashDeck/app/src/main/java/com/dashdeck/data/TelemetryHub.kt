package com.dashdeck.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

/**
 * Collects phone-side telemetry while anyone (car session or phone editor) is watching.
 * Must be used from the main thread.
 */
class TelemetryHub(context: Context) {
    private val _state = MutableStateFlow(Telemetry())
    val state: StateFlow<Telemetry> = _state.asStateFlow()

    private val location = LocationSource(context, this)
    private val battery = BatterySource(context, this)
    private var users = 0

    fun acquire() {
        if (users++ == 0) {
            location.start()
            battery.start()
        }
    }

    fun release() {
        if (users > 0 && --users == 0) {
            location.stop()
            battery.stop()
        }
    }

    fun onPermissionsChanged() {
        if (users > 0) {
            location.stop()
            location.start()
        }
    }

    fun update(block: (Telemetry) -> Telemetry) = _state.update { old ->
        val next = block(old)
        next.copy(maxSpeedMps = max(next.maxSpeedMps, next.carSpeedMps ?: next.gpsSpeedMps ?: 0f))
    }

    fun resetTrip() = _state.update {
        it.copy(tripStartMillis = System.currentTimeMillis(), tripDistanceM = 0.0, maxSpeedMps = 0f)
    }
}
