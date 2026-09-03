package com.example.navsync.sensor

/**
 * Data model for 3-axis motion/field sensors (Accelerometer, Gyroscope, Magnetometer, Gravity).
 * All measurements retain raw un-filtered hardware values.
 */
data class ImuData(
    val sensorType: Int,
    val timestampNanos: Long,
    val wallClockMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int
)

/**
 * Data model for complete Rotation Vector sensor events.
 */
data class RotationVectorData(
    val timestampNanos: Long,
    val wallClockMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float = 0f,
    val headingAccuracy: Float = -1f,
    val accuracy: Int
)
