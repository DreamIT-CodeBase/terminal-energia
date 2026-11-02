package com.terminalscout.app.prod.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await

/** A single GPS fix mapped to the payload's location block. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val speedKmph: Float?,
    val headingDeg: Float?,
    val accuracyM: Float?
)

/**
 * Thin wrapper over the fused location provider. [currentFix] tries for a fresh high-accuracy fix
 * and falls back to the last known location; it returns null when permission is missing or no fix
 * is available, so callers degrade gracefully rather than block capture.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /**
     * Checks whether device location services are switched on and can satisfy a high-accuracy
     * request. The returned [Task] succeeds when location is usable; on failure with a
     * `ResolvableApiException` the caller can launch the system "turn on location" dialog. Used to
     * gate recording so no clip is captured without a live GPS fix.
     */
    fun locationSettingsTask(): Task<LocationSettingsResponse> {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
            .build()
        return LocationServices.getSettingsClient(appContext).checkLocationSettings(settingsRequest)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission") // Guarded by hasPermission() below.
    suspend fun currentFix(): LocationFix? {
        if (!hasPermission()) {
            Log.d(TAG, "No location permission — location block will be omitted")
            return null
        }
        return try {
            val cts = CancellationTokenSource()
            val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                ?: client.lastLocation.await()
            location?.let {
                LocationFix(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    // m/s → km/h; only trust values the fix actually reported.
                    speedKmph = if (it.hasSpeed()) it.speed * 3.6f else null,
                    headingDeg = if (it.hasBearing()) it.bearing else null,
                    accuracyM = if (it.hasAccuracy()) it.accuracy else null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain location fix", e)
            null
        }
    }

    companion object {
        private const val TAG = "LocationProvider"
    }
}
