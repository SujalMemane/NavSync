package com.example.navsync.repository

import android.location.Location
import com.example.navsync.data.SensorDataRepository
import com.example.navsync.sensor.GnssData
import com.example.navsync.services.DistanceCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val timestampNanos: Long = 0L
)

class LocationRepository(sensorDataRepository: SensorDataRepository) {

    private val distanceCalculator = DistanceCalculator()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _trackPoints = MutableStateFlow<List<LocationPoint>>(emptyList())
    val trackPoints: StateFlow<List<LocationPoint>> = _trackPoints.asStateFlow()

    private val _distanceKm = MutableStateFlow(0.0)
    val distanceKm: StateFlow<Double> = _distanceKm.asStateFlow()

    init {
        scope.launch {
            sensorDataRepository.gnssData.collect { gnssData ->
                if (gnssData != null && gnssData.latitude != 0.0 && gnssData.longitude != 0.0) {
                    processGnssUpdate(gnssData)
                }
            }
        }
    }

    private fun processGnssUpdate(gnssData: GnssData) {
        val loc = Location(gnssData.provider).apply {
            latitude = gnssData.latitude
            longitude = gnssData.longitude
            altitude = gnssData.altitude
            speed = gnssData.speed
            bearing = gnssData.bearing
            accuracy = gnssData.horizontalAccuracy
            elapsedRealtimeNanos = gnssData.timestampNanos
        }

        val updatedDistKm = distanceCalculator.addLocation(loc)
        _distanceKm.value = updatedDistKm

        val point = LocationPoint(
            latitude = gnssData.latitude,
            longitude = gnssData.longitude,
            altitude = gnssData.altitude,
            timestampNanos = gnssData.timestampNanos
        )

        val currentList = _trackPoints.value.toMutableList()
        // Append point if it moved at least ~1 meter from last point to save memory
        if (currentList.isEmpty()) {
            currentList.add(point)
            _trackPoints.value = currentList
        } else {
            val last = currentList.last()
            val results = FloatArray(1)
            Location.distanceBetween(last.latitude, last.longitude, point.latitude, point.longitude, results)
            if (results[0] >= 1.0f) {
                currentList.add(point)
                _trackPoints.value = currentList
            }
        }
    }

    fun resetTrack() {
        distanceCalculator.reset()
        _distanceKm.value = 0.0
        _trackPoints.value = emptyList()
    }
}
