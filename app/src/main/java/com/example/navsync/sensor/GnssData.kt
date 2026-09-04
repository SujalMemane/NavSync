package com.example.navsync.sensor

enum class GnssStatusState {
    NO_PERMISSION,
    LOCATION_DISABLED,
    WAITING_FIX,
    GNSS_ACTIVE,
    GNSS_DEGRADED,
    GNSS_LOST,
    DEAD_RECKONING
}

/**
 * Data model for raw GNSS location and satellite state.
 */
data class GnssData(
    val timestampNanos: Long = 0L,
    val wallClockMillis: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val horizontalAccuracy: Float = 0f,
    val verticalAccuracy: Float = 0f,
    val speedAccuracy: Float = 0f,
    val bearingAccuracy: Float = 0f,
    val satelliteCount: Int = 0,
    val usedInFix: Int = 0,
    val strongCount: Int = 0,
    val moderateCount: Int = 0,
    val weakCount: Int = 0,
    val provider: String = "none",
    val status: GnssStatusState = GnssStatusState.GNSS_LOST
)
