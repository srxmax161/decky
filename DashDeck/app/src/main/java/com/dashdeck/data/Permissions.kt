package com.dashdeck.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    private val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    // Defined by Google Play services / Android Auto; only requestable when the defining app is installed.
    private val CAR = listOf(
        "com.google.android.gms.permission.CAR_SPEED",
        "com.google.android.gms.permission.CAR_FUEL",
        "com.google.android.gms.permission.CAR_MILEAGE",
    )

    fun hasLocation(context: Context) = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): List<String> =
        (LOCATION + CAR.filter { isDefined(context, it) }).filterNot { isGranted(context, it) }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isDefined(context: Context, permission: String) =
        runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess
}
