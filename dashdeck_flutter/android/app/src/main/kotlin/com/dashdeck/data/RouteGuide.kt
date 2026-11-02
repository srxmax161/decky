package com.dashdeck.data

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/** One manoeuvre on the route: where it is along the line and what to tell the driver. */
data class Turn(
    /** Metres from the start of the route. */
    val alongM: Double,
    val instruction: String,
    val street: String,
    /** Which way the arrow points, in degrees clockwise from straight ahead; null at the destination. */
    val angle: Float?,
)

/**
 * The line to draw. Points are kept in Web Mercator (0–1 across and down the world) so drawing
 * needs no trigonometry, plus plain latitude/longitude for measuring.
 */
class RoutePath(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val mercX: DoubleArray,
    val mercY: DoubleArray,
    /** Metres from the start to each point. */
    val alongM: DoubleArray,
    val turns: List<Turn>,
    val durationS: Double,
) {
    val distanceM get() = alongM.last()
}

/** A route DashDeck looked up itself, so its maps can show a line like the navigation app's. */
data class RouteState(
    val destination: String,
    val status: Status,
    val path: RoutePath? = null,
    /** The path point just behind the car. */
    val passedIndex: Int = 0,
    /** How far the car is from the line; large means it's taken another way. */
    val offRouteM: Double = 0.0,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val nextTurn: Turn? = null,
    val nextTurnM: Double = 0.0,
) {
    enum class Status { FINDING, ACTIVE, ARRIVED, NEEDS_ADDRESS, NOT_FOUND, FAILED }
}

/**
 * Finds a driving route from the car to a shortcut's destination with the public OSRM server
 * (OpenStreetMap roads, no traffic), follows the car along it and asks again when the car leaves it.
 * Waze and Google Maps keep their own routes to themselves, so this is DashDeck's best guess at the
 * same trip: after a detour it re-plans from wherever the car is, which usually brings it back in
 * line with the app doing the navigating. Use from the main thread.
 */
