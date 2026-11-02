package com.dashdeck.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.dashdeck.model.Wallpaper
import kotlin.random.Random

/**
 * Built-in wallpapers, drawn as flat layered landscapes so they scale to any car screen. Anything
 * round is small and muted on purpose: it sits behind see-through widgets and shouldn't look like
 * part of one.
 */
object Scenery {
    private const val W = 660f
    private const val H = 320f

    private val HILLS = listOf(
        floatArrayOf(0f, 210f, 90f, 150f, 170f, 195f, 260f, 120f, 360f, 190f, 450f, 140f, 560f, 200f, 660f, 160f),
        floatArrayOf(0f, 250f, 120f, 205f, 230f, 245f, 330f, 200f, 440f, 250f, 560f, 215f, 660f, 245f),
        floatArrayOf(0f, 290f, 160f, 260f, 300f, 290f, 460f, 262f, 660f, 292f),
    )

    private class Palette(val sky: Int, val sun: Int, val hills: List<Int>)

    private val DUSK = Palette(0xFF1D2B3F.toInt(), 0xFF8F7458.toInt(), listOf(0xFF2C3D55, 0xFF22324A, 0xFF17243A).map { it.toInt() })
    private val NIGHT = Palette(0xFF0B1020.toInt(), 0xFF8C8B84.toInt(), listOf(0xFF151D33, 0xFF10172A, 0xFF0B1122).map { it.toInt() })
    private val FOREST = Palette(0xFF20393A.toInt(), 0xFF7E7A5E.toInt(), listOf(0xFF2F5B4B, 0xFF24493C, 0xFF193629).map { it.toInt() })

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(canvas: Canvas, width: Float, height: Float, wallpaper: Wallpaper) {
        val sx = width / W
        val sy = height / H
        when (wallpaper) {
            Wallpaper.NIGHT -> {
                landscape(canvas, width, height, sx, sy, NIGHT, moon = true)
                stars(canvas, width, height * 0.55f, sx)
            }
            Wallpaper.FOREST -> landscape(canvas, width, height, sx, sy, FOREST, moon = false)
            Wallpaper.OCEAN -> ocean(canvas, width, height, sx, sy)
            Wallpaper.PLAIN -> canvas.drawColor(0xFF0B0E12.toInt())
            Wallpaper.DUSK, Wallpaper.PHOTO, Wallpaper.VIDEO -> landscape(canvas, width, height, sx, sy, DUSK, moon = false)
        }
    }

    private fun landscape(c: Canvas, width: Float, height: Float, sx: Float, sy: Float, p: Palette, moon: Boolean) {
        c.drawColor(p.sky)
        if (moon) {
            paint.color = p.sun
            c.drawCircle(560f * sx, 60f * sy, 16f * sy, paint)
        }
        HILLS.forEachIndexed { i, points ->
            path.reset()
            path.moveTo(0f, height)
            for (k in points.indices step 2) path.lineTo(points[k] * sx, points[k + 1] * sy)
            path.lineTo(width, height)
            path.close()
            paint.color = p.hills[i]
            c.drawPath(path, paint)
        }
    }

    private fun stars(c: Canvas, width: Float, height: Float, scale: Float) {
        val random = Random(7)
        paint.color = 0xB3FFFFFF.toInt()
        repeat(70) { c.drawCircle(random.nextFloat() * width, random.nextFloat() * height, (0.6f + random.nextFloat()) * scale, paint) }
    }

    private fun ocean(c: Canvas, width: Float, height: Float, sx: Float, sy: Float) {
        c.drawColor(0xFF1E3550.toInt())
        val horizon = 190f * sy
        paint.color = 0xFF9C7563.toInt()
        c.drawCircle(330f * sx, 150f * sy, 36f * sy, paint)
        paint.color = 0xFF18405E.toInt()
        c.drawRect(0f, horizon, width, height, paint)
        paint.color = 0x609C7563
        for (i in 0 until 6) {
            val y = horizon + (10f + i * 18f) * sy
            val half = (60f - i * 7f) * sx
            c.drawRect(330f * sx - half, y, 330f * sx + half, y + 3f * sy, paint)
        }
    }
}
