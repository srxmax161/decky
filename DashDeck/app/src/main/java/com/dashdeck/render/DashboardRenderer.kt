package com.dashdeck.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.dashdeck.data.Telemetry
import com.dashdeck.model.DashConfig
import com.dashdeck.model.DashPage
import com.dashdeck.model.Tile
import com.dashdeck.model.Units
import com.dashdeck.model.WidgetType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a dashboard page onto any [Canvas]. Shared by the car surface and the phone preview so
 * what you edit is exactly what you get.
 */
class DashboardRenderer {
    var is24Hour: Boolean = true

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val time24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val time12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val amPm = SimpleDateFormat("a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val path = Path()
    private val scratch = RectF()

    fun draw(
        canvas: Canvas,
        area: Rect,
        density: Float,
        config: DashConfig,
        pageIndex: Int,
        telemetry: Telemetry,
        now: Long,
    ) {
        canvas.drawColor(BACKGROUND)
        val page = config.pages.getOrNull(pageIndex) ?: return
        val gap = 8f * density
        val bounds = RectF(area).apply { inset(gap, gap) }
        if (page.tiles.isEmpty()) {
            drawCentered(canvas, bounds, "Empty page — add tiles in the DashDeck phone app", 16f * density)
            return
        }
        for ((tile, rect) in layout(page, bounds, gap)) {
            drawTile(canvas, tile, rect, density, config, telemetry, now)
        }
    }

    private fun drawTile(c: Canvas, tile: Tile, rect: RectF, d: Float, cfg: DashConfig, t: Telemetry, now: Long) {
        val accent = cfg.accent.toInt()
        val units = cfg.units
        val radius = 12f * d
        cardPaint.color = CARD
        c.drawRoundRect(rect, radius, radius, cardPaint)

        val pad = (min(rect.width(), rect.height()) * 0.08f).coerceAtLeast(6f * d)
        val inner = RectF(rect).apply { inset(pad, pad) }
        val labelSize = (inner.height() * 0.12f).coerceIn(8f * d, 16f * d)
        labelPaint.textSize = labelSize
        labelPaint.color = LABEL
        labelPaint.textAlign = Paint.Align.LEFT
        val label = tile.label.ifBlank { defaultLabel(tile.type, t) }.uppercase(Locale.getDefault())
        c.drawText(ellipsize(label, labelPaint, inner.width()), inner.left, inner.top + labelSize, labelPaint)

        val body = RectF(inner.left, inner.top + labelSize * 1.6f, inner.right, inner.bottom)
        if (body.width() <= 0f || body.height() <= 0f) return

        when (tile.type) {
            WidgetType.SPEED -> drawSpeed(c, body, t.currentSpeed(now), units, accent)
            WidgetType.CLOCK -> {
                val date = Date(now)
                if (is24Hour) drawValue(c, body, time24.format(date), "", dateFormat.format(date))
                else drawValue(c, body, time12.format(date), amPm.format(date), dateFormat.format(date))
            }
            WidgetType.COMPASS -> drawCompass(c, body, t.headingDeg, accent)
            WidgetType.TRIP_DISTANCE -> drawValue(c, body, Fmt.distance(t.tripDistanceM, units), Fmt.distanceUnit(units))
            WidgetType.TRIP_TIME -> drawValue(c, body, Fmt.duration(now - t.tripStartMillis), "")
            WidgetType.AVG_SPEED -> drawValue(c, body, Fmt.speed(t.averageSpeedMps(now), units), Fmt.speedUnit(units))
            WidgetType.MAX_SPEED -> drawValue(c, body, Fmt.speed(t.maxSpeedMps, units), Fmt.speedUnit(units))
            WidgetType.ALTITUDE -> drawValue(c, body, Fmt.length(t.altitudeM, units), Fmt.lengthUnit(units))
            WidgetType.PHONE_BATTERY -> {
                val pct = t.phoneBatteryPct
                drawLevel(c, body, pct?.div(100f), if (t.phoneCharging) "Charging" else null, levelColor(pct))
            }
            WidgetType.ENERGY -> drawLevel(
                c, body, t.energyPct?.div(100f), if (t.energyLow) "Low" else null, if (t.energyLow) BAD else accent,
            )
            WidgetType.RANGE -> drawValue(c, body, Fmt.distance(t.rangeM?.toDouble(), units, 0), Fmt.distanceUnit(units))
            WidgetType.ODOMETER -> drawValue(c, body, Fmt.distance(t.odometerM?.toDouble(), units, 0), Fmt.distanceUnit(units))
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
        WidgetType.NOTE -> ""
        else -> type.label
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
        val barHeight = (body.height() * 0.12f).coerceAtLeast(4f)
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
            val gap = r * 0.3f
            drawValue(c, RectF(cx + r + gap, body.top, body.right, body.bottom), cardinal, degrees)
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

    private fun drawCentered(c: Canvas, bounds: RectF, text: String, size: Float) {
        subPaint.textSize = size
        subPaint.color = LABEL
        subPaint.textAlign = Paint.Align.CENTER
        c.drawText(ellipsize(text, subPaint, bounds.width()), bounds.centerX(), bounds.centerY(), subPaint)
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
        const val BACKGROUND = 0xFF0B0E12.toInt()
        private const val CARD = 0xFF161B22.toInt()
        private const val TRACK = 0xFF2A313B.toInt()
        private const val TEXT = 0xFFF2F5F8.toInt()
        private const val LABEL = 0xFF8B95A3.toInt()
        private const val GOOD = 0xFF66BB6A.toInt()
        private const val WARN = 0xFFFFB300.toInt()
        private const val BAD = 0xFFEF5350.toInt()

        /**
         * Flows tiles into rows of at most `columns` units. Each row is stretched to the full width,
         * so a short last row never leaves a hole.
         */
        fun layout(page: DashPage, bounds: RectF, gap: Float): List<Pair<Tile, RectF>> {
            val columns = page.columns.coerceIn(1, 4)
            val rows = mutableListOf<MutableList<Tile>>()
            var used = columns
            for (tile in page.tiles) {
                val span = tile.span.coerceIn(1, columns)
                if (used + span > columns) {
                    rows += mutableListOf<Tile>()
                    used = 0
                }
                rows.last() += tile
                used += span
            }
            if (rows.isEmpty()) return emptyList()

            val rowHeight = (bounds.height() - gap * (rows.size - 1)) / rows.size
            val result = mutableListOf<Pair<Tile, RectF>>()
            rows.forEachIndexed { r, row ->
                val units = row.sumOf { it.span.coerceIn(1, columns) }
                val unitWidth = (bounds.width() - gap * (row.size - 1)) / units
                val top = bounds.top + r * (rowHeight + gap)
                var left = bounds.left
                for (tile in row) {
                    val width = unitWidth * tile.span.coerceIn(1, columns)
                    result += tile to RectF(left, top, left + width, top + rowHeight)
                    left += width + gap
                }
            }
            return result
        }
    }
}
