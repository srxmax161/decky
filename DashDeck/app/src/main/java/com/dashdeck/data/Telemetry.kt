package com.dashdeck.data

/** One snapshot of everything the dashboard can show. Null means "not available". */
data class Telemetry(
    val gpsSpeedMps: Float? = null,
    val carSpeedMps: Float? = null,
    val headingDeg: Float? = null,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val lastFixMillis: Long = 0L,
    val tripStartMillis: Long = System.currentTimeMillis(),
    val tripDistanceM: Double = 0.0,
    val maxSpeedMps: Float = 0f,
    val phoneBatteryPct: Int? = null,
    val phoneCharging: Boolean = false,
    val energyPct: Float? = null,
    val energyIsFuel: Boolean = true,
    val energyLow: Boolean = false,
    val rangeM: Float? = null,
    val odometerM: Float? = null,
) {
    fun hasFix(now: Long) = now - lastFixMillis < FIX_TIMEOUT_MS

    /** Car-reported speed matches the speedometer, so it wins over GPS when available. */
    fun currentSpeed(now: Long): Float? = carSpeedMps ?: gpsSpeedMps?.takeIf { hasFix(now) }

    fun averageSpeedMps(now: Long): Float? {
        val seconds = (now - tripStartMillis) / 1000.0
        return if (seconds < 30) null else (tripDistanceM / seconds).toFloat()
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 5_000L
    }
}
