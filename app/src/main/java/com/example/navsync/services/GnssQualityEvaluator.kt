package com.example.navsync.services

import com.example.navsync.sensor.GnssData
import com.example.navsync.sensor.GnssStatusState

enum class GnssSignalQuality {
    GOOD,
    FAIR,
    POOR,
    LOST
}

object GnssQualityEvaluator {
    fun evaluate(gnssData: GnssData?): GnssSignalQuality {
        if (gnssData == null || gnssData.status == GnssStatusState.GNSS_LOST || gnssData.status == GnssStatusState.NO_PERMISSION || gnssData.status == GnssStatusState.LOCATION_DISABLED) {
            return GnssSignalQuality.LOST
        }

        val ageMs = System.currentTimeMillis() - gnssData.wallClockMillis
        if (ageMs > 10_000L) {
            return GnssSignalQuality.LOST
        }

        val acc = gnssData.horizontalAccuracy

        return when {
            acc > 0f && acc <= 15f -> GnssSignalQuality.GOOD
            acc > 15f && acc <= 35f -> GnssSignalQuality.FAIR
            acc > 35f -> GnssSignalQuality.POOR
            else -> GnssSignalQuality.LOST
        }
    }
}
