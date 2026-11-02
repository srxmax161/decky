package com.dashdeck.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dashdeck.R
import com.dashdeck.dashDeck
import com.dashdeck.model.Shortcut
import com.dashdeck.model.ShortcutKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** The "deck": a grid of one-tap buttons configured on the phone. */
class ShortcutsScreen(carContext: CarContext) : Screen(carContext) {
    private val app = carContext.dashDeck

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.configRepository.config.map { it.shortcuts }.distinctUntilChanged().collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Add shortcuts in the DashDeck phone app")
        app.configRepository.config.value.shortcuts.take(gridLimit()).forEach { shortcut ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(shortcut.name.ifBlank { shortcut.kind.label })
                    .setText(shortcut.kind.label)
                    .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, iconFor(shortcut.kind))).build())
                    .setOnClickListener { run(shortcut) }
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setTitle("Deck")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun run(shortcut: Shortcut) {
        val target = shortcut.target.trim()
        if (target.isEmpty()) {
            toast("${shortcut.name}: nothing configured")
            return
        }
        when (shortcut.kind) {
            ShortcutKind.NAVIGATE -> launch(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + Uri.encode(target))))
            ShortcutKind.DIAL -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target))))
            ShortcutKind.WEBHOOK -> lifecycleScope.launch {
                toast("${shortcut.name}…")
                val ok = withContext(Dispatchers.IO) { postWebhook(target) }
                toast(if (ok) "${shortcut.name} ✓" else "${shortcut.name} failed")
            }
        }
    }

    private fun launch(intent: Intent) {
        runCatching { carContext.startCarApp(intent) }.onFailure { toast("Couldn't start: ${it.message}") }
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

    private fun gridLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } else {
            6
        }

    private fun toast(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()

    private fun iconFor(kind: ShortcutKind) = when (kind) {
        ShortcutKind.NAVIGATE -> R.drawable.ic_navigate
        ShortcutKind.DIAL -> R.drawable.ic_dial
        ShortcutKind.WEBHOOK -> R.drawable.ic_webhook
    }
}
