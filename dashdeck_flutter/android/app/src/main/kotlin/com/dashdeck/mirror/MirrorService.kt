package com.dashdeck.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dashdeck.R

/**
 * Android only allows screen capture from a foreground service with a visible notification; this is
 * it. Started by the phone app once the user has agreed to "Start casting?".
 */
class MirrorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Mirror.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground, as a media-projection service, before asking for the projection.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        Mirror.start(this, projection)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Mirror.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone mirroring", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_deck)
            .setContentTitle("Mirroring to the car screen")
            .setContentText("Shown in DashDeck's Mirror page while parked")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.dashdeck.STOP_MIRROR"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }
    }
}
