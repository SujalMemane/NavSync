package com.example.navsync.services

import android.location.Location
import android.util.Log
import com.example.navsync.repository.LocationPoint
import java.util.LinkedList
import java.util.Locale

data class SpeedData(
    val rawSpeedMps: Float = 0.0f,
    val derivedSpeedMps: Float = 0.0f,
    val filteredSpeedMps: Float = 0.0f,
    val displaySpeedKmh: Float = 0.0f,
    val isStationary: Boolean = true,
    val displacementMeters: Float = 0.0f,
    val accuracyMeters: Float = 0.0f,
    val logSummary: String = ""
)

class SpeedEstimator {

    companion object {
        private const val TAG = "NAVSYNC_SPEED"
        private const val GPS_STATIONARY_SPEED_THRESHOLD_MPS = 0.5f // ~1.8 km/h
        private const val DISPLACEMENT_STATIONARY_BASE_METERS = 3.0f
        private const val STATIONARY_CONSECUTIVE_SAMPLES_REQUIRED = 3
        private const val MOVING_CONSECUTIVE_SAMPLES_REQUIRED = 2
        private const val SPEED_ROLLING_WINDOW_SIZE = 5
        private const val EMA_ALPHA = 0.35f
    }

    private var previousLocation: LocationPoint? = null
    private var previousTimestampMs: Long = 0L

    private var stationaryCount = 0
    private var movingCount = 0
    private var isConfirmedStationary = true

    private val speedHistory = LinkedList<Float>()
    private var lastFilteredSpeedMps = 0.0f

    fun processLocation(
        currentLocation: LocationPoint,
        rawSpeedMps: Float,
        accuracyMeters: Float,
        timestampMs: Long = System.currentTimeMillis()
    ): SpeedData {
        val prevLoc = previousLocation
        val prevTimeMs = previousTimestampMs

        var displacement = 0.0f
        var derivedSpeedMps = 0.0f

        if (prevLoc != null && prevTimeMs > 0L && timestampMs > prevTimeMs) {
            val results = FloatArray(1)
            Location.distanceBetween(
                prevLoc.latitude, prevLoc.longitude,
                currentLocation.latitude, currentLocation.longitude,
                results
            )
            displacement = results[0]
            val elapsedSec = (timestampMs - prevTimeMs) / 1000.0f
            if (elapsedSec > 0.05f) {
                derivedSpeedMps = displacement / elapsedSec
            }
        }

        previousLocation = currentLocation
        previousTimestampMs = timestampMs

        // Accuracy-Aware Displacement Threshold
        val effectiveDisplacementThreshold = maxOf(
            DISPLACEMENT_STATIONARY_BASE_METERS,
            accuracyMeters * 0.25f
        )

        // Candidate Stationary Evaluation
        val isCandidateStationary = (rawSpeedMps <= GPS_STATIONARY_SPEED_THRESHOLD_MPS) &&
                (displacement <= effectiveDisplacementThreshold) &&
                (derivedSpeedMps <= 0.6f || displacement <= 2.0f)

        if (isCandidateStationary) {
            stationaryCount++
            movingCount = 0
            if (stationaryCount >= STATIONARY_CONSECUTIVE_SAMPLES_REQUIRED) {
                isConfirmedStationary = true
            }
        } else {
            movingCount++
            stationaryCount = 0
            if (movingCount >= MOVING_CONSECUTIVE_SAMPLES_REQUIRED) {
                isConfirmedStationary = false
            }
        }

        var displaySpeedKmh = 0.0f
        var filteredSpeedMps = 0.0f

        if (isConfirmedStationary) {
            // Stationary Phone Rule: Display EXACTLY 0 km/h
            displaySpeedKmh = 0.0f
            filteredSpeedMps = 0.0f
            speedHistory.clear()
            lastFilteredSpeedMps = 0.0f
        } else {
            // Legitimate Motion: Speed Filtering with Rolling Window & Exponential Smoothing
            val currentEffectiveSpeed = if (rawSpeedMps > 0.2f) rawSpeedMps else derivedSpeedMps

            speedHistory.add(currentEffectiveSpeed)
            if (speedHistory.size > SPEED_ROLLING_WINDOW_SIZE) {
                speedHistory.removeFirst()
            }

            val windowAvg = speedHistory.average().toFloat()

            filteredSpeedMps = if (lastFilteredSpeedMps == 0.0f) {
                windowAvg
            } else {
                (EMA_ALPHA * currentEffectiveSpeed) + ((1.0f - EMA_ALPHA) * lastFilteredSpeedMps)
            }
            lastFilteredSpeedMps = filteredSpeedMps

            val rawKmh = filteredSpeedMps * 3.6f
            displaySpeedKmh = (Math.round(rawKmh * 10.0f) / 10.0f)

            // Prevent small residual float fraction below 0.5 km/h
            if (displaySpeedKmh < 0.5f) {
                displaySpeedKmh = 0.0f
            }
        }

        val logSummary = String.format(
            Locale.US,
            "rawSpeed=%.2f m/s derivedSpeed=%.2f m/s accuracy=%.1f m displacement=%.1f m stationary=%b displaySpeed=%.1f km/h",
            rawSpeedMps, derivedSpeedMps, accuracyMeters, displacement, isConfirmedStationary, displaySpeedKmh
        )

        Log.d(TAG, logSummary)

        return SpeedData(
            rawSpeedMps = rawSpeedMps,
            derivedSpeedMps = derivedSpeedMps,
            filteredSpeedMps = filteredSpeedMps,
            displaySpeedKmh = displaySpeedKmh,
            isStationary = isConfirmedStationary,
            displacementMeters = displacement,
            accuracyMeters = accuracyMeters,
            logSummary = logSummary
        )
    }

    fun reset() {
        previousLocation = null
        previousTimestampMs = 0L
        stationaryCount = 0
        movingCount = 0
        isConfirmedStationary = true
        speedHistory.clear()
        lastFilteredSpeedMps = 0.0f
    }
}
