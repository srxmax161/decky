package com.dashdeck.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.dashdeck.R
import com.dashdeck.data.Lyrics
import com.dashdeck.data.RouteState
import com.dashdeck.data.Telemetry
import com.dashdeck.data.WallpaperStore
import com.dashdeck.model.DashConfig
import com.dashdeck.model.Shortcut
import com.dashdeck.model.ShortcutKind
import com.dashdeck.model.Tile
import com.dashdeck.model.Units
import com.dashdeck.model.Wallpaper
import com.dashdeck.model.WidgetType
import com.dashdeck.video.VideoWallpaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws a dashboard page onto any [Canvas]: wallpaper, see-through widget panels on a 12-column
 * grid, and the floating menu button. Shared by the car surface and the phone preview so what you
 * edit is exactly what you get. Draw from one thread at a time; [hitTest] and [mirrorPoint] may be
 * called from any thread and see the last finished frame.
 */
class DashboardRenderer(private val context: Context) {
    var is24Hour: Boolean = true

    /** False in the phone preview, which would otherwise mirror itself (and shows a video wallpaper's still). */
    var liveMirror: Boolean = true

    /** Where the mirrored phone screen landed on the frame being drawn, for mapping taps. */
    private var mirrorRect: RectF? = null

    // What the last finished frame showed, for taps arriving on another thread.
    @Volatile private var shownHits: List<Pair<RectF, Hit>> = emptyList()
    @Volatile private var shownMirrorRect: RectF? = null

    /** Something tappable on the last frame drawn. */
    sealed interface Hit {
        object Menu : Hit
        object Mirror : Hit
        object VideoScreen : Hit
        object VideoTile : Hit
        enum class Video : Hit { SEARCH, BACK, UP, DOWN }
        data class RunShortcut(val shortcut: Shortcut) : Hit
        enum class Media : Hit { PREVIOUS, PLAY_PAUSE, NEXT }
    }

    private val hits = mutableListOf<Pair<RectF, Hit>>()

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileSource = Rect()

