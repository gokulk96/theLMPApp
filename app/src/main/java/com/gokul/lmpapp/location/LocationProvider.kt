package com.gokul.lmpapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

data class UserLocation(val lat: Double, val lon: Double)

/**
 * Location provider with a two-tier fallback:
 *   1. FusedLocationProvider (best accuracy, requires Google Play)
 *   2. Standard LocationManager (always works on emulators; picks up the
 *      mock location set in Extended Controls → Location)
 */
class LocationProvider(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): UserLocation? {
        // 1 — Fused current fix
        val fusedCurrent = runCatching {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        }.getOrNull()
        if (fusedCurrent != null) return UserLocation(fusedCurrent.latitude, fusedCurrent.longitude)

        // 2 — Fused last-known (may be null on a fresh emulator)
        val fusedLast = runCatching { fusedClient.lastLocation.await() }.getOrNull()
        if (fusedLast != null) return UserLocation(fusedLast.latitude, fusedLast.longitude)

        // 3 — Standard LocationManager (works on emulators and devices
        //     without Google Play; picks up Extended Controls mock location)
        return locationManagerFix()
    }

    @SuppressLint("MissingPermission")
    private fun locationManagerFix(): UserLocation? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { UserLocation(it.latitude, it.longitude) }
    }
}
