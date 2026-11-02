package com.dashdeck.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Replays taps from the car screen onto the phone's screen, so a mirrored app can be played from
 * the car. Android only lets accessibility services do this, and only once the user switches it on.
 */
class TapService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: TapService? = null

        /** Whether the user has switched tap control on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, TapService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Taps the phone screen at (x, y) in its current orientation. False if tap control is off. */
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TAP_MS)
            return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        private const val TAP_MS = 40L
    }
}
