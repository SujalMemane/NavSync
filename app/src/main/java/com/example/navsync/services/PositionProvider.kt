package com.example.navsync.services

import android.util.Log
import com.example.navsync.repository.LocationPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PositionEstimate(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speedMps: Float = 0f,
    val bearing: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis(),
    val source: PositionSource = PositionSource.GNSS
) {
    fun toLocationPoint(): LocationPoint = LocationPoint(latitude, longitude)
}

interface PositionProvider {
    val currentPosition: StateFlow<PositionEstimate?>
    fun startUpdates()
    fun stopUpdates()
}

class GnssPositionProvider(private val locationProvider: OnlineLocationProvider) : PositionProvider {

    private val _currentPosition = MutableStateFlow<PositionEstimate?>(null)
    override val currentPosition: StateFlow<PositionEstimate?> = _currentPosition.asStateFlow()

    init {
        Log.d("NAVSYNC_POS", "GnssPositionProvider initialized (GNSS position stream active)")
    }

    override fun startUpdates() {
        locationProvider.startLocationUpdates()
    }

    override fun stopUpdates() {
        locationProvider.stopLocationUpdates()
    }

    fun updateFromNavPosition(navPos: NavigationPosition?) {
        if (navPos != null) {
            _currentPosition.value = PositionEstimate(
                latitude = navPos.latitude,
                longitude = navPos.longitude,
                altitude = navPos.altitude,
                accuracy = navPos.accuracy,
                speedMps = navPos.filteredSpeedMps,
                bearing = navPos.bearing,
                timestampMs = navPos.wallClockMillis,
                source = PositionSource.GNSS
            )
        }
    }
}
