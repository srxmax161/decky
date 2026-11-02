package com.dashdeck.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dashdeck.dashDeck

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = dashDeck

        // Live preview needs live data; the car session holds its own reference.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = app.telemetry.acquire()
            override fun onStop(owner: LifecycleOwner) = app.telemetry.release()
        })

        setContent {
            val config by app.configRepository.config.collectAsStateWithLifecycle()
            val accent = Color(config.accent.toInt())
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = accent,
                    secondary = accent,
                    background = Color(0xFF0B0E12),
                    surface = Color(0xFF0B0E12),
                    surfaceContainer = Color(0xFF161B22),
                    surfaceContainerHigh = Color(0xFF1D232C),
                    surfaceContainerHighest = Color(0xFF242B35),
                ),
            ) {
                EditorScreen(app)
            }
        }
    }
}
