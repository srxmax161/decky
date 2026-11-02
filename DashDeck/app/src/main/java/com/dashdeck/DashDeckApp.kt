package com.dashdeck

import android.app.Application
import android.content.Context
import com.dashdeck.data.ConfigRepository
import com.dashdeck.data.TelemetryHub

class DashDeckApp : Application() {
    lateinit var configRepository: ConfigRepository
        private set
    lateinit var telemetry: TelemetryHub
        private set

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this)
        telemetry = TelemetryHub(this)
    }
}

val Context.dashDeck: DashDeckApp
    get() = applicationContext as DashDeckApp
