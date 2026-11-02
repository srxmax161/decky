package com.dashdeck.data

import android.graphics.Bitmap
import android.os.SystemClock

/** What a music app on the phone is playing, as its media session reports it. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    /** [SystemClock.elapsedRealtime] when [positionMs] was reported. */
    val positionUpdatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    val art: Bitmap?,
    val appPackage: String,
) {
    val trackKey: String get() = "${artist.lowercase()}|${title.lowercase()}"

    /** Players only report position on changes, so extrapolate while playing. */
    fun positionNow(): Long {
        if (!playing) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - positionUpdatedAt
        val position = positionMs + (elapsed * speed).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val trackKey: String,
    val status: Status,
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
) {
    enum class Status { LOADING, SYNCED, PLAIN, INSTRUMENTAL, NOT_FOUND, ERROR }

    /** Index of the line being sung at [positionMs], or -1 before the first one. */
    fun lineAt(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
