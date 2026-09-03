package com.example.navsync.services

import com.example.navsync.repository.LocationPoint
import com.example.navsync.sensor.GnssData
import com.example.navsync.sensor.GnssStatusState
import com.example.navsync.sensor.SensorManagerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PositionSource {
    GNSS,
    FUSED,
    DEAD_RECKONING,
    MAP_MATCHED
}

data class NavigationPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speedMps: Float = 0f,
    val rawSpeedMps: Float = 0f,
    val filteredSpeedMps: Float = 0f,
    val displaySpeedKmh: Float = 0f,
    val isStationary: Boolean = true,
    val displacementMeters: Float = 0f,
    val bearing: Float = -1f,
    val timestampNanos: Long = 0L,
    val wallClockMillis: Long = System.currentTimeMillis(),
    val provider: String = "gps",
    val source: PositionSource = PositionSource.GNSS,
    val isMock: Boolean = false
) {
    val speedKmh: Float
        get() = displaySpeedKmh

    val isValid: Boolean
        get() = latitude != 0.0 && longitude != 0.0 && !isMock && latitude.isFinite() && longitude.isFinite()
}

interface LocationProvider {
    val currentPosition: StateFlow<NavigationPosition?>
    val gpsStatus: StateFlow<GnssStatusState>
    fun startLocationUpdates()
    fun stopLocationUpdates()
}

class OnlineLocationProvider(
    private val sensorManagerRepository: SensorManagerRepository
) : LocationProvider {

    private val speedEstimator = SpeedEstimator()

    private val _currentPosition = MutableStateFlow<NavigationPosition?>(null)
    override val currentPosition: StateFlow<NavigationPosition?> = _currentPosition.asStateFlow()

    private val _gpsStatus = MutableStateFlow(GnssStatusState.WAITING_FIX)
    override val gpsStatus: StateFlow<GnssStatusState> = _gpsStatus.asStateFlow()

    fun updateFromGnssData(gnss: GnssData?) {
        if (gnss == null) {
            _gpsStatus.value = GnssStatusState.WAITING_FIX
            return
        }

        _gpsStatus.value = gnss.status

        if (gnss.latitude != 0.0 && gnss.longitude != 0.0 && gnss.latitude.isFinite() && gnss.longitude.isFinite()) {
            val currentPoint = LocationPoint(latitude = gnss.latitude, longitude = gnss.longitude, altitude = gnss.altitude)
            val speedData = speedEstimator.processLocation(
                currentLocation = currentPoint,
                rawSpeedMps = gnss.speed,
                accuracyMeters = gnss.horizontalAccuracy,
                timestampMs = gnss.wallClockMillis
            )

            val pos = NavigationPosition(
                latitude = gnss.latitude,
                longitude = gnss.longitude,
                altitude = gnss.altitude,
                accuracy = gnss.horizontalAccuracy,
                speedMps = speedData.filteredSpeedMps,
                rawSpeedMps = speedData.rawSpeedMps,
                filteredSpeedMps = speedData.filteredSpeedMps,
                displaySpeedKmh = speedData.displaySpeedKmh,
                isStationary = speedData.isStationary,
                displacementMeters = speedData.displacementMeters,
                bearing = gnss.bearing,
                timestampNanos = gnss.timestampNanos,
                wallClockMillis = gnss.wallClockMillis,
                provider = gnss.provider,
                source = PositionSource.GNSS
            )
            _currentPosition.value = pos
        }
    }

    override fun startLocationUpdates() {
        speedEstimator.reset()
        sensorManagerRepository.startGnss()
    }

    override fun stopLocationUpdates() {
        sensorManagerRepository.stopGnss()
    }
}