    private val time24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val time12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val amPm = SimpleDateFormat("a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())

    private val path = Path()
    private val scratch = RectF()

    private var background: Bitmap? = null
    private var backgroundKey: List<Any>? = null

    fun hitTest(x: Float, y: Float): Hit? = shownHits.lastOrNull { it.first.contains(x, y) }?.second

    /** A tap at (x, y) as a fraction (0–1) across and down the mirrored screen. */
    fun mirrorPoint(x: Float, y: Float): Pair<Float, Float>? {
        val rect = shownMirrorRect?.takeIf { it.contains(x, y) } ?: return null
        return (x - rect.left) / rect.width() to (y - rect.top) / rect.height()
    }

    fun draw(
        canvas: Canvas,
        area: Rect,
        density: Float,
        config: DashConfig,
        pageIndex: Int,
        telemetry: Telemetry,
        now: Long,
        highlightTileId: String? = null,
    ) {
        hits.clear()
        mirrorRect = null
        try {
            drawPage(canvas, area, density, config, pageIndex, telemetry, now, highlightTileId)
        } finally {
            shownHits = hits.toList()
            shownMirrorRect = mirrorRect
        }
    }

    /** The car's driver display: the map alone, turning with the car, centred in [area]. */
    fun drawCluster(canvas: Canvas, area: Rect, density: Float, config: DashConfig, telemetry: Telemetry, now: Long) {
        hits.clear()
        val screen = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        drawMap(canvas, screen, 0f, density, telemetry, now, config.accent.toInt(), RectF(area))
        drawRouteCard(canvas, RectF(area), density, config.units, telemetry, now)
        shownHits = emptyList()
    }

    private fun drawPage(
        canvas: Canvas,
        area: Rect,
        density: Float,
        config: DashConfig,
        pageIndex: Int,
        telemetry: Telemetry,
        now: Long,
        highlightTileId: String?,
    ) {
        val bounds = RectF(area)
        val page = config.pages.getOrNull(pageIndex)
        if (page?.mirror == true) {
            drawMirror(canvas, bounds, density, telemetry, now)
            return
        }
        if (page?.video == true) {
            drawVideoPage(canvas, bounds, density, telemetry, now, config.accent.toInt())
            return
        }
        val grid = GridLayout.gridRect(bounds, density)
        val cells = page?.let { GridLayout.place(it.tiles, columnFirst = it.map) }.orEmpty()
        var routeArea: RectF? = null
        if (page?.map == true) {
            // The car sits in the middle of whatever the widgets leave uncovered on the right.
            val usedColumns = cells.maxOfOrNull { it.column + it.columns } ?: 0
            val focus = if (usedColumns in 1 until GridLayout.COLUMNS) {
                RectF(GridLayout.columnLeft(grid, density, usedColumns), 0f, canvas.width.toFloat(), canvas.height.toFloat())
            } else {
                RectF(bounds)
            }
            val screen = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            drawMap(canvas, screen, 0f, density, telemetry, now, config.accent.toInt(), focus)
            routeArea = focus
        } else {
            val screen = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            if (config.wallpaper == Wallpaper.VIDEO && liveMirror && VideoWallpaper.draw(canvas, screen)) {
                canvas.drawColor(VEIL)
            } else {
                canvas.drawBitmap(background(canvas.width, canvas.height, config), 0f, 0f, null)
            }
        }
        if (cells.isEmpty()) {
            drawCentered(canvas, grid, "Empty page — add widgets in the DashDeck phone app", 16f * density)
        }
        for (cell in cells) {
            val rect = GridLayout.cellRect(grid, density, cell)
            drawTile(canvas, cell.tile, rect, density, config, telemetry, now)
            if (cell.tile.id == highlightTileId) {
                strokePaint.color = config.accent.toInt()
                strokePaint.strokeWidth = 3f * density
                scratch.set(rect)
                scratch.inset(-2f * density, -2f * density)
                canvas.drawRoundRect(scratch, 16f * density, 16f * density, strokePaint)
            }
        }
        val button = GridLayout.buttonRect(bounds, density)
        // Directions go over the widgets: on a crowded map page they matter more.
        routeArea?.let { drawRouteCard(canvas, it, density, config.units, telemetry, now, avoid = button) }
        drawMenuButton(canvas, button, density, config)
    }

    // region Mirror

    /**
     * The phone's screen, as large as fits, only while parked. There's no floating menu button here
     * so the whole screen stays tappable; Android Auto's own toolbar button opens the menu.
     */
    private fun drawMirror(c: Canvas, bounds: RectF, d: Float, t: Telemetry, now: Long) {
        c.drawColor(Color.BLACK)
        val (title, detail) = when {
            !liveMirror -> "Your phone's screen shows here" to "In the car, while parked. Start mirroring below."
            !com.dashdeck.mirror.Mirror.running.value -> "Phone mirroring is off" to "Start it from the DashDeck app on your phone"
            t.isDriving(now) -> "Paused while driving" to "It comes back when you stop"
            else -> {
                val rect = com.dashdeck.mirror.Mirror.draw(c, bounds)
                if (rect != null) {
                    mirrorRect = rect
                    hits += rect to Hit.Mirror
                    return
                }
                "Waiting for the phone screen…" to "Unlock the phone and open the app you want"
            }
        }
        drawCentered(c, RectF(bounds.left, bounds.centerY() - 28f * d, bounds.right, bounds.centerY()), title, 22f * d)
        subPaint.color = LABEL
        subPaint.textSize = 14f * d
        subPaint.textAlign = Paint.Align.CENTER
        c.drawText(detail, bounds.centerX(), bounds.centerY() + 22f * d, subPaint)
        subPaint.textAlign = Paint.Align.LEFT
    }

    // endregion

    // region Video

    /**
     * YouTube, full screen, with big buttons down the left: Android Auto gives apps taps but no
     * dragging, so scrolling and going back need buttons of their own.
     */
    private fun drawVideoPage(c: Canvas, bounds: RectF, d: Float, t: Telemetry, now: Long, accent: Int) {
        c.drawColor(Color.BLACK)
        val gap = GridLayout.GAP_DP * d
        val size = GridLayout.BUTTON_DP * d
        val bar = RectF(bounds.left + gap, bounds.top + gap, bounds.left + gap + size, bounds.bottom - gap)
        val content = RectF(bar.right + gap, bounds.top, bounds.right, bounds.bottom)
        val driving = t.isDriving(now)

        val buttons = buildList {
            add(R.drawable.ic_deck to Hit.Menu)
            if (!driving) {
                add(R.drawable.ic_search to Hit.Video.SEARCH)
                add(R.drawable.ic_back to Hit.Video.BACK)
                add(R.drawable.ic_up to Hit.Video.UP)
                add(R.drawable.ic_down to Hit.Video.DOWN)
            }
        }
        val step = min(size + gap, (bar.height() + gap) / buttons.size)
        buttons.forEachIndexed { i, (icon, hit) ->
            val rect = RectF(bar.left, bar.top + i * step, bar.right, bar.top + i * step + min(size, step - gap))
            fillPaint.color = if (hit == Hit.Menu) accent else BUTTON
            c.drawRoundRect(rect, rect.width() / 2, rect.width() / 2, fillPaint)
            drawIcon(c, icon, rect, 0.5f, if (hit == Hit.Menu) Color.BLACK else TEXT)
            hits += rect to hit
        }

        val (title, detail) = when {
            !liveMirror -> "YouTube shows here" to "In the car, while parked"
            driving -> "Video hidden while driving" to "The sound keeps playing"
            !com.dashdeck.video.VideoBrowser.isOpen -> "Opening YouTube…" to ""
            else -> {
                val rect = com.dashdeck.video.VideoBrowser.draw(c, content)
                if (rect != null) {
                    mirrorRect = rect
                    hits += rect to Hit.VideoScreen
                    return
                }
                "Loading YouTube…" to "Needs an internet connection"
            }
        }
        drawCentered(c, RectF(content.left, content.centerY() - 28f * d, content.right, content.centerY()), title, 22f * d)
        subPaint.color = LABEL
        subPaint.textSize = 14f * d
        subPaint.textAlign = Paint.Align.CENTER
        c.drawText(detail, content.centerX(), content.centerY() + 22f * d, subPaint)
        subPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * The video that's playing: from the YouTube page if it has one, otherwise the mirrored phone
     * screen, so any app (VancedTube, a browser, a game) works. Tap to pause or play.
     */
    private fun drawVideoTile(c: Canvas, body: RectF, t: Telemetry, now: Long, d: Float) {
        val browser = com.dashdeck.video.VideoBrowser
        val mirror = com.dashdeck.mirror.Mirror
        val message = when {
            !liveMirror -> "Plays the video on your phone, while parked"
            t.isDriving(now) -> "Hidden while driving"
            else -> null
        }
        if (message == null) {
            val fromBrowser = browser.isOpen && browser.videoRect != null
            val rect = when {
                fromBrowser -> browser.draw(c, body, videoOnly = true)
                mirror.running.value -> mirror.draw(c, body)
                else -> null
            }
            if (rect != null) {
                hits += body to Hit.VideoTile
                val paused = if (fromBrowser) !browser.playing else t.nowPlaying?.playing == false
                if (paused) {
                    fillPaint.color = SHADOW
                    val r = min(body.width(), body.height()) * 0.18f
                    c.drawCircle(body.centerX(), body.centerY(), r, fillPaint)
                    drawIcon(c, R.drawable.ic_play, RectF(body.centerX() - r, body.centerY() - r, body.centerX() + r, body.centerY() + r), 0.7f, TEXT)
                }
                return
            }
        }
        drawMessage(c, body, d, message ?: "Start mirroring in the phone app, then play a video")
    }

    // endregion

    // region Background

    /** Wallpaper plus a dimming veil, cached because it only changes when the setting does. */
    private fun background(width: Int, height: Int, config: DashConfig): Bitmap {
        val key = listOf(width, height, config.wallpaper, config.wallpaperRev)
        background?.takeIf { key == backgroundKey }?.let { return it }

        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val own = config.wallpaper == Wallpaper.PHOTO || config.wallpaper == Wallpaper.VIDEO
        val photo = if (own) WallpaperStore.load(context, max(width, height)) else null
        if (photo != null) {
            val scale = max(width / photo.width.toFloat(), height / photo.height.toFloat())
            val w = photo.width * scale
            val h = photo.height * scale
            canvas.drawBitmap(photo, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
        } else {
            Scenery.draw(canvas, width.toFloat(), height.toFloat(), config.wallpaper)
        }
        if (config.wallpaper != Wallpaper.PLAIN) canvas.drawColor(VEIL)

        background?.recycle()
        background = bitmap
        backgroundKey = key
        return bitmap
    }

    // endregion

    // region Tiles

    private fun drawTile(c: Canvas, tile: Tile, rect: RectF, d: Float, cfg: DashConfig, t: Telemetry, now: Long) {
        if (tile.type == WidgetType.CLOCK) {
            drawClock(c, rect, d, now)
            return
        }
        val accent = cfg.accent.toInt()
        val units = cfg.units
        val radius = 16f * d
        panelPaint.color = Color.argb(cfg.panelOpacity.coerceIn(20, 100) * 255 / 100, 14, 17, 23)
        c.drawRoundRect(rect, radius, radius, panelPaint)
        borderPaint.color = 0x1FFFFFFF
        borderPaint.strokeWidth = d
        c.drawRoundRect(rect, radius, radius, borderPaint)
        if (tile.type == WidgetType.MAP) {
            drawMap(c, rect, radius, d, t, now, accent)
            c.drawRoundRect(rect, radius, radius, borderPaint)
            return
        }

        val pad = (min(rect.width(), rect.height()) * 0.09f).coerceIn(8f * d, 18f * d)
        val inner = RectF(rect).apply { inset(pad, pad) }
        val label = tile.label.ifBlank { defaultLabel(tile.type, t) }
        val body = if (label.isEmpty()) {
            inner
        } else {
            val labelSize = (inner.height() * 0.14f).coerceIn(10f * d, 16f * d)
            labelPaint.textSize = labelSize
            labelPaint.color = LABEL
            labelPaint.textAlign = Paint.Align.LEFT
            c.drawText(ellipsize(label, labelPaint, inner.width()), inner.left, inner.top + labelSize, labelPaint)
            RectF(inner.left, inner.top + labelSize * 1.5f, inner.right, inner.bottom)
        }
        if (body.width() <= 0f || body.height() <= 0f) return

        when (tile.type) {
            WidgetType.CLOCK, WidgetType.MAP -> Unit
            WidgetType.SPEED -> drawSpeed(c, body, t.currentSpeed(now), units, accent)
            WidgetType.COMPASS -> drawCompass(c, body, t.headingDeg, accent)
            WidgetType.SHORTCUTS -> drawShortcuts(c, body, cfg.shortcuts, d)
            WidgetType.NOW_PLAYING -> drawNowPlaying(c, body, t, d, accent)
            WidgetType.VIDEO -> drawVideoTile(c, body, t, now, d)
            WidgetType.LYRICS -> drawLyrics(c, body, t, d, accent)
            WidgetType.TRIP_DISTANCE -> drawValue(c, body, Fmt.distance(t.tripDistanceM, units), Fmt.distanceUnit(units))
            WidgetType.TRIP_TIME -> drawValue(
                c, body, Fmt.duration(t.tripElapsedMillis(now)), "",
                sub = if (t.tripStartMillis == null) "Starts when you drive" else null,
            )
            WidgetType.AVG_SPEED -> drawValue(c, body, Fmt.speed(t.averageSpeedMps(now), units), Fmt.speedUnit(units))
            WidgetType.MAX_SPEED -> drawValue(c, body, Fmt.speed(t.maxSpeedMps, units), Fmt.speedUnit(units))
            WidgetType.ALTITUDE -> drawValue(c, body, Fmt.length(t.altitudeM, units), Fmt.lengthUnit(units))
            WidgetType.PHONE_BATTERY -> {
                val pct = t.phoneBatteryPct
                drawLevel(c, body, pct?.div(100f), if (t.phoneCharging) "Charging" else null, levelColor(pct))
            }
            WidgetType.ENERGY -> drawLevel(
                c, body, t.energyPct?.div(100f),
                t.rangeM?.let { Fmt.distance(it.toDouble(), units, 0) + " " + Fmt.distanceUnit(units) + " left" }
                    ?: NOT_SHARED.takeIf { t.energyShared == false && t.energyPct == null },
                if (t.energyLow) BAD else GOOD,
            )
            WidgetType.RANGE -> drawValue(
                c, body, Fmt.distance(t.rangeM?.toDouble(), units, 0), Fmt.distanceUnit(units),
                NOT_SHARED.takeIf { t.energyShared == false && t.rangeM == null },
            )
            WidgetType.ODOMETER -> drawValue(
                c, body, Fmt.distance(t.odometerM?.toDouble(), units, 0), Fmt.distanceUnit(units),
                NOT_SHARED.takeIf { t.mileageShared == false && t.odometerM == null },
            )
            WidgetType.GPS -> {
                val fix = t.hasFix(now)
                drawValue(
                    c, body,
                    value = if (fix) "±" + Fmt.length(t.accuracyM?.toDouble(), units) else "--",
                    unit = if (fix) Fmt.lengthUnit(units) else "",
                    sub = if (fix) "Locked" else "Searching…",
                    color = if (fix) GOOD else TEXT,
                )
            }
            WidgetType.NOTE -> drawNote(c, body, tile.text.ifBlank { "—" })
        }
    }

    private fun defaultLabel(type: WidgetType, t: Telemetry) = when (type) {
        WidgetType.ENERGY -> if (t.energyIsFuel) "Fuel" else "Charge"
        WidgetType.NOTE, WidgetType.SHORTCUTS, WidgetType.NOW_PLAYING, WidgetType.VIDEO -> ""
        WidgetType.LYRICS -> t.nowPlaying?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" · ") } ?: type.label
        else -> type.label
    }

    /** The clock floats on the wallpaper with no panel, like a phone lock screen. */
    private fun drawClock(c: Canvas, rect: RectF, d: Float, now: Long) {
        val date = Date(now)
        val time = if (is24Hour) time24.format(date) else time12.format(date)
        val suffix = if (is24Hour) "" else " " + amPm.format(date)
        val dateText = dateFormat.format(date)
        val pad = 8f * d

        var size = rect.height() * 0.62f
        clockPaint.textSize = size
        val width = clockPaint.measureText(time + suffix)
        if (width > rect.width() - pad * 2) size *= (rect.width() - pad * 2) / width
        val dateSize = (size * 0.2f).coerceAtLeast(10f * d)

        val capHeight = size * 0.72f
        val block = capHeight + dateSize * 1.8f
        val baseline = rect.top + (rect.height() - block) / 2 + capHeight

        clockPaint.color = TEXT
        clockPaint.textSize = size
        clockPaint.setShadowLayer(10f * d, 0f, 2f * d, SHADOW)
        c.drawText(time, rect.left + pad, baseline, clockPaint)
        if (suffix.isNotEmpty()) {
            val x = rect.left + pad + clockPaint.measureText(time)
            clockPaint.textSize = size * 0.3f
            c.drawText(suffix, x, baseline, clockPaint)
        }
        clockPaint.textSize = dateSize
        c.drawText(ellipsize(dateText, clockPaint, rect.width() - pad * 2), rect.left + pad, baseline + dateSize * 1.8f, clockPaint)
        clockPaint.clearShadowLayer()
    }

    /** Big left-aligned number with an optional unit suffix and a secondary line beneath. */
    private fun drawValue(c: Canvas, body: RectF, value: String, unit: String, sub: String? = null, color: Int = TEXT) {
        // Space for the secondary line is always reserved so neighbouring tiles share a digit size.
        val subSize = min(body.height() * 0.16f, body.width() * 0.12f)
        val subBlock = subSize * 1.5f
        var size = (body.height() - subBlock) * 0.95f
        val unitGap = { s: Float -> if (unit.isEmpty()) 0f else s * 0.1f }

        fun measure(): Float {
            valuePaint.textSize = size
            unitPaint.textSize = size * 0.38f
            return valuePaint.measureText(value) + unitGap(size) + if (unit.isEmpty()) 0f else unitPaint.measureText(unit)
        }

        val width = measure()
        if (width > body.width()) {
            size *= body.width() / width
            measure()
        }

        val capHeight = size * 0.72f
        val baseline = body.top + (body.height() - capHeight - if (sub != null) subBlock else 0f) / 2f + capHeight
        valuePaint.color = color
        valuePaint.textAlign = Paint.Align.LEFT
        c.drawText(value, body.left, baseline, valuePaint)
        if (unit.isNotEmpty()) {
            unitPaint.color = LABEL
            unitPaint.textAlign = Paint.Align.LEFT
            c.drawText(unit, body.left + valuePaint.measureText(value) + unitGap(size), baseline, unitPaint)
        }
        if (sub != null) {
            subPaint.textSize = subSize
            subPaint.color = LABEL
            c.drawText(ellipsize(sub, subPaint, body.width()), body.left, baseline + subBlock, subPaint)
        }
    }

    /** Percentage with a level bar along the bottom. */
    private fun drawLevel(c: Canvas, body: RectF, fraction: Float?, sub: String?, color: Int) {
        val barHeight = (body.height() * 0.1f).coerceAtLeast(4f)
        val textBody = RectF(body.left, body.top, body.right, body.bottom - barHeight * 1.8f)
        drawValue(c, textBody, fraction?.let { (it * 100).roundToInt().toString() } ?: "--", "%", sub)

        scratch.set(body.left, body.bottom - barHeight, body.right, body.bottom)
        fillPaint.color = TRACK
        c.drawRoundRect(scratch, barHeight / 2, barHeight / 2, fillPaint)
        if (fraction != null) {
            scratch.right = body.left + body.width() * fraction.coerceIn(0f, 1f)
            fillPaint.color = color
            c.drawRoundRect(scratch, barHeight / 2, barHeight / 2, fillPaint)
        }
    }

    /** 240° arc gauge with the speed in the middle. */
    private fun drawSpeed(c: Canvas, body: RectF, mps: Float?, units: Units, accent: Int) {
        val max = if (units == Units.METRIC) 200f else 130f
        val size = min(body.width(), body.height() * 1.15f)
        val stroke = size * 0.07f
        val r = size / 2 - stroke / 2
        val cx = body.centerX()
        val cy = body.top + stroke / 2 + r
        scratch.set(cx - r, cy - r, cx + r, cy + r)

        strokePaint.strokeWidth = stroke
        strokePaint.color = TRACK
        c.drawArc(scratch, 150f, 240f, false, strokePaint)
        val value = mps?.let { Fmt.speedValue(it, units) }
        if (value != null && value > 0.5f) {
            strokePaint.color = accent
            c.drawArc(scratch, 150f, 240f * (value / max).coerceIn(0f, 1f), false, strokePaint)
        }

        val text = Fmt.speed(mps, units)
        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.color = TEXT
        valuePaint.textSize = r * 0.8f
        val w = valuePaint.measureText(text)
        if (w > r * 1.4f) valuePaint.textSize *= r * 1.4f / w
        c.drawText(text, cx, cy + valuePaint.textSize * 0.3f, valuePaint)

        unitPaint.textAlign = Paint.Align.CENTER
        unitPaint.color = LABEL
        unitPaint.textSize = r * 0.22f
        c.drawText(Fmt.speedUnit(units), cx, cy + r * 0.62f, unitPaint)
        valuePaint.textAlign = Paint.Align.LEFT
        unitPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * Street map around the car, edge to edge in the panel. While driving it turns so the road ahead
     * points up and zooms out as speed rises; parked, it's north-up. The car is centred in [focus],
     * which on a map page is the part of the screen the widgets leave clear.
     */
    private fun drawMap(
        c: Canvas, rect: RectF, radius: Float, d: Float, t: Telemetry, now: Long, accent: Int, focus: RectF = rect,
    ) {
        c.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        c.drawColor(MAP_BACKGROUND)
        c.restore()
        val lat = t.latitude
        val lon = t.longitude
        if (lat == null || lon == null) {
            drawMessage(c, RectF(focus).apply { inset(12f * d, 12f * d) }, d, "Waiting for GPS…")
            return
        }
        val kmh = (t.currentSpeed(now) ?: 0f) * 3.6f
        val zoom = when {
            kmh < 50f -> 16
            kmh < 90f -> 15
            else -> 14
        }
        val headingUp = t.headingDeg?.takeIf { kmh >= HEADING_UP_KMH }
        val tileSize = 256f * d * MAP_SCALE
        val world = 1 shl zoom
        // Web Mercator, in screen pixels at this zoom.
        val px = (lon + 180.0) / 360.0 * world * tileSize
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val py = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * world * tileSize
        // Heading-up puts the car low in the panel so more of the road ahead shows.
        val ax = focus.centerX()
        val ay = if (headingUp != null) focus.top + focus.height() * 0.7f else focus.centerY()

        c.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        if (headingUp != null) c.rotate(-headingUp, ax, ay)
        val reach = hypot(rect.width(), rect.height()) // covers the panel at any rotation
        val firstX = floor((px - reach) / tileSize).toInt()
        val lastX = floor((px + reach) / tileSize).toInt()
        val firstY = floor((py - reach) / tileSize).toInt().coerceAtLeast(0)
        val lastY = floor((py + reach) / tileSize).toInt().coerceAtMost(world - 1)
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val left = ax + (tx * tileSize - px).toFloat()
                val top = ay + (ty * tileSize - py).toFloat()
                scratch.set(left, top, left + tileSize, top + tileSize)
                val wx = ((tx % world) + world) % world
                val tile = MapTiles.get(context, zoom, wx, ty)
                if (tile != null) {
                    c.drawBitmap(tile, null, scratch, tilePaint)
                } else {
                    // Until it arrives, stretch the matching quarter of the tile one zoom out.
                    val parent = MapTiles.cached(zoom - 1, wx / 2, ty / 2) ?: continue
                    val half = parent.width / 2
                    tileSource.set((wx % 2) * half, (ty % 2) * half, (wx % 2) * half + half, (ty % 2) * half + half)
                    c.drawBitmap(parent, tileSource, scratch, tilePaint)
                }
            }
        }
        t.route?.let { drawRoute(c, it, world * tileSize.toDouble(), px, py, ax, ay, reach, d) }
        c.restore()

        // The car: an arrow along the direction of travel, or a dot when that isn't known.
        val fresh = t.hasFix(now)
        val s = 13f * d
        fillPaint.color = if (fresh) accent else LABEL
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2.5f * d
        val travel = headingUp?.let { 0f } ?: t.headingDeg
        if (travel == null) {
            c.drawCircle(ax, ay, s * 0.6f, fillPaint)
            c.drawCircle(ax, ay, s * 0.6f, strokePaint)
        } else {
            c.save()
            c.rotate(travel, ax, ay)
            path.reset()
            path.moveTo(ax, ay - s)
            path.lineTo(ax + s * 0.75f, ay + s * 0.8f)
            path.lineTo(ax, ay + s * 0.4f)
            path.lineTo(ax - s * 0.75f, ay + s * 0.8f)
            path.close()
            c.drawPath(path, fillPaint)
            strokePaint.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, strokePaint)
            c.restore()
        }

