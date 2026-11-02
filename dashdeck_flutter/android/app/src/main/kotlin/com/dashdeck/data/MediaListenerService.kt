package com.dashdeck.data

import android.service.notification.NotificationListenerService

/**
 * Android only lets an app read other apps' media sessions (title, artist, position) if it has
 * notification access, which is granted to a listener service like this one. It reads nothing itself.
 */
class MediaListenerService : NotificationListenerService()
