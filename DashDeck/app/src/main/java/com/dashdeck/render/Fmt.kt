package com.dashdeck.render

import com.dashdeck.model.Units
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    fun speedValue(mps: Float, units: Units) = if (units == Units.METRIC) mps * 3.6f else mps * 2.236936f

    fun speed(mps: Float?, units: Units) = mps?.let { speedValue(it, units).roundToInt().toString() } ?: "--"

    fun speedUnit(units: Units) = if (units == Units.METRIC) "km/h" else "mph"

    fun distance(meters: Double?, units: Units, decimals: Int = 1): String {
        meters ?: return "--"
        val value = if (units == Units.METRIC) meters / 1000 else meters / METERS_PER_MILE
        return String.format(Locale.getDefault(), "%,.${decimals}f", value)
    }

    fun distanceUnit(units: Units) = if (units == Units.METRIC) "km" else "mi"

    fun length(meters: Double?, units: Units) =
        meters?.let { (if (units == Units.METRIC) it else it * FEET_PER_METER).roundToInt().toString() } ?: "--"

    fun lengthUnit(units: Units) = if (units == Units.METRIC) "m" else "ft"

    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun cardinal(degrees: Float): String {
        val normalized = (degrees % 360 + 360) % 360
        return CARDINALS[(normalized / 45f).roundToInt() % 8]
    }

    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
}
