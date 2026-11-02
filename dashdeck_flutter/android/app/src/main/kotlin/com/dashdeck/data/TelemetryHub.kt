package com.dashdeck.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val location = LocationSource(context, this)
    private val battery = BatterySource(context, this)
    private val lyrics = LyricsRepository(this, scope, context.filesDir)
    val media = MediaSource(context, this, lyrics::onTrack)
    val route = RouteGuide(context, this, scope)
    private var users = 0

    fun acquire() {
        if (users++ == 0) {
            location.start()
            battery.start()
            media.start()
        }
    }

    fun release() {
        if (users > 0 && --users == 0) {
            location.stop()
            battery.stop()
            media.stop()
        }
    }

    /** Call after the user may have changed notification access in Settings. */
    fun onMediaAccessChanged() {
        if (media.hasAccess() != _state.value.mediaAccess) media.restart()
    }

    fun onPermissionsChanged() {
        if (users > 0) {
            location.stop()
            location.start()
        }
    }

    fun update(block: (Telemetry) -> Telemetry) = _state.update { old ->
        val next = block(old)
        val now = System.currentTimeMillis()
        val speed = next.currentSpeed(now) ?: 0f
        next.copy(
            maxSpeedMps = max(next.maxSpeedMps, speed),
            // Android Auto doesn't share the gear selector, so "driving" means actually moving.
            tripStartMillis = next.tripStartMillis ?: now.takeIf { speed >= TRIP_START_SPEED_MPS },
        )
    }

    /** Clears the trip; the timer starts again when the car next moves. */
    fun resetTrip() = _state.update {
        it.copy(tripStartMillis = null, tripDistanceM = 0.0, maxSpeedMps = 0f)
    }

    /** Each drive gets a fresh trip, unless the car was only switched off for a short stop. */
    fun onCarConnected() {
        if (System.currentTimeMillis() - lastCarDisconnect > SHORT_STOP_MS) {
            resetTrip()
            route.clear()
        }
    }

    fun onCarDisconnected() {
        lastCarDisconnect = System.currentTimeMillis()
    }

    private var lastCarDisconnect = 0L

    private companion object {
        const val TRIP_START_SPEED_MPS = 1f / 3.6f // 1 km/h
        const val SHORT_STOP_MS = 10 * 60_000L
    }
}
