package com.example.navsync.services

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.SystemClock
import java.util.Locale

class DistanceCalculator {
    private var totalDistanceMeters: Double = 0.0
    private var lastValidLocation: Location? = null

    fun reset() {
        totalDistanceMeters = 0.0
        lastValidLocation = null
    }

    fun addLocation(location: Location): Double {
        val last = lastValidLocation
        if (last == null) {
            lastValidLocation = location
            return totalDistanceMeters
        }

        // Sanity checks: reject non-increasing time or low accuracy
        val timeDeltaSec = (location.elapsedRealtimeNanos - last.elapsedRealtimeNanos) / 1e9
        if (timeDeltaSec <= 0.1) return totalDistanceMeters
        if (location.hasAccuracy() && location.accuracy > 50f) return totalDistanceMeters

        val dist = last.distanceTo(location).toDouble()
        val impliedSpeedKmh = (dist / timeDeltaSec) * 3.6

        // Reject impossible location jumps (> 180 km/h)
        if (dist > 0.5 && impliedSpeedKmh <= 180.0) {
            totalDistanceMeters += dist
            lastValidLocation = location
        }

        return totalDistanceMeters
    }

    fun getDistanceKm(): Double = totalDistanceMeters / 1000.0
}

class DurationTracker {
    private var startTimeMs: Long = SystemClock.elapsedRealtime()
    private var isRunning: Boolean = false

    fun start() {
        startTimeMs = SystemClock.elapsedRealtime()
        isRunning = true
    }

    fun reset() {
        startTimeMs = SystemClock.elapsedRealtime()
    }

    fun getElapsedSeconds(): Long {
        if (!isRunning) return 0L
        return (SystemClock.elapsedRealtime() - startTimeMs) / 1000L
    }

    fun getFormattedDuration(): String {
        val totalSec = getElapsedSeconds()
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

object BatteryReader {
    fun getBatteryPercentage(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) return level

            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val rawLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (rawLevel >= 0 && scale > 0) {
                (rawLevel * 100) / scale
            } else {
                100
            }
        } catch (e: Exception) {
            100
        }
    }
}