class RouteGuide(private val context: Context, private val hub: TelemetryHub, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var dismiss: Job? = null
    private var target: Pair<Double, Double>? = null
    private var offRouteFixes = 0
    private var lastRequestAt = 0L
    private val places = mutableMapOf<String, Pair<Double, Double>>()

    /** Plans a route to [query] (an address, place name or "lat, lon"), labelled [label] on screen. */
    fun start(label: String, query: String) {
        cancel()
        Log.i(TAG, "Route to '$label': looking up '$query'")
        hub.update { it.copy(route = RouteState(label, RouteState.Status.FINDING)) }
        job = scope.launch {
            val near = hub.state.value.let { t -> t.latitude?.let { lat -> t.longitude?.let { lat to it } } }
            val place = places[query] ?: withContext(Dispatchers.IO) { geocode(query, near) }
            if (place == null) {
                Log.i(TAG, "Route: couldn't find '$query'")
                finish(RouteState.Status.NOT_FOUND)
                return@launch
            }
            places[query] = place
            target = place
            fetch()
        }
    }

    /** Explains why a shortcut has no line, briefly, on the map. */
    fun needsAddress(label: String) {
        cancel()
        finish(RouteState.Status.NEEDS_ADDRESS, label)
    }

    fun clear() {
        cancel()
        hub.update { it.copy(route = null) }
    }

    private fun cancel() {
        job?.cancel()
        dismiss?.cancel()
        target = null
        offRouteFixes = 0
    }

    /** Shows a final state for a little while, then takes the route off the map. */
    private fun finish(status: RouteState.Status, label: String? = null) {
        target = null
        hub.update { t ->
            t.copy(route = (t.route ?: RouteState(label.orEmpty(), status)).copy(status = status))
        }
        dismiss?.cancel()
        dismiss = scope.launch {
            delay(if (status == RouteState.Status.ARRIVED) ARRIVED_SHOW_MS else MESSAGE_SHOW_MS)
            hub.update { it.copy(route = null) }
        }
    }

    private suspend fun fetch() {
        val destination = target ?: return
        val t = hub.state.value
        val lat = t.latitude ?: return // onFix asks again once there's a position
        val lon = t.longitude ?: return
        // Starting in the direction the car already faces avoids routes that begin with a U-turn.
        val heading = t.headingDeg?.takeIf { (t.currentSpeed(System.currentTimeMillis()) ?: 0f) > 2f }
        lastRequestAt = SystemClock.elapsedRealtime()
        val path = withContext(Dispatchers.IO) {
            runCatching { requestRoute(lat, lon, destination, heading) }
                .onFailure { Log.w(TAG, "Route request failed: $it") }
                .getOrNull()
        }
        if (target != destination) return // cancelled or replaced meanwhile
        val route = hub.state.value.route ?: return
        if (path == null) {
            // Keep following the old line if there is one; the next fix tries again.
            if (route.path == null) finish(RouteState.Status.FAILED)
            return
        }
        Log.i(TAG, "Route to '${route.destination}': ${path.distanceM.toInt()} m, ${path.turns.size} turns")
        offRouteFixes = 0
        val now = hub.state.value
        hub.update {
            it.copy(route = follow(route.copy(status = RouteState.Status.ACTIVE, path = path, passedIndex = 0),
                now.latitude ?: lat, now.longitude ?: lon))
        }
    }

    fun onFix(location: Location) {
        val route = hub.state.value.route ?: return
        if (target == null || route.status == RouteState.Status.ARRIVED) return
        val path = route.path
        val busy = job?.isActive == true
        val sinceRequest = SystemClock.elapsedRealtime() - lastRequestAt
        if (path == null) {
            if (!busy && sinceRequest > RETRY_MS) job = scope.launch { fetch() }
            return
        }
        val next = follow(route, location.latitude, location.longitude)
        if (next.remainingM < ARRIVED_M) {
            Log.i(TAG, "Route: arrived at '${route.destination}'")
            hub.update { it.copy(route = next) }
            finish(RouteState.Status.ARRIVED)
            return
        }
        offRouteFixes = if (next.offRouteM > OFF_ROUTE_M && location.accuracy <= MAX_ACCURACY_M) offRouteFixes + 1 else 0
        hub.update { it.copy(route = next) }
        if (offRouteFixes >= OFF_ROUTE_FIXES && !busy && sinceRequest > REROUTE_GAP_MS) {
            Log.i(TAG, "Route: ${next.offRouteM.toInt()} m off the line, planning again")
            job = scope.launch { fetch() }
        }
    }

    /** Where the car is along the route: the nearest piece of line, looking ahead of the last one. */
    private fun follow(route: RouteState, lat: Double, lon: Double): RouteState {
        val path = route.path ?: return route
        val last = path.lats.size - 1
        if (last < 1) return route
        var best = nearest(path, lat, lon, (route.passedIndex - 2).coerceAtLeast(0), (route.passedIndex + WINDOW).coerceAtMost(last))
        // Lost it (a detour, or a gap in fixes): try the whole line before calling it off-route.
        if (best.distance > OFF_ROUTE_M) best = nearest(path, lat, lon, 0, last)
        val along = path.alongM[best.index] + best.fraction * (path.alongM[best.index + 1] - path.alongM[best.index])
        val remaining = (path.distanceM - along).coerceAtLeast(0.0)
        val turn = path.turns.firstOrNull { it.alongM > along + 5.0 }
        return route.copy(
            passedIndex = best.index,
            offRouteM = best.distance,
            remainingM = remaining,
            remainingS = if (path.distanceM > 0) path.durationS * remaining / path.distanceM else 0.0,
            nextTurn = turn,
            nextTurnM = turn?.let { it.alongM - along } ?: 0.0,
        )
    }

    private class Nearest(val index: Int, val fraction: Double, val distance: Double)

    /** Closest point on segments [from]..[to] (each from point i to i + 1), in metres. */
    private fun nearest(path: RoutePath, lat: Double, lon: Double, from: Int, to: Int): Nearest {
        val metersPerDegLat = EARTH_M * PI / 180.0
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(lat))
        var bestIndex = from
        var bestFraction = 0.0
        var bestDistance = Double.MAX_VALUE
        for (i in from until to) {
            // The car at the origin, the segment from a to b, in local metres.
            val ax = (path.lons[i] - lon) * metersPerDegLon
            val ay = (path.lats[i] - lat) * metersPerDegLat
            val bx = (path.lons[i + 1] - lon) * metersPerDegLon
            val by = (path.lats[i + 1] - lat) * metersPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val f = if (lengthSq == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0)
            val distance = hypot(ax + f * dx, ay + f * dy)
            if (distance < bestDistance) {
                bestIndex = i
                bestFraction = f
                bestDistance = distance
            }
        }
        return Nearest(bestIndex, bestFraction, bestDistance)
    }

    // region Network

    private fun requestRoute(lat: Double, lon: Double, destination: Pair<Double, Double>, heading: Float?): RoutePath {
        val bearings = heading?.let { "&bearings=${it.toInt()},60;" }.orEmpty()
        val url = "$OSRM/route/v1/driving/$lon,$lat;${destination.second},${destination.first}" +
            "?overview=full&geometries=polyline6&steps=true$bearings"
        val json = JSONObject(get(url))
        check(json.optString("code") == "Ok") { "OSRM: ${json.optString("code")} ${json.optString("message")}" }
        val route = json.getJSONArray("routes").getJSONObject(0)
        val points = decodePolyline(route.getString("geometry"))
        check(points.size >= 2) { "OSRM: empty route" }
        val lats = DoubleArray(points.size) { points[it].first }
        val lons = DoubleArray(points.size) { points[it].second }
        val along = DoubleArray(points.size)
        val result = FloatArray(1)
        for (i in 1 until points.size) {
            Location.distanceBetween(lats[i - 1], lons[i - 1], lats[i], lons[i], result)
            along[i] = along[i - 1] + result[0]
        }

        val turns = mutableListOf<Turn>()
        var searchFrom = 0
        val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        for (s in 0 until steps.length()) {
            val step = steps.getJSONObject(s)
            val maneuver = step.getJSONObject("maneuver")
            val location = maneuver.getJSONArray("location")
            val index = closestPoint(lats, lons, location.getDouble(1), location.getDouble(0), searchFrom)
            searchFrom = index
            val type = maneuver.optString("type")
            val modifier = maneuver.optString("modifier").ifBlank { null }
            if (type in IGNORED_TYPES || (type == "continue" && modifier in STRAIGHT)) continue
            val street = step.optString("name").ifBlank { step.optString("ref") }
            turns += Turn(
                alongM = along[index],
                instruction = instruction(type, modifier, maneuver.optInt("exit", 0)),
                street = if (type == "arrive") "" else street,
                angle = if (type == "arrive") null else ANGLES[modifier] ?: 0f,
            )
        }
        return RoutePath(
            lats, lons,
            mercX = DoubleArray(points.size) { (lons[it] + 180.0) / 360.0 },
            mercY = DoubleArray(points.size) {
                val latRad = Math.toRadians(lats[it].coerceIn(-85.0, 85.0))
                (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
            },
            alongM = along,
            turns = turns,
            durationS = route.getDouble("duration"),
        )
    }

    /** OSRM's step locations sit on the line, so the first point that close is the one. */
    private fun closestPoint(lats: DoubleArray, lons: DoubleArray, lat: Double, lon: Double, from: Int): Int {
        var best = from
        var bestDistance = Double.MAX_VALUE
        for (i in from until lats.size) {
            val d = hypot(lats[i] - lat, (lons[i] - lon) * cos(Math.toRadians(lat)))
            if (d < bestDistance) {
                best = i
                bestDistance = d
                if (d < 1e-6) break
            }
        }
        return best
    }

    private fun instruction(type: String, modifier: String?, exit: Int): String {
        val way = modifier?.takeUnless { it == "straight" }
        return when (type) {
            "arrive" -> "Arrive"
            "roundabout", "rotary" -> if (exit > 0) "At the roundabout, take exit $exit" else "Enter the roundabout"
            "exit roundabout", "exit rotary" -> "Leave the roundabout"
            "merge" -> "Merge" + (way?.let { " $it" } ?: "")
            "on ramp" -> "Take the ramp" + (way?.let { " on the $it" } ?: "")
            "off ramp" -> "Take the exit" + (way?.let { " on the $it" } ?: "")
            "fork" -> way?.let { "Keep ${it.removePrefix("slight ").removePrefix("sharp ")}" } ?: "Keep straight"
            else -> when (modifier) {
                "uturn" -> "Make a U-turn"
                null, "straight" -> "Continue straight"
                else -> "Turn $modifier"
            }
        }
    }

    private fun geocode(query: String, near: Pair<Double, Double>?): Pair<Double, Double>? {
        COORDINATES.matchEntire(query.trim())?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        if (Geocoder.isPresent()) {
            val geocoder = Geocoder(context)
            @Suppress("DEPRECATION")
            val found = runCatching {
                // Prefer a match near the car; "Petronas" means the one down the road.
                near?.let { (lat, lon) ->
                    geocoder.getFromLocationName(query, 1, lat - NEARBY_DEG, lon - NEARBY_DEG, lat + NEARBY_DEG, lon + NEARBY_DEG)
                }?.firstOrNull() ?: geocoder.getFromLocationName(query, 1)?.firstOrNull()
            }.onFailure { Log.w(TAG, "Geocoder failed for '$query': $it") }.getOrNull()
            if (found != null) return found.latitude to found.longitude
        }
        // Phones without Google's geocoder (and emulators) ask OpenStreetMap instead.
        return runCatching {
            val bias = near?.let { (lat, lon) ->
                "&viewbox=${lon - NEARBY_DEG},${lat + NEARBY_DEG},${lon + NEARBY_DEG},${lat - NEARBY_DEG}"
            }.orEmpty()
            val body = get("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${Uri.encode(query)}$bias")
            val first = org.json.JSONArray(body).optJSONObject(0) ?: return@runCatching null
            first.getString("lat").toDouble() to first.getString("lon").toDouble()
        }.onFailure { Log.w(TAG, "Nominatim failed for '$query': $it") }.getOrNull()
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "DashDeck/1.0 (personal Android Auto dashboard)")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} from ${URL(url).host}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Google's encoded polyline format, at OSRM's six decimal places. */
    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0L
                var b: Int
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            points += lat / 1e6 to lon / 1e6
        }
        return points
    }

    // endregion

    private companion object {
        const val TAG = "DashDeck"
        const val OSRM = "https://router.project-osrm.org"
        const val EARTH_M = 6_371_000.0
        /** Path points searched ahead of the last match on each fix. */
        const val WINDOW = 400
        const val OFF_ROUTE_M = 50.0
        const val OFF_ROUTE_FIXES = 3
        const val MAX_ACCURACY_M = 30f
        const val ARRIVED_M = 40.0
        const val REROUTE_GAP_MS = 15_000L
        const val RETRY_MS = 15_000L
        const val ARRIVED_SHOW_MS = 30_000L
        const val MESSAGE_SHOW_MS = 12_000L
        const val NEARBY_DEG = 1.0
        val COORDINATES = Regex("""(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""")
        val IGNORED_TYPES = setOf("depart", "new name", "notification", "use lane")
        val STRAIGHT = setOf(null, "straight")
        val ANGLES = mapOf(
            "straight" to 0f, "slight right" to 45f, "right" to 90f, "sharp right" to 135f,
            "uturn" to 180f, "sharp left" to -135f, "left" to -90f, "slight left" to -45f,
        )
    }
}
