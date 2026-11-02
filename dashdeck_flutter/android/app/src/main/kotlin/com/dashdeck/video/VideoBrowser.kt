package com.dashdeck.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.dashdeck.mirror.FrameGrabber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * YouTube's own mobile site, running in a WebView on a private display that nobody sees, with its
 * frames copied to the car screen. Car taps are delivered straight into the WebView, so no special
 * permission is needed. Everything except [draw] and [frames] must be called on the main thread.
 */
object VideoBrowser {
    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames.asStateFlow()

    private val handler by lazy { Handler(HandlerThread("DashDeck video").apply { start() }.looper) }
    private val grabber by lazy { FrameGrabber("Video", handler) { _frames.value++ } }
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    private var webView: WebView? = null
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val poller = Runnable { pollVideo() }

    /** Where the video itself is in the frame (frame pixels), for the mini player. */
    @Volatile var videoRect: Rect? = null
        private set

    @Volatile var playing: Boolean = false
        private set

    val isOpen: Boolean get() = webView != null

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {
        if (webView != null) return
        val app = context.applicationContext
        val virtual = app.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "DashDeck video", WIDTH, HEIGHT, DPI, grabber.surface(WIDTH, HEIGHT),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = virtual
        val screen = Presentation(app, virtual.display)
        val web = WebView(screen.context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                // YouTube's fullscreen button hands us the video view to show on its own.
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    exitFullscreen()
                    fullscreen = view
                    fullscreenCallback = callback
                    container?.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                }

                override fun onHideCustomView() = exitFullscreen()
            }
            loadUrl(HOME)
        }
        val root = FrameLayout(screen.context).apply { addView(web, FrameLayout.LayoutParams(MATCH, MATCH)) }
        screen.setContentView(root)
        screen.show()
        presentation = screen
        container = root
        webView = web
        main.postDelayed(poller, POLL_MS)
        Log.i("DashDeck", "Video browser opened")
    }

    fun close() {
        main.removeCallbacks(poller)
        exitFullscreen()
        webView?.destroy()
        webView = null
        container = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
        grabber.close()
        videoRect = null
        playing = false
    }

    fun search(query: String) {
        webView?.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(query))
    }

    fun home() {
        exitFullscreen()
        webView?.loadUrl(HOME)
    }

    fun back() {
        val web = webView ?: return
        when {
            fullscreen != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
        }
    }

    /** A tap at a point given as fractions (0–1) of the frame. */
    fun tap(nx: Float, ny: Float) {
        val target = touchTarget() ?: return
        val x = nx * WIDTH
        val y = ny * HEIGHT
        val down = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(touch(down, down, MotionEvent.ACTION_DOWN, x, y))
        main.postDelayed({ target.dispatchTouchEvent(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)) }, TAP_MS)
    }

    /** Scrolls the page by most of a screen, as a finger drag so any scrolling list responds. */
    fun scroll(down: Boolean) {
        val target = touchTarget() ?: return
        val x = WIDTH * 0.5f
        val from = HEIGHT * if (down) 0.8f else 0.2f
        val to = HEIGHT * if (down) 0.2f else 0.8f
        val start = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(touch(start, start, MotionEvent.ACTION_DOWN, x, from))
        for (step in 1..DRAG_STEPS) {
            val y = from + (to - from) * step / DRAG_STEPS
            main.postDelayed({
                val action = if (step == DRAG_STEPS) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                target.dispatchTouchEvent(touch(start, SystemClock.uptimeMillis(), action, x, y))
            }, step * DRAG_STEP_MS)
        }
    }

    fun togglePlay() {
        webView?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})()",
            null,
        )
    }

    /**
     * Draws the page as large as fits in [bounds] (or, with [videoOnly], just the playing video,
     * filling the bounds). Returns where the frame landed, or null when there's nothing to show.
     */
    fun draw(canvas: Canvas, bounds: RectF, videoOnly: Boolean = false): RectF? {
        if (!videoOnly) return grabber.draw(canvas, bounds)
        val video = videoRect ?: return null
        return grabber.draw(canvas, bounds, crop = video, fill = true)
    }

    private fun touchTarget(): View? = fullscreen ?: presentation?.window?.decorView

    private fun touch(downTime: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    private fun exitFullscreen() {
        val view = fullscreen ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreen = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    /** Keeps track of where the video sits on the page and whether it's playing. */
    private fun pollVideo() {
        val web = webView ?: return
        if (fullscreen != null) {
            videoRect = Rect(0, 0, WIDTH, HEIGHT)
        }
        web.evaluateJavascript(
            "(function(){var v=Array.from(document.querySelectorAll('video')).find(function(v){return v.offsetWidth>0});" +
                "if(!v)return null;var r=v.getBoundingClientRect();" +
                "return [r.left,r.top,r.width,r.height,window.devicePixelRatio,v.paused?0:1];})()",
        ) { result ->
            val values = result?.trim('[', ']')?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
            if (values == null || values.size < 6) {
                if (fullscreen == null) videoRect = null
                playing = false
            } else {
                val (left, top, width, height, ratio) = values
                playing = values[5] == 1f
                if (fullscreen == null) {
                    val rect = Rect(
                        (left * ratio).roundToInt().coerceIn(0, WIDTH), (top * ratio).roundToInt().coerceIn(0, HEIGHT),
                        ((left + width) * ratio).roundToInt().coerceIn(0, WIDTH),
                        ((top + height) * ratio).roundToInt().coerceIn(0, HEIGHT),
                    )
                    videoRect = rect.takeIf { it.width() > 40 && it.height() > 40 }
                }
            }
        }
        main.postDelayed(poller, POLL_MS)
    }

    private const val HOME = "https://m.youtube.com/"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val DPI = 240
    private const val POLL_MS = 1_000L
    private const val TAP_MS = 40L
    private const val DRAG_STEPS = 8
    private const val DRAG_STEP_MS = 16L
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
