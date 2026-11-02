package com.dashdeck.video

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.FrameLayout
import android.widget.VideoView
import com.dashdeck.data.WallpaperStore
import com.dashdeck.mirror.FrameGrabber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The user's video wallpaper, looping without sound on a private display nobody sees, with its frames
 * copied behind the car dashboard the same way as the YouTube page. It never asks for audio focus,
 * so music keeps playing. Everything except [draw] must be called on the main thread.
 */
object VideoWallpaper {
    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames.asStateFlow()

    private val handler by lazy { Handler(HandlerThread("DashDeck wallpaper").apply { start() }.looper) }
    private val grabber by lazy { FrameGrabber("Wallpaper", handler) { _frames.value++ } }
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var video: VideoView? = null
    private var shownRev = 0L
    private var paused = false

    /** Starts the video saved at wallpaper revision [rev], unless it's already the one playing (or can't). */
    fun show(context: Context, rev: Long) {
        if (rev == shownRev) return
        hide()
        shownRev = rev
        val app = context.applicationContext
        val file = WallpaperStore.videoFile(app) ?: return
        val (width, height) = displaySize(file.path) ?: return
        val virtual = app.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "DashDeck wallpaper", width, height, DPI, grabber.surface(width, height),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val screen = Presentation(app, virtual.display)
        val view = VideoView(screen.context).apply {
            // Silent and in the background: leave the music alone.
            setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            setOnPreparedListener { player ->
                player.isLooping = true
                player.setVolume(0f, 0f)
                if (!paused) start()
            }
            setOnErrorListener { _, what, extra ->
                Log.w("DashDeck", "Video wallpaper can't play ($what/$extra)")
                true
            }
            setVideoPath(file.path)
        }
        screen.setContentView(FrameLayout(screen.context).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
        screen.show()
        display = virtual
        presentation = screen
        video = view
        Log.i("DashDeck", "Video wallpaper playing at ${width}x$height")
    }

    fun hide() {
        shownRev = 0L
        if (display == null) return
        video?.stopPlayback()
        video = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
        grabber.close()
    }

    /** Holds the current frame (while driving, or when no page shows the wallpaper). */
    fun setPaused(pause: Boolean) {
        if (pause == paused) return
        paused = pause
        video?.let { if (pause) it.pause() else it.start() }
    }

    /** Fills [bounds] with the latest frame, cropping the edges; false until there is one. */
    fun draw(canvas: Canvas, bounds: RectF): Boolean = grabber.draw(canvas, bounds, fill = true) != null

    /** The video's shape, at most [MAX_EDGE] pixels on its long side, which is plenty behind dimmed widgets. */
    private fun displaySize(path: String): Pair<Int, Int>? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) w = h.also { h = w }
            check(w > 0 && h > 0)
            val scale = minOf(1f, MAX_EDGE.toFloat() / max(w, h))
            // Even sizes keep video decoders happy.
            ((w * scale / 2).roundToInt() * 2) to ((h * scale / 2).roundToInt() * 2)
        } finally {
            retriever.release()
        }
    }.onFailure { Log.w("DashDeck", "Video wallpaper unreadable: $it") }.getOrNull()

    private const val MAX_EDGE = 1280
    private const val DPI = 160
}
