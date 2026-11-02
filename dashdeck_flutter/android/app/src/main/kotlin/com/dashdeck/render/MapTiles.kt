package com.dashdeck.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * OpenStreetMap tiles, darkened for night driving and kept in memory and on disk so a dead spot or
 * familiar roads need no network. OSM allows light personal use like this with an identifying
 * User-Agent and caching (https://operations.osmfoundation.org/policies/tiles/). Shared by every
 * renderer and thread.
 */
object MapTiles {
    const val ATTRIBUTION = "© OpenStreetMap contributors"
    private const val MEMORY_BYTES = 48 * 1024 * 1024
    private const val RETRY_AFTER_MS = 15_000L
    private const val DISK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newFixedThreadPool(4)
    @Volatile private var disk: File? = null

    /** The tile if it's ready; otherwise starts loading it and returns null. */
    fun get(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        val dir = disk ?: File(context.cacheDir, "osm-tiles").also { disk = it }
        val recentFailure = failedAt[key]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true
        if (!recentFailure && loading.add(key)) {
            executor.execute {
                try {
                    load(dir, z, x, y)?.let { memory.put(key, it) } ?: failedAt.put(key, System.currentTimeMillis())
                } finally {
                    loading.remove(key)
                }
            }
        }
        return null
    }

    /** Only what's already in memory, for drawing a blurry stand-in while the real tile loads. */
    fun cached(z: Int, x: Int, y: Int): Bitmap? = memory.get("$z/$x/$y")

    private fun load(dir: File, z: Int, x: Int, y: Int): Bitmap? {
        val file = File(dir, "$z/$x/$y.png")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < DISK_MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.path)?.let { return darken(it) }
        }
        return runCatching {
            val connection = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "DashDeck/1.0 (personal Android Auto dashboard)")
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                val bytes = connection.inputStream.use { it.readBytes() }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::darken)
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w("DashDeck", "Map tile $z/$x/$y failed: $it") }.getOrNull()
            // Offline: an old tile beats a blank square.
            ?: if (file.exists()) BitmapFactory.decodeFile(file.path)?.let(::darken) else null
    }

    /** Inverts the light map and mutes its colours: dark land, light roads and labels, grey-blue water. */
    private fun darken(tile: Bitmap): Bitmap {
        val dark = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
        Canvas(dark).drawBitmap(tile, 0f, 0f, darkPaint)
        tile.recycle()
        return dark
    }

    private val darkPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ).apply {
                postConcat(ColorMatrix().apply { setSaturation(0.2f) })
                postConcat(ColorMatrix().apply { setScale(0.85f, 0.88f, 0.95f, 1f) })
            },
        )
    }
}
