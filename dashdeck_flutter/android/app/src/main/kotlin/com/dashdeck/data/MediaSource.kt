package com.dashdeck.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat

/** Follows whichever music app is playing (Spotify, YouTube Music, …) and can control it. */
class MediaSource(
    private val context: Context,
    private val hub: TelemetryHub,
    private val onTrackChanged: (NowPlaying?) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var running = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = choose(activeSessions())
    }

    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun start() {
        if (running) return
        running = true
        val access = hasAccess()
        hub.update { it.copy(mediaAccess = access) }
        if (!access) return
        runCatching { manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler) }
        choose(activeSessions())
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
        hub.update { it.copy(nowPlaying = null) }
        onTrackChanged(null)
    }

    fun restart() {
        if (!running) return
        stop()
        start()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun activeSessions(): List<MediaController> =
        runCatching { manager.getActiveSessions(listener) }.getOrDefault(emptyList())

    /** Prefers a session that's actually playing over one that's merely paused. */
    private fun choose(sessions: List<MediaController>) {
        val pick = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = pick
            pick?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val metadata = c?.metadata
        val state = c?.playbackState
        val nowPlaying = if (c == null || metadata == null) {
            null
        } else {
            NowPlaying(
                title = metadata.text(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
                artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = state?.position ?: 0L,
                positionUpdatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                appPackage = c.packageName,
            )
        }
        hub.update { it.copy(nowPlaying = nowPlaying) }
        onTrackChanged(nowPlaying)
    }

    private fun MediaMetadata.text(key: String) = getString(key)?.takeIf { it.isNotBlank() }
}
