package com.example.navsync.sensor

import android.hardware.SensorManager

enum class SensorAccuracyState {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN;

    companion object {
        fun fromInt(accuracy: Int): SensorAccuracyState = when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> UNRELIABLE
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            else -> UNKNOWN
        }
    }
}

/**
 * Live health monitoring for an individual sensor data stream.
 */
data class StreamHealth(
    val isAvailable: Boolean = false,
    val measuredFrequencyHz: Double = 0.0,
    val sampleCount: Long = 0L,
    val lastTimestampNanos: Long = 0L,
    val lastUpdateTimeMs: Long = 0L,
    val isStale: Boolean = false,
    val accuracy: SensorAccuracyState = SensorAccuracyState.UNKNOWN
)

/**
 * Sensor availability map for the device.
 */
data class SensorAvailability(
    val accelerometer: StreamHealth = StreamHealth(),
    val gyroscope: StreamHealth = StreamHealth(),
    val magnetometer: StreamHealth = StreamHealth(),
    val gravity: StreamHealth = StreamHealth(),
    val rotationVector: StreamHealth = StreamHealth(),
    val gnss: StreamHealth = StreamHealth()
)