        // North pointer while the map turns with the car.
        if (headingUp != null) {
            val r = 13f * d
            val cx = focus.right - r - 10f * d
            val cy = focus.top + r + 10f * d
            fillPaint.color = SHADOW
            c.drawCircle(cx, cy, r, fillPaint)
            c.save()
            c.rotate(-headingUp, cx, cy)
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.textSize = r * 0.95f
            labelPaint.color = accent
            c.drawText("N", cx, cy + r * 0.33f, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            c.restore()
        }

        // Required credit for the map data and tiles.
        subPaint.textSize = 9f * d
        subPaint.color = LABEL
        subPaint.textAlign = Paint.Align.RIGHT
        c.drawText(MapTiles.ATTRIBUTION, focus.right - 8f * d, focus.bottom - 6f * d, subPaint)
        subPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * The rest of the route, from the car on, over the map already placed (and turned) by [drawMap]:
     * [worldPx] is the world's width in pixels and ([px], [py]) the car's spot in it, drawn at
     * ([ax], [ay]). Parts far off screen are skipped.
     */
    private fun drawRoute(
        c: Canvas, route: RouteState, worldPx: Double, px: Double, py: Double, ax: Float, ay: Float, reach: Float, d: Float,
    ) {
        val line = route.path ?: return
        path.reset()
        // On the line, start it at the car so there's no gap under the arrow.
        var started = route.offRouteM < ROUTE_SNAP_M
        if (started) path.moveTo(ax, ay)
        var wasFar = false
        var endX = ax
        var endY = ay
        val limit = reach * 1.5f
        for (i in route.passedIndex + 1 until line.mercX.size) {
            val x = ax + (line.mercX[i] * worldPx - px).toFloat()
            val y = ay + (line.mercY[i] * worldPx - py).toFloat()
            val far = abs(x - ax) > limit || abs(y - ay) > limit
            if (!started || (far && wasFar)) path.moveTo(x, y) else path.lineTo(x, y)
            started = true
            wasFar = far
            endX = x
            endY = y
        }
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = ROUTE_EDGE
        strokePaint.strokeWidth = 10f * d
        c.drawPath(path, strokePaint)
        strokePaint.color = ROUTE
        strokePaint.strokeWidth = 6.5f * d
        c.drawPath(path, strokePaint)
        // The destination.
        fillPaint.color = ROUTE
        c.drawCircle(endX, endY, 8f * d, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2.5f * d
        c.drawCircle(endX, endY, 8f * d, strokePaint)
    }

    /**
     * The next turn in a card at the top of [focus] and the arrival time along the bottom, like a
     * navigation app; or why there's no route, briefly.
     */
    private fun drawRouteCard(c: Canvas, focus: RectF, d: Float, units: Units, t: Telemetry, now: Long, avoid: RectF? = null) {
        val route = t.route ?: return
        val pad = 12f * d
        // Beside the menu button when the area reaches it.
        val left = if (avoid != null && avoid.right > focus.left && avoid.top < focus.top + 90f * d) avoid.right + pad else focus.left + pad
        val card = RectF(left, focus.top + pad, left + min(focus.right - left - pad, 360f * d), focus.top + pad + 76f * d)
        fillPaint.color = ROUTE_CARD
        c.drawRoundRect(card, 14f * d, 14f * d, fillPaint)

        val message = when (route.status) {
            RouteState.Status.FINDING -> "Finding the route to ${route.destination}…"
            RouteState.Status.NEEDS_ADDRESS -> "Add ${route.destination}'s address in the DashDeck app to see the route here"
            RouteState.Status.NOT_FOUND -> "Couldn't find ${route.destination}. Check its address in the DashDeck app"
            RouteState.Status.FAILED -> "No route to ${route.destination} right now"
            RouteState.Status.ARRIVED -> "You've arrived at ${route.destination}"
            RouteState.Status.ACTIVE -> null
        }
        if (message != null) {
            val body = RectF(card).apply { inset(14f * d, 8f * d) }
            drawMessage(c, body, d, message)
            return
        }

        val turn = route.nextTurn
        val icon = card.height() * 0.32f
        drawTurnArrow(c, card.left + card.height() / 2, card.centerY(), icon, turn?.angle, d)
        val textLeft = card.left + card.height()
        val textWidth = card.right - textLeft - 12f * d
        valuePaint.color = TEXT
        valuePaint.textSize = 26f * d
        val distance = turnDistance(route.nextTurnM, units)
        c.drawText(distance, textLeft, card.top + 34f * d, valuePaint)
        val street = turn?.street?.ifBlank { null }
        if (turn != null && street != null) {
            labelPaint.color = LABEL
            labelPaint.textSize = 14f * d
            val after = textLeft + valuePaint.measureText(distance) + 10f * d
            c.drawText(ellipsize(turn.instruction, labelPaint, card.right - after - 12f * d), after, card.top + 34f * d, labelPaint)
        }
        labelPaint.color = ROUTE
        labelPaint.textSize = 18f * d
        c.drawText(ellipsize(street ?: turn?.instruction ?: route.destination, labelPaint, textWidth), textLeft, card.top + 62f * d, labelPaint)

        // Arrival time, time left and distance left.
        val arrival = Date(now + (route.remainingS * 1000).toLong())
        val clock = if (is24Hour) time24.format(arrival) else time12.format(arrival) + " " + amPm.format(arrival)
        val minutes = (route.remainingS / 60).roundToInt()
        val timeLeft = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        val far = Fmt.distance(route.remainingM, units, if (route.remainingM >= 10_000) 0 else 1) + " " + Fmt.distanceUnit(units)
        val text = "$clock  ·  $timeLeft  ·  $far"
        labelPaint.color = TEXT
        labelPaint.textSize = 16f * d
        val width = labelPaint.measureText(text) + 28f * d
        val bar = RectF(focus.centerX() - width / 2, focus.bottom - 52f * d, focus.centerX() + width / 2, focus.bottom - 20f * d)
        fillPaint.color = ROUTE_CARD
        c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, fillPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        c.drawText(text, bar.centerX(), bar.centerY() + 5.5f * d, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }

    /** A bent arrow for a turn ([angle] from straight ahead), or a pin for the destination. */
    private fun drawTurnArrow(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float?, d: Float) {
        strokePaint.color = TEXT
        fillPaint.color = TEXT
        if (angle == null) {
            c.drawCircle(cx, cy - r * 0.2f, r * 0.55f, fillPaint)
            fillPaint.color = ROUTE_CARD
            c.drawCircle(cx, cy - r * 0.2f, r * 0.22f, fillPaint)
            fillPaint.color = TEXT
            path.reset()
            path.moveTo(cx - r * 0.42f, cy + r * 0.1f)
            path.lineTo(cx, cy + r)
            path.lineTo(cx + r * 0.42f, cy + r * 0.1f)
            path.close()
            c.drawPath(path, fillPaint)
            return
        }
        strokePaint.strokeWidth = r * 0.3f
        strokePaint.strokeJoin = Paint.Join.ROUND
        val rad = Math.toRadians(angle.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val uTurn = abs(angle) >= 170f
        // Stem up from the bottom, then off in the new direction (a U-turn hooks back down on the left).
        val stemX = if (uTurn) cx + r * 0.4f else cx
        val bendY = if (uTurn) cy - r * 0.45f else if (angle == 0f) cy - r * 0.35f else cy
        path.reset()
        path.moveTo(stemX, cy + r)
        path.lineTo(stemX, bendY)
        val tipX: Float
        val tipY: Float
        val headX: Float
        val headY: Float
        if (uTurn) {
            path.quadTo(stemX, cy - r, cx, cy - r)
            path.quadTo(cx - r * 0.4f, cy - r, cx - r * 0.4f, bendY)
            path.lineTo(cx - r * 0.4f, cy + r * 0.1f)
            tipX = cx - r * 0.4f
            tipY = cy + r * 0.1f
            headX = 0f
            headY = 1f
        } else {
            tipX = stemX + dx * r * 0.75f
            tipY = bendY + dy * r * 0.75f
            if (angle != 0f) path.lineTo(tipX, tipY)
            headX = dx
            headY = dy
        }
        c.drawPath(path, strokePaint)
        // Arrowhead at the tip, pointing the way to go.
        val h = r * 0.5f
        path.reset()
        path.moveTo(tipX + headX * h, tipY + headY * h)
        path.lineTo(tipX - headY * h * 0.8f, tipY + headX * h * 0.8f)
        path.lineTo(tipX + headY * h * 0.8f, tipY - headX * h * 0.8f)
        path.close()
        c.drawPath(path, fillPaint)
    }

    /** "90 m", "1.2 km": rounded the way navigation apps announce turns. */
    private fun turnDistance(meters: Double, units: Units): String = if (units == Units.METRIC) {
        if (meters < 1000) "${((meters / 10).roundToInt() * 10).coerceAtLeast(10)} m"
        else String.format(Locale.getDefault(), "%.1f km", meters / 1000)
    } else {
        val miles = meters / 1609.344
        if (miles < 0.1) "${((meters * 3.28084 / 50).roundToInt() * 50).coerceAtLeast(50)} ft"
        else String.format(Locale.getDefault(), "%.1f mi", miles)
    }

    /** Rotating compass card with a fixed pointer; wide tiles get a readout beside the dial. */
    private fun drawCompass(c: Canvas, body: RectF, heading: Float?, accent: Int) {
        val wide = body.width() > body.height() * 1.6f
        val r = (if (wide) body.height() else min(body.width(), body.height())) / 2f
        val cx = if (wide) body.left + r else body.centerX()
        val cy = body.centerY()
        val ring = r * 0.9f

        strokePaint.color = TRACK
        strokePaint.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, ring, strokePaint)

        c.save()
        c.rotate(-(heading ?: 0f), cx, cy)
        for (i in 0 until 36) {
            val major = i % 9 == 0
            strokePaint.strokeWidth = if (major) r * 0.04f else r * 0.02f
            strokePaint.color = if (major) TEXT else LABEL
            c.drawLine(cx, cy - ring, cx, cy - ring + if (major) r * 0.14f else r * 0.07f, strokePaint)
            c.rotate(10f, cx, cy)
        }
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = r * 0.2f
        listOf("N", "E", "S", "W").forEachIndexed { i, letter ->
            c.save()
            c.rotate(i * 90f, cx, cy)
            labelPaint.color = if (i == 0) accent else LABEL
            c.drawText(letter, cx, cy - r * 0.52f, labelPaint)
            c.restore()
        }
        labelPaint.textAlign = Paint.Align.LEFT
        c.restore()

        path.reset()
        path.moveTo(cx - r * 0.1f, cy - r)
        path.lineTo(cx + r * 0.1f, cy - r)
        path.lineTo(cx, cy - r * 0.8f)
        path.close()
        fillPaint.color = accent
        c.drawPath(path, fillPaint)

        val cardinal = heading?.let(Fmt::cardinal) ?: "--"
        val degrees = heading?.let { "${((it % 360 + 360) % 360).roundToInt()}°" } ?: ""
        if (wide) {
            drawValue(c, RectF(cx + r * 1.3f, body.top, body.right, body.bottom), cardinal, degrees)
        } else {
            valuePaint.textAlign = Paint.Align.CENTER
            valuePaint.color = TEXT
            valuePaint.textSize = r * 0.32f
            c.drawText(cardinal, cx, cy + r * 0.08f, valuePaint)
            unitPaint.textAlign = Paint.Align.CENTER
            unitPaint.color = LABEL
            unitPaint.textSize = r * 0.16f
            c.drawText(degrees, cx, cy + r * 0.3f, unitPaint)
            valuePaint.textAlign = Paint.Align.LEFT
            unitPaint.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * Tappable shortcut buttons: round icons with names beneath when the widget is tall enough,
     * otherwise labelled pills so short "Bar" widgets still say what each button does.
     */
    private fun drawShortcuts(c: Canvas, body: RectF, shortcuts: List<Shortcut>, d: Float) {
        if (shortcuts.isEmpty()) {
            drawCentered(c, body, "Add shortcuts in the phone app", min(body.height() * 0.25f, 14f * d))
            return
        }
        val gap = 12f * d
        val nameSize = (body.height() * 0.16f).coerceIn(10f * d, 14f * d)
        subPaint.textSize = nameSize
        subPaint.color = TEXT
        if (body.height() < 70f * d) {
            drawShortcutPills(c, body, shortcuts, d, gap)
            return
        }

        val maxDiameter = min(72f * d, body.height() - nameSize * 1.8f)
        val count = ((body.width() + gap) / (min(maxDiameter, 40f * d) + gap)).toInt().coerceIn(1, shortcuts.size)
        val diameter = min(maxDiameter, (body.width() - gap * (count - 1)) / count)
        val rowWidth = count * diameter + (count - 1) * gap
        var x = body.centerX() - rowWidth / 2
        val top = body.centerY() - (diameter + nameSize * 1.8f) / 2

        subPaint.textAlign = Paint.Align.CENTER
        for (shortcut in shortcuts.take(count)) {
            val circle = RectF(x, top, x + diameter, top + diameter)
            fillPaint.color = BUTTON
            c.drawOval(circle, fillPaint)
            drawIcon(c, iconFor(shortcut.kind), circle, 0.5f, TEXT)
            c.drawText(
                ellipsize(shortcut.name, subPaint, diameter + gap * 0.8f),
                circle.centerX(), circle.bottom + nameSize * 1.5f, subPaint,
            )
            hits += RectF(circle).apply { inset(-gap / 2, -gap / 2) } to Hit.RunShortcut(shortcut)
            x += diameter + gap
        }
        subPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawShortcutPills(c: Canvas, body: RectF, shortcuts: List<Shortcut>, d: Float, gap: Float) {
        val height = min(body.height(), 48f * d)
        val iconSize = height * 0.5f
        val pad = height * 0.35f
        subPaint.textSize = height * 0.36f
        val maxText = 140f * d
        // A few pixels of slack, or ellipsize trims names that fit exactly.
        val widths = shortcuts.map { pad * 2 + iconSize + pad * 0.6f + min(subPaint.measureText(it.name) + 4f, maxText) }
        var count = shortcuts.size
        while (count > 1 && widths.take(count).sum() + gap * (count - 1) > body.width()) count--
        val rowWidth = min(body.width(), widths.take(count).sum() + gap * (count - 1))
        var x = body.centerX() - rowWidth / 2
        val top = body.centerY() - height / 2

        for ((i, shortcut) in shortcuts.take(count).withIndex()) {
            val pill = RectF(x, top, min(x + widths[i], body.right), top + height)
            fillPaint.color = BUTTON
            c.drawRoundRect(pill, height / 2, height / 2, fillPaint)
            val icon = RectF(pill.left + pad, pill.centerY() - iconSize / 2, pill.left + pad + iconSize, pill.centerY() + iconSize / 2)
            drawIcon(c, iconFor(shortcut.kind), icon, 1f, TEXT)
            val textLeft = icon.right + pad * 0.6f
            c.drawText(
                ellipsize(shortcut.name, subPaint, pill.right - pad - textLeft),
                textLeft, pill.centerY() + subPaint.textSize / 3, subPaint,
            )
            hits += RectF(pill) to Hit.RunShortcut(shortcut)
            x += widths[i] + gap
        }
    }

    /** Artwork, title and artist, a progress bar, and previous / play-pause / next buttons. */
    private fun drawNowPlaying(c: Canvas, body: RectF, t: Telemetry, d: Float, accent: Int) {
        val track = t.nowPlaying ?: run {
            drawMessage(c, body, d, if (t.mediaAccess) "Nothing playing" else "Allow notification access in the DashDeck phone app")
            return
        }
        val gap = 12f * d
        val artSize = min(body.height(), body.width() * 0.4f)
        val art = RectF(body.left, body.centerY() - artSize / 2, body.left + artSize, body.centerY() + artSize / 2)
        drawArt(c, art, track.art, d)

        val text = RectF(art.right + gap, body.top, body.right, body.bottom)
        if (text.width() <= 0) return
        val controls = text.height() >= 90f * d
        val titleSize = (text.height() * if (controls) 0.16f else 0.24f).coerceIn(12f * d, 26f * d)
        val artistSize = titleSize * 0.75f
        val buttonSize = if (controls) min(48f * d, text.height() * 0.34f) else 0f
        val barHeight = 4f * d
        val block = titleSize + artistSize * 1.5f + barHeight + titleSize * 0.8f + if (controls) buttonSize + gap else 0f
        var y = text.top + (text.height() - block) / 2 + titleSize

        subPaint.textSize = titleSize
        subPaint.color = TEXT
        subPaint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(ellipsize(track.title.ifBlank { "Unknown song" }, subPaint, text.width()), text.left, y, subPaint)
        subPaint.typeface = Typeface.DEFAULT
        y += artistSize * 1.5f
        subPaint.textSize = artistSize
        subPaint.color = LABEL
        c.drawText(ellipsize(track.artist, subPaint, text.width()), text.left, y, subPaint)

        y += titleSize * 0.8f
        scratch.set(text.left, y, text.right, y + barHeight)
        fillPaint.color = TRACK
        c.drawRoundRect(scratch, barHeight / 2, barHeight / 2, fillPaint)
        if (track.durationMs > 0) {
            scratch.right = text.left + text.width() * (track.positionNow().toFloat() / track.durationMs).coerceIn(0f, 1f)
            fillPaint.color = TEXT
            c.drawRoundRect(scratch, barHeight / 2, barHeight / 2, fillPaint)
        }

        if (controls) {
            val top = y + barHeight + gap
            val buttons = listOf(
                Hit.Media.PREVIOUS to R.drawable.ic_skip_previous,
                Hit.Media.PLAY_PAUSE to if (track.playing) R.drawable.ic_pause else R.drawable.ic_play,
                Hit.Media.NEXT to R.drawable.ic_skip_next,
            )
            buttons.forEachIndexed { i, (action, icon) ->
                val left = text.left + i * (buttonSize + gap)
                val circle = RectF(left, top, left + buttonSize, top + buttonSize)
                val main = action == Hit.Media.PLAY_PAUSE
                fillPaint.color = if (main) accent else BUTTON
                c.drawOval(circle, fillPaint)
                drawIcon(c, icon, circle, 0.55f, if (main) 0xFF0B0E12.toInt() else TEXT)
                hits += RectF(circle).apply { inset(-gap / 2, -gap / 2) } to action
            }
        }
    }

    private fun drawArt(c: Canvas, rect: RectF, art: Bitmap?, d: Float) {
        val radius = 10f * d
        c.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        if (art != null && !art.isRecycled) {
            val scale = max(rect.width() / art.width, rect.height() / art.height)
            val w = art.width * scale
            val h = art.height * scale
            c.drawBitmap(art, null, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), artPaint)
        } else {
            fillPaint.color = BUTTON
            c.drawRect(rect, fillPaint)
            drawIcon(c, R.drawable.ic_music, rect, 0.45f, LABEL)
        }
        c.restore()
    }

    /** The line being sung, large; the line before, faded above; the next lines, faded below. */
    private fun drawLyrics(c: Canvas, body: RectF, t: Telemetry, d: Float, accent: Int) {
        val track = t.nowPlaying
        val lyrics = t.lyrics
        val message = when {
            !t.mediaAccess -> "Allow notification access in the DashDeck phone app"
            track == null -> "Play something to see its lyrics"
            lyrics == null || lyrics.status == Lyrics.Status.LOADING -> "Finding lyrics…"
            lyrics.status == Lyrics.Status.INSTRUMENTAL -> "♪ Instrumental"
            lyrics.status == Lyrics.Status.NOT_FOUND -> "No lyrics found for this song"
            lyrics.status == Lyrics.Status.ERROR -> "Lyrics services aren't answering. Trying again next song."
            else -> null
        }
        if (message != null || track == null || lyrics == null) {
            drawMessage(c, body, d, message.orEmpty())
            return
        }

        c.save()
        c.clipRect(body)
        val width = body.width().toInt()
        val big = (body.height() * 0.14f).coerceIn(16f * d, 34f * d)
        val small = big * 0.62f

        if (lyrics.status == Lyrics.Status.PLAIN) {
            notePaint.textSize = small
            notePaint.color = LABEL
            val layout = layout("Not synced to the song\n\n" + lyrics.plain.orEmpty(), width)
            c.translate(body.left, body.top)
            layout.draw(c)
            c.restore()
            return
        }

        val lines = lyrics.lines
        // Show each line a moment early; the eye lags behind the voice.
        val index = lyrics.lineAt(track.positionNow() + LYRIC_LEAD_MS)
        fun text(i: Int) = lines[i].text.ifBlank { "♪" }

        notePaint.textSize = big
        notePaint.color = TEXT
        val current = layout(if (index >= 0) text(index) else "♪", width)
        val currentTop = body.top + body.height() * 0.32f - current.height / 2f

        if (index > 0) {
            notePaint.textSize = small
            notePaint.color = FADED
            val previous = layout(text(index - 1), width)
            c.save()
            c.translate(body.left, currentTop - previous.height - small * 0.6f)
            previous.draw(c)
            c.restore()
        }

        notePaint.textSize = big
        notePaint.color = TEXT
        c.save()
        c.translate(body.left, currentTop)
        current.draw(c)
        c.restore()
        fillPaint.color = accent
        c.drawRect(body.left - 8f * d, currentTop + 2f * d, body.left - 5f * d, currentTop + current.height - 2f * d, fillPaint)

        var y = currentTop + current.height + small * 0.8f
        notePaint.textSize = small
        notePaint.color = FADED
        var i = index + 1
        while (i < lines.size && y < body.bottom) {
            val next = layout(text(i), width)
            c.save()
            c.translate(body.left, y)
            next.draw(c)
            c.restore()
            y += next.height + small * 0.5f
            i++
        }
        c.restore()
    }

    private fun layout(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, notePaint, max(width, 1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

    private fun drawMessage(c: Canvas, body: RectF, d: Float, message: String) {
        notePaint.textSize = min(body.height() * 0.2f, 15f * d)
        notePaint.color = LABEL
        val layout = layout(message, body.width().toInt())
        c.save()
        c.translate(body.left, body.top + (body.height() - layout.height) / 2)
        layout.draw(c)
        c.restore()
    }

    private fun drawNote(c: Canvas, body: RectF, text: String) {
        notePaint.color = TEXT
        var size = body.height() * 0.35f
        var layout: StaticLayout
        do {
            notePaint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, notePaint, body.width().toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            size *= 0.85f
        } while (layout.height > body.height() && size > 6f)
        c.save()
        c.translate(body.left, body.top + (body.height() - layout.height) / 2f)
        layout.draw(c)
        c.restore()
    }

    // endregion

    private fun drawMenuButton(c: Canvas, rect: RectF, d: Float, cfg: DashConfig) {
        val accent = cfg.accent.toInt()
        fillPaint.color = Color.argb(
            cfg.buttonOpacity.coerceIn(20, 100) * 255 / 100, Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        c.drawRoundRect(rect, 14f * d, 14f * d, fillPaint)
        drawIcon(c, R.drawable.ic_deck, rect, 0.5f, 0xFF0B0E12.toInt())
        hits += RectF(rect) to Hit.Menu
    }

    private fun drawIcon(c: Canvas, res: Int, bounds: RectF, scale: Float, color: Int) {
        val icon = ContextCompat.getDrawable(context, res)?.mutate() ?: return
        val half = min(bounds.width(), bounds.height()) * scale / 2
        icon.setBounds(
            (bounds.centerX() - half).toInt(), (bounds.centerY() - half).toInt(),
            (bounds.centerX() + half).toInt(), (bounds.centerY() + half).toInt(),
        )
        icon.setTint(color)
        icon.draw(c)
    }

    private fun drawCentered(c: Canvas, bounds: RectF, text: String, size: Float) {
        subPaint.textSize = size
        subPaint.color = TEXT
        subPaint.textAlign = Paint.Align.CENTER
        c.drawText(ellipsize(text, subPaint, bounds.width()), bounds.centerX(), bounds.centerY() + size / 3, subPaint)
        subPaint.textAlign = Paint.Align.LEFT
    }

    private fun levelColor(pct: Int?) = when {
        pct == null -> TRACK
        pct > 50 -> GOOD
        pct > 20 -> WARN
        else -> BAD
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float) =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    companion object {
        private const val VEIL = 0x40000000
        private const val SHADOW = 0x99000000.toInt()
        private const val TRACK = 0x40FFFFFF
        private const val BUTTON = 0x33FFFFFF
        private const val FADED = 0x80F2F5F8.toInt()
        private const val LYRIC_LEAD_MS = 300L
        private const val NOT_SHARED = "Not shared by your car"
        private const val MAP_BACKGROUND = 0xFF1B1F27.toInt()
        /** Map tiles drawn a little larger than on a phone, for reading at arm's length. */
        private const val MAP_SCALE = 1.2f
        private const val HEADING_UP_KMH = 5f
        /** Waze-like cyan for the route, edged so it stands out on the dark map. */
        private const val ROUTE = 0xFF33CCFF.toInt()
        private const val ROUTE_EDGE = 0xFF0B3D52.toInt()
        private const val ROUTE_CARD = 0xE6101418.toInt()
        /** Closer than this, the line starts right at the car. */
        private const val ROUTE_SNAP_M = 25.0
        private const val TEXT = 0xFFF2F5F8.toInt()
        private const val LABEL = 0xFFAEB6C2.toInt()
        private const val GOOD = 0xFF66BB6A.toInt()
        private const val WARN = 0xFFFFB300.toInt()
        private const val BAD = 0xFFEF5350.toInt()

        fun iconFor(kind: ShortcutKind) = when (kind) {
            ShortcutKind.NAVIGATE -> R.drawable.ic_navigate
            ShortcutKind.DIAL -> R.drawable.ic_dial
            ShortcutKind.WEBHOOK -> R.drawable.ic_webhook
        }
    }
}
