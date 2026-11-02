package com.dashdeck.bridge

import android.content.Context
import android.os.Build

/**
 * Android Auto 17.x hides sideloaded Car App Library apps, even with "Unknown sources" on, unless
 * the phone's own package installer installed them on behalf of the Play Store. Apps can't arrange
 * that themselves; `tool/install_android_auto.sh` does it from the computer. This only reports it.
 */
class InstallSource(private val context: Context) {

    fun isReadyForAndroidAuto(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return legacyInstaller() == PLAY_STORE
        val info = context.packageManager.getInstallSourceInfo(context.packageName)
        return info.installingPackageName == PLAY_STORE && info.initiatingPackageName in PACKAGE_INSTALLERS
    }

    fun describe(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "installer=${legacyInstaller()}"
        val info = context.packageManager.getInstallSourceInfo(context.packageName)
        return "installer=${info.installingPackageName}, started by=${info.initiatingPackageName}"
    }

    @Suppress("DEPRECATION")
    private fun legacyInstaller() = context.packageManager.getInstallerPackageName(context.packageName)

    private companion object {
        const val PLAY_STORE = "com.android.vending"
        val PACKAGE_INSTALLERS = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")
    }
}
