package com.dashdeck.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class DashDeckCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DashDeckSession()

    /** The main screen gets the dashboard; the driver display gets a map. */
    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) ClusterSession() else DashDeckSession()
}
