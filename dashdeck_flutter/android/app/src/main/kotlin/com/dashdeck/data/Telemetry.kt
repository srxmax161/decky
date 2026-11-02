package com.dashdeck.data

/** One snapshot of everything the dashboard can show. Null means "not available". */
data class Telemetry(
    val gpsSpeedMps: Float? = null,
    val carSpeedMps: Float? = null,
    val headingDeg: Float? = null,
    val altitudeM: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    val lastFixMillis: Long = 0L,
    /** When the car first moved this trip; null while waiting for it to move. */
    val tripStartMillis: Long? = null,
    val tripDistanceM: Double = 0.0,
    val maxSpeedMps: Float = 0f,
    val phoneBatteryPct: Int? = null,
    val phoneCharging: Boolean = false,
    val energyPct: Float? = null,
    val energyIsFuel: Boolean = true,
    val energyLow: Boolean = false,
    val rangeM: Float? = null,
    val odometerM: Float? = null,
    /** False once the car has answered that it doesn't share charge/fuel or the odometer; null until then. */
    val energyShared: Boolean? = null,
    val mileageShared: Boolean? = null,
    /** Whether DashDeck has notification access, which reading other apps' playback requires. */
    val mediaAccess: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val lyrics: Lyrics? = null,
    /** DashDeck's own route to a shortcut's destination, while there is one. */
    val route: RouteState? = null,
) {
    fun hasFix(now: Long) = now - lastFixMillis < FIX_TIMEOUT_MS

    /** Car-reported speed matches the speedometer, so it wins over GPS when available. */
    fun currentSpeed(now: Long): Float? = carSpeedMps ?: gpsSpeedMps?.takeIf { hasFix(now) }

    /** Moving faster than a crawl; mirrored apps are hidden then. Unknown speed counts as parked. */
    fun isDriving(now: Long): Boolean = (currentSpeed(now) ?: 0f) >= DRIVING_MPS

    fun tripElapsedMillis(now: Long): Long = tripStartMillis?.let { now - it } ?: 0L

    fun averageSpeedMps(now: Long): Float? {
        val seconds = tripElapsedMillis(now) / 1000.0
        return if (seconds < 30) null else (tripDistanceM / seconds).toFloat()
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 5_000L
        const val DRIVING_MPS = 5f / 3.6f
    }
}
