package com.forest.offgrid.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

class LocationHelper(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        // Try to get last known location first for speed
        var location = fusedLocationClient.lastLocation.await()
        
        if (location == null) {
            // If null, request a fresh update (high accuracy)
            val cancellationToken = CancellationTokenSource()
            try {
                location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationToken.token
                ).await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return location
    }
}
