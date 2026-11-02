package com.dashdeck.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.dashdeck.R
import com.dashdeck.dashDeck
import com.dashdeck.model.Shortcut
import com.dashdeck.model.ShortcutKind
import com.dashdeck.render.DashboardRenderer
import com.dashdeck.video.VideoBrowser
import kotlin.math.max

/**
 * Opened by the floating button or Android Auto's own button: the same list as the panel beside
 * the dashboard, full height.
 */
class MenuScreen(
    carContext: CarContext,
    private val currentPage: Int,
    private val onPageSelected: (Int) -> Unit,
) : Screen(carContext) {
    private val menu = MenuItems(carContext, ShortcutRunner(carContext, lifecycleScope))

    override fun onGetTemplate(): Template = ListTemplate.Builder()
        .setTitle("DashDeck")
        .setHeaderAction(Action.BACK)
        .setSingleList(menu.build(currentPage, onPageSelected) { screenManager.pop() })
        .build()
}

/**
 * Shortcuts first (these always respond, unlike taps on the dashboard, which some hosts don't pass
 * through), then music, pages and the trip. [done] runs after an item has done its job: the full
 * menu closes, the side panel refreshes.
 */
class MenuItems(private val carContext: CarContext, private val runner: ShortcutRunner) {
    private val app = carContext.dashDeck

    fun build(currentPage: Int, onPageSelected: (Int) -> Unit, done: () -> Unit): ItemList {
        val config = app.configRepository.config.value
        val pages = config.pages
        val items = ItemList.Builder()
        val limit = listLimit()
        val track = app.telemetry.state.value.nowPlaying
        if (track != null) {
            // Dashboard taps don't reach the app on some cars, so music controls live here too.
            items.addItem(
                Row.Builder()
                    .setTitle(if (track.playing) "Pause" else "Play")
                    .addText(listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" · "))
                    .setImage(icon(if (track.playing) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener {
                        app.telemetry.media.playPause()
                        done()
                    }
                    .build(),
            )
            items.addItem(
                Row.Builder()
                    .setTitle("Next song")
                    .setImage(icon(R.drawable.ic_skip_next))
                    .setOnClickListener {
                        app.telemetry.media.next()
                        done()
                    }
                    .build(),
            )
        }
        val musicRows = if (track != null) 2 else 0
        val route = app.telemetry.state.value.route
        // "All shortcuts" and "Reset trip", plus "Close YouTube" and "Clear route" while there's one.
        val fixedRows = 2 + (if (VideoBrowser.isOpen) 1 else 0) + (if (route != null) 1 else 0)
        val shortcuts = config.shortcuts.take(max(0, limit - pages.size - fixedRows - musicRows))
        for (shortcut in shortcuts) {
            items.addItem(
                Row.Builder()
                    .setTitle(shortcut.name.ifBlank { shortcut.kind.label })
                    .addText(shortcutDetail(shortcut))
                    .setImage(icon(DashboardRenderer.iconFor(shortcut.kind)))
                    .setOnClickListener { runner.run(shortcut) }
                    .build(),
            )
        }
        pages.take(max(0, limit - shortcuts.size - fixedRows - musicRows)).forEachIndexed { index, page ->
            items.addItem(
                Row.Builder()
                    .setTitle("Page: " + page.name.ifBlank { "${index + 1}" })
                    .apply { if (index == currentPage) addText("Showing now") }
                    .setOnClickListener {
                        onPageSelected(index)
                        done()
                    }
                    .build(),
            )
        }
        if (VideoBrowser.isOpen) {
            items.addItem(
                Row.Builder()
                    .setTitle("Close YouTube")
                    .addText("Stops the video and its sound")
                    .setImage(icon(R.drawable.ic_video))
                    .setOnClickListener {
                        VideoBrowser.close()
                        done()
                    }
                    .build(),
            )
        }
        if (route != null) {
            items.addItem(
                Row.Builder()
                    .setTitle("Clear route")
                    .addText("Removes the line to ${route.destination}")
                    .setImage(icon(R.drawable.ic_navigate))
                    .setOnClickListener {
                        app.telemetry.route.clear()
                        done()
                    }
                    .build(),
            )
        }
        items.addItem(
            Row.Builder()
                .setTitle("All shortcuts")
                .setOnClickListener { carContext.getCarService(ScreenManager::class.java).push(ShortcutsScreen(carContext)) }
                .build(),
        )
        items.addItem(
            Row.Builder()
                .setTitle("Reset trip")
                .setOnClickListener {
                    app.telemetry.resetTrip()
                    CarToast.makeText(carContext, "Trip reset", CarToast.LENGTH_SHORT).show()
                    done()
                }
                .build(),
        )
        return items.build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun shortcutDetail(shortcut: Shortcut) = when (shortcut.kind) {
        ShortcutKind.NAVIGATE -> "Navigate with " + shortcut.navApp.label
        else -> shortcut.kind.label
    }

    private fun listLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            6
        }
}
