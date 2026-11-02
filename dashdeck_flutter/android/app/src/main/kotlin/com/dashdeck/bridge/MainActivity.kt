package com.dashdeck.bridge

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import com.dashdeck.dashDeck
import com.dashdeck.mirror.Mirror
import com.dashdeck.mirror.MirrorService
import com.dashdeck.mirror.TapService
import com.dashdeck.data.Permissions
import com.dashdeck.data.WallpaperStore
import com.dashdeck.model.Wallpaper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Hosts the Flutter editor. Config, telemetry and drawing stay native so the car session (which
 * runs without Flutter) and the phone UI share one source of truth.
 */
class MainActivity : FlutterActivity() {
    private lateinit var preview: PreviewRenderer
    private val installSource = InstallSource(this)
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var pendingWallpaperResult: MethodChannel.Result? = null
    private var pendingMirrorResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewRenderer(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val app = dashDeck
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getConfig" -> result.success(app.configRepository.exportJson())
                "setConfig" -> runCatching { app.configRepository.importJson(call.arguments as String) }
                    .onSuccess { result.success(null) }
                    .onFailure { result.error("bad_config", it.message, null) }
                "resetConfig" -> {
                    app.configRepository.reset()
                    result.success(app.configRepository.exportJson())
                }
                "resetTrip" -> {
                    app.telemetry.resetTrip()
                    result.success(null)
                }
                "hasPermissions" -> result.success(Permissions.missing(this).isEmpty())
                "requestPermissions" -> requestPermissions(result)
                "pickWallpaper" -> pickWallpaper(result)
                "mediaAccess" -> result.success(app.telemetry.media.hasAccess())
                "openMediaAccess" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(null)
                }
                "openAppInfo" -> {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    result.success(null)
                }
                "mirrorStatus" -> result.success(
                    mapOf("running" to Mirror.running.value, "tapControl" to TapService.isEnabled(this)),
                )
                "startMirror" -> startMirror(result)
                "stopMirror" -> {
                    Mirror.stop()
                    result.success(null)
                }
                "openTapSettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(null)
                }
                "androidAutoStatus" -> result.success(
                    mapOf("ready" to installSource.isReadyForAndroidAuto(), "detail" to installSource.describe()),
                )
                "renderPreview" -> preview.render(
                    pageIndex = call.argument<Int>("page") ?: 0,
                    width = call.argument<Int>("width") ?: 800,
                    height = call.argument<Int>("height") ?: 400,
                    highlightTileId = call.argument<String>("highlight"),
                    result = result,
                )
                else -> result.notImplemented()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dashDeck.telemetry.acquire()
    }

    override fun onResume() {
        super.onResume()
        // The user may be coming back from granting notification access.
        dashDeck.telemetry.onMediaAccessChanged()
    }

    override fun onStop() {
        dashDeck.telemetry.release()
        super.onStop()
    }

    override fun onDestroy() {
        preview.close()
        super.onDestroy()
    }

    private fun requestPermissions(result: MethodChannel.Result) {
        val missing = Permissions.missing(this)
        if (missing.isEmpty() || pendingPermissionResult != null) {
            result.success(Permissions.missing(this).isEmpty())
            return
        }
        pendingPermissionResult = result
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        dashDeck.telemetry.onPermissionsChanged()
        pendingPermissionResult?.success(Permissions.missing(this).isEmpty())
        pendingPermissionResult = null
    }

    /** Shows Android's "Start casting?" prompt; resolves to whether mirroring started. */
    private fun startMirror(result: MethodChannel.Result) {
        if (Mirror.running.value || pendingMirrorResult != null) {
            result.success(Mirror.running.value)
            return
        }
        pendingMirrorResult = result
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), MIRROR_REQUEST)
    }

    /** Resolves to the updated config JSON, or null if nothing was picked. */
    private fun pickWallpaper(result: MethodChannel.Result) {
        if (pendingWallpaperResult != null) {
            result.success(null)
            return
        }
        pendingWallpaperResult = result
        val pick = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        startActivityForResult(pick, WALLPAPER_REQUEST)
    }

    @Deprecated("FlutterActivity still routes results through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MIRROR_REQUEST) {
            val result = pendingMirrorResult ?: return
            pendingMirrorResult = null
            if (resultCode != Activity.RESULT_OK || data == null) {
                result.success(false)
                return
            }
            MirrorService.start(this, resultCode, data)
            result.success(true)
            return
        }
        if (requestCode != WALLPAPER_REQUEST) return
        val result = pendingWallpaperResult ?: return
        pendingWallpaperResult = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        val app = dashDeck
        val video = contentResolver.getType(uri)?.startsWith("video/") == true
        // Decoding a camera photo or copying a video can take a moment; keep it off the UI thread.
        Thread {
            val saved = if (video) WallpaperStore.saveVideo(app, uri) else WallpaperStore.save(app, uri)
            runOnUiThread {
                if (saved) {
                    val kind = if (video) Wallpaper.VIDEO else Wallpaper.PHOTO
                    app.configRepository.update { it.copy(wallpaper = kind, wallpaperRev = System.currentTimeMillis()) }
                    result.success(app.configRepository.exportJson())
                } else {
                    result.error("bad_image", "That file couldn't be opened as a ${if (video) "video" else "picture"}", null)
                }
            }
        }.start()
    }

    private companion object {
        const val CHANNEL = "dashdeck/app"
        const val PERMISSION_REQUEST = 1
        const val WALLPAPER_REQUEST = 2
        const val MIRROR_REQUEST = 3
    }
}
