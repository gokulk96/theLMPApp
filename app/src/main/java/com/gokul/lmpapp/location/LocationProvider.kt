package com.gokul.lmpapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

data class UserLocation(val lat: Double, val lon: Double)

/** Thin wrapper around FusedLocationProvider with a coroutine API. */
class LocationProvider(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the current location, falling back to the last known location.
     * Returns null if no fix is available. Callers must check [hasPermission].
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): UserLocation? {
        val current = runCatching {
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        }.getOrNull()
        val location = current ?: runCatching { client.lastLocation.await() }.getOrNull()
        return location?.let { UserLocation(it.latitude, it.longitude) }
    }
}
