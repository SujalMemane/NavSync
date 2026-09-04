package com.example.navsync.repository

import android.content.Context
import android.util.Log
import com.example.navsync.data.SensorDataRepository
import com.example.navsync.sensor.GnssData
import com.example.navsync.services.BatteryReader
import com.example.navsync.services.DurationTracker
import com.example.navsync.services.GnssQualityEvaluator
import com.example.navsync.services.GnssSignalQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NavigationState(
    val gnssData: GnssData? = null,
    val compassHeading: Float = -1f,
    val distanceKm: Double = 0.0,
    val durationFormatted: String = "00:00:00",
    val batteryPercent: Int = 100,
    val gnssQuality: GnssSignalQuality = GnssSignalQuality.LOST,
    val isTracking: Boolean = true,
    val trackPoints: List<LocationPoint> = emptyList()
)

class NavigationRepository(
    private val context: Context,
    val sensorDataRepository: SensorDataRepository,
    val locationRepository: LocationRepository
) {
    private val durationTracker = DurationTracker().apply { start() }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    init {
        scope.launch {
            sensorDataRepository.gnssData.collect {
                updateState()
            }
        }
        scope.launch {
            while (isActive) {
                delay(500L)
                updateState()
            }
        }
    }

    private fun updateState() {
        val gnss = sensorDataRepository.gnssData.value
        val compass = sensorDataRepository.compassHeading.value
        val dist = locationRepository.distanceKm.value
        val tracks = locationRepository.trackPoints.value
        val duration = durationTracker.getFormattedDuration()
        val battery = BatteryReader.getBatteryPercentage(context)
        val quality = GnssQualityEvaluator.evaluate(gnss)

        if (gnss != null) {
            val ageMs = System.currentTimeMillis() - gnss.wallClockMillis
            Log.d("NAVSYNC_GNSS", "locationReceived lat=${gnss.latitude} lon=${gnss.longitude} accuracy=${gnss.horizontalAccuracy} speed=${gnss.speed} bearing=${gnss.bearing} ageMs=$ageMs")
        }

        _navigationState.value = NavigationState(
            gnssData = gnss,
            compassHeading = compass,
            distanceKm = dist,
            durationFormatted = duration,
            batteryPercent = battery,
            gnssQuality = quality,
            isTracking = true,
            trackPoints = tracks
        )
    }

    fun startTracking() {
        sensorDataRepository.startSensors()
    }

    fun stopTracking() {
        sensorDataRepository.stopSensors()
    }
}
