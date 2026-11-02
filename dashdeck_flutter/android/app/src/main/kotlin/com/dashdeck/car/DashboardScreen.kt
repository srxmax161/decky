package com.dashdeck.car

import android.text.format.DateFormat
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dashdeck.R
import com.dashdeck.dashDeck
import com.dashdeck.mirror.Mirror
import com.dashdeck.mirror.TapService
import com.dashdeck.video.VideoBrowser
import com.dashdeck.video.VideoWallpaper
import com.dashdeck.model.Wallpaper
import com.dashdeck.model.WidgetType
import com.dashdeck.render.DashboardRenderer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DashboardScreen(carContext: CarContext) : Screen(carContext) {
    private val app = carContext.dashDeck
    private val renderer = DashboardRenderer(carContext)
    private val runner = ShortcutRunner(carContext, lifecycleScope)
    private var pageIndex = 0

    private val surface = SurfaceRenderer(carContext, lifecycle, onTap = ::onTap) { canvas, area, density ->
        renderer.is24Hour = DateFormat.is24HourFormat(carContext)
        renderer.draw(
            canvas, area, density,
            app.configRepository.config.value, pageIndex,
            app.telemetry.state.value, System.currentTimeMillis(),
        )
    }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.configRepository.config.collect { config ->
                        pageIndex = pageIndex.coerceIn(0, config.pages.lastIndex)
                        openVideoIfShown()
                        updateWallpaper()
                        surface.render()
                    }
                }
                launch {
                    app.telemetry.state.collect {
                        updateWallpaper()
                        surface.render()
                    }
                }
                launch { VideoWallpaper.frames.collect { if (showsWallpaper()) surface.render() } }
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        VideoWallpaper.hide()
                    }
                }
                launch { Mirror.running.collect { surface.render() } }
                launch { Mirror.frames.collect { if (onMirrorPage() || showsVideo()) surface.render() } }
                launch { VideoBrowser.frames.collect { if (showsVideo()) surface.render() } }
                launch {
                    // Keeps the clock and trip timer ticking even when no sensor data arrives, and
                    // lyrics in step with the song.
                    while (true) {
                        delay(if (showsPlayingMusic()) 250L else 1_000L - System.currentTimeMillis() % 1_000L)
                        surface.render()
                    }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Android Auto requires an action strip on this template. It opens the same menu as the
        // floating button, which also covers hosts that don't pass surface taps through.
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_deck)).build())
                    .setOnClickListener(::openMenu)
                    .build(),
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(actions).build()
    }

    private fun onTap(x: Float, y: Float) {
        when (val hit = renderer.hitTest(x, y)) {
            DashboardRenderer.Hit.Menu -> openMenu()
            is DashboardRenderer.Hit.RunShortcut -> runner.run(hit.shortcut)
            DashboardRenderer.Hit.Media.PREVIOUS -> app.telemetry.media.previous()
            DashboardRenderer.Hit.Media.PLAY_PAUSE -> app.telemetry.media.playPause()
            DashboardRenderer.Hit.Media.NEXT -> app.telemetry.media.next()
            DashboardRenderer.Hit.Mirror -> tapMirror(x, y)
            DashboardRenderer.Hit.VideoScreen -> if (!driving()) renderer.mirrorPoint(x, y)?.let { (nx, ny) -> VideoBrowser.tap(nx, ny) }
            DashboardRenderer.Hit.VideoTile -> if (!driving()) {
                // The YouTube page if it has a video; otherwise whichever app is playing, via its media controls.
                if (VideoBrowser.isOpen && VideoBrowser.videoRect != null) VideoBrowser.togglePlay() else app.telemetry.media.playPause()
            }
            DashboardRenderer.Hit.Video.SEARCH -> screenManager.push(VideoSearchScreen(carContext))
            DashboardRenderer.Hit.Video.BACK -> VideoBrowser.back()
            DashboardRenderer.Hit.Video.UP -> VideoBrowser.scroll(down = false)
            DashboardRenderer.Hit.Video.DOWN -> VideoBrowser.scroll(down = true)
            null -> Unit
        }
    }

    /** Plays the video wallpaper while parked on a page that shows it; otherwise holds its frame. */
    private fun updateWallpaper() {
        val config = app.configRepository.config.value
        if (config.wallpaper != Wallpaper.VIDEO) {
            VideoWallpaper.hide()
            return
        }
        VideoWallpaper.show(carContext, config.wallpaperRev)
        VideoWallpaper.setPaused(driving() || !showsWallpaper())
    }

    private fun showsWallpaper(): Boolean {
        val page = app.configRepository.config.value.pages.getOrNull(pageIndex) ?: return false
        return !page.map && !page.mirror && !page.video
    }

    private fun onMirrorPage() = app.configRepository.config.value.pages.getOrNull(pageIndex)?.mirror == true

    private fun driving() = app.telemetry.state.value.isDriving(System.currentTimeMillis())

    /** On the YouTube page, or a page with the mini video widget. */
    private fun showsVideo(): Boolean {
        val page = app.configRepository.config.value.pages.getOrNull(pageIndex) ?: return false
        return page.video || page.tiles.any { it.type == WidgetType.VIDEO }
    }

    /** The browser is heavy, so it only starts once the YouTube page is first shown. */
    private fun openVideoIfShown() {
        if (app.configRepository.config.value.pages.getOrNull(pageIndex)?.video == true) VideoBrowser.open(carContext)
    }

    /** Replays a car-screen tap at the same spot on the phone's screen. */
    private fun tapMirror(x: Float, y: Float) {
        if (app.telemetry.state.value.isDriving(System.currentTimeMillis())) return
        val (nx, ny) = renderer.mirrorPoint(x, y) ?: return
        val point = Mirror.toPhone(carContext, nx, ny)
        if (!TapService.tap(point.x.toFloat(), point.y.toFloat())) {
            CarToast.makeText(carContext, "Turn on DashDeck tap control in the phone app", CarToast.LENGTH_LONG).show()
        }
    }

    private fun showsPlayingMusic(): Boolean {
        if (app.telemetry.state.value.nowPlaying?.playing != true) return false
        val page = app.configRepository.config.value.pages.getOrNull(pageIndex) ?: return false
        return page.tiles.any { it.type == WidgetType.LYRICS || it.type == WidgetType.NOW_PLAYING }
    }

    private fun openMenu() {
        screenManager.push(MenuScreen(carContext, pageIndex, ::selectPage))
    }

    private fun selectPage(selected: Int) {
        pageIndex = selected
        openVideoIfShown()
        updateWallpaper()
        surface.render()
    }
}
