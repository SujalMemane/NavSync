package com.example.navsync.data

import android.content.Context
import com.example.navsync.sensor.GnssData
import com.example.navsync.sensor.ImuData
import com.example.navsync.sensor.RotationVectorData
import com.example.navsync.sensor.SensorAvailability
import com.example.navsync.sensor.SensorManagerRepository
import kotlinx.coroutines.flow.StateFlow

class SensorDataRepository(context: Context) {

    val sensorManagerRepo = SensorManagerRepository(context.applicationContext)

    val accelerometerData: StateFlow<ImuData?> = sensorManagerRepo.accelerometerData
    val gyroscopeData: StateFlow<ImuData?> = sensorManagerRepo.gyroscopeData
    val magnetometerData: StateFlow<ImuData?> = sensorManagerRepo.magnetometerData
    val gravityData: StateFlow<ImuData?> = sensorManagerRepo.gravityData
    val rotationVectorData: StateFlow<RotationVectorData?> = sensorManagerRepo.rotationVectorData
    val compassHeading: StateFlow<Float> = sensorManagerRepo.compassHeading
    val gnssData: StateFlow<GnssData?> = sensorManagerRepo.gnssData
    val availability: StateFlow<SensorAvailability> = sensorManagerRepo.availability
    val isRecording: StateFlow<Boolean> = sensorManagerRepo.isRecording
    val recordingPath: StateFlow<String> = sensorManagerRepo.recordingPath

    fun startSensors() {
        sensorManagerRepo.startSensors()
    }

    fun stopSensors() {
        sensorManagerRepo.stopSensors()
    }

    fun startGnss() {
        sensorManagerRepo.startGnss()
    }

    fun stopGnss() {
        sensorManagerRepo.stopGnss()
    }

    fun startRecording(): Boolean {
        return sensorManagerRepo.startRecording()
    }

    fun stopRecording(): String? {
        return sensorManagerRepo.stopRecording()
    }
}
