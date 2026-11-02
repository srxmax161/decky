package com.dashdeck.car

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.Display
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import com.dashdeck.dashDeck
import com.dashdeck.model.NavApp
import com.dashdeck.model.Shortcut
import com.dashdeck.model.ShortcutKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Runs a shortcut from either the dashboard's shortcut widget or the Shortcuts screen. */
class ShortcutRunner(private val carContext: CarContext, private val scope: CoroutineScope) {

    fun run(shortcut: Shortcut) {
        val target = shortcut.target.trim()
        if (target.isEmpty()) {
            toast("${shortcut.name}: nothing configured")
            return
        }
        Log.i(TAG, "Running ${shortcut.kind} shortcut '${shortcut.name}' (${shortcut.navApp})")
        when (shortcut.kind) {
            ShortcutKind.NAVIGATE -> {
                navigate(target, shortcut.navApp)
                showRoute(shortcut, target)
            }
            ShortcutKind.DIAL -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target))))
            ShortcutKind.WEBHOOK -> scope.launch {
                toast("${shortcut.name}…")
                val ok = withContext(Dispatchers.IO) { postWebhook(target) }
                toast(if (ok) "${shortcut.name} ✓" else "${shortcut.name} failed")
            }
        }
    }

    private fun navigate(target: String, app: NavApp) {
        // Android Auto can't route this itself: a "navigate" intent goes back to DashDeck (it's the
        // current navigation app), and naming another car app is refused. So hand the route to the
        // app on the phone; its car screen follows the same trip.
        val (name, intent) = when (app) {
            NavApp.WAZE -> "Waze" to Intent(Intent.ACTION_VIEW, wazeUri(target)).setPackage(WAZE)
            NavApp.DEFAULT -> "Google Maps" to
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(target))).setPackage(MAPS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The car context would open it on the car's display, which Android refuses.
        val onPhone = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        Log.i(TAG, "Starting $name on the phone: ${intent.data}")
        runCatching { carContext.applicationContext.startActivity(intent, onPhone) }
            .onSuccess { toast("Route sent to $name") }
            .onFailure {
                Log.w(TAG, "Couldn't start $name", it)
                toast(if (it is ActivityNotFoundException) "$name isn't installed" else "Couldn't start $name")
            }
    }

    /** DashDeck's own line on its maps, following the same trip as the navigation app (roughly). */
    private fun showRoute(shortcut: Shortcut, target: String) {
        val route = carContext.dashDeck.telemetry.route
        val label = shortcut.name.ifBlank { target }
        val place = shortcut.address.trim().ifBlank { target.takeUnless { it.lowercase() in WAZE_FAVORITES } }
        if (place == null) route.needsAddress(label) else route.start(label, place)
    }

    /** "Home" and "Work" use the places saved in Waze; anything else is searched for. */
    private fun wazeUri(target: String): Uri = when (target.lowercase()) {
        in WAZE_FAVORITES -> Uri.parse("waze://?favorite=${target.lowercase()}&navigate=yes")
        else -> Uri.parse("waze://?q=${Uri.encode(target)}&navigate=yes")
    }

    private fun launch(intent: Intent) {
        Log.i(TAG, "startCarApp $intent ${intent.data}")
        runCatching { carContext.startCarApp(intent) }.onFailure {
            Log.w(TAG, "startCarApp failed for $intent", it)
            toast("Couldn't start: ${it.message}")
        }
    }

    private fun postWebhook(url: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun toast(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "DashDeck"
        const val WAZE = "com.waze"
        const val MAPS = "com.google.android.apps.maps"
        val WAZE_FAVORITES = setOf("home", "work")
    }
}
