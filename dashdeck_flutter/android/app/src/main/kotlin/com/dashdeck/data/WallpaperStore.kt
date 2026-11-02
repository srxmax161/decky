package com.dashdeck.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * The user's own wallpaper, kept in app storage: a photo as a downscaled JPEG, or a video as a copy
 * plus a still from it (the JPEG) for the phone preview and while the video can't play.
 */
object WallpaperStore {
    private const val MAX_EDGE = 2048

    private fun file(context: Context) = File(context.filesDir, "wallpaper.jpg")

    fun videoFile(context: Context): File? = File(context.filesDir, "wallpaper.mp4").takeIf { it.exists() }

    /** Copies the video at [uri] and saves its first frame as the still; false if it isn't a playable video. */
    fun saveVideo(context: Context, uri: Uri): Boolean = runCatching {
        val temp = File(context.filesDir, "wallpaper.mp4.part")
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: return false
        val still = MediaMetadataRetriever().run {
            try {
                setDataSource(temp.path)
                getFrameAtTime(0)
            } finally {
                release()
            }
        }
        if (still == null) {
            temp.delete()
            return false
        }
        val scale = MAX_EDGE.toFloat() / max(still.width, still.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(still, (still.width * scale).toInt(), (still.height * scale).toInt(), true) else still
        file(context).outputStream().use { sized.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        check(temp.renameTo(File(context.filesDir, "wallpaper.mp4")))
        true
    }.getOrDefault(false)

    /** Copies and downscales [uri]; returns false if it couldn't be read as an image. */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return false
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE) }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return false
        file(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    /** Decodes the photo at roughly [targetEdge] pixels on its long side, or null if none is set. */
    fun load(context: Context, targetEdge: Int): Bitmap? {
        val f = file(context).takeIf { it.exists() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetEdge) }
        return BitmapFactory.decodeFile(f.path, options)
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }
}
