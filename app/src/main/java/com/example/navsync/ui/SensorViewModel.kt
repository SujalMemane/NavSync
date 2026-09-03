package com.example.navsync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.navsync.data.SensorDataRepository
import com.example.navsync.sensor.GnssData
import com.example.navsync.sensor.ImuData
import com.example.navsync.sensor.RotationVectorData
import com.example.navsync.sensor.SensorAvailability
import kotlinx.coroutines.flow.StateFlow

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SensorDataRepository(application)

    val accelerometerData: StateFlow<ImuData?> = repository.accelerometerData
    val gyroscopeData: StateFlow<ImuData?> = repository.gyroscopeData
    val magnetometerData: StateFlow<ImuData?> = repository.magnetometerData
    val gravityData: StateFlow<ImuData?> = repository.gravityData
    val rotationVectorData: StateFlow<RotationVectorData?> = repository.rotationVectorData
    val gnssData: StateFlow<GnssData?> = repository.gnssData
    val availability: StateFlow<SensorAvailability> = repository.availability
    val isRecording: StateFlow<Boolean> = repository.isRecording
    val recordingPath: StateFlow<String> = repository.recordingPath

    fun startSensors() {
        repository.startSensors()
    }

    fun stopSensors() {
        repository.stopSensors()
    }

    fun toggleRecording() {
        if (isRecording.value) {
            repository.stopRecording()
        } else {
            repository.startRecording()
        }
    }
}
