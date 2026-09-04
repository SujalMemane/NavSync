package com.example.navsync.services

import android.util.Log
import com.example.navsync.repository.LocationPoint
import java.util.Locale
import kotlin.math.*

/**
 * Propagates vehicle geographic coordinates during GPS blackout intervals
 * using the ML model's predicted displacement (dx, dy) and heading.
 */
class DeadReckoningPositionEstimator {

    companion object {
        private const val TAG = "NAVSYNC_DR_POS"
        private const val METERS_PER_DEG_LAT = 111139.0
        private const val MAX_ROUTE_SNAP_DISTANCE_METERS = 30.0
    }

    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentAltitude: Double = 0.0
    private var currentHeading: Float = 0f
    private var currentSpeedMps: Float = 0f
    private var totalDeadReckoningDistanceMeters: Double = 0.0
    private var lastUpdateMs: Long = 0L

    var activeRoute: Route? = null

    /**
     * Resets / synchronizes the dead-reckoning reference position with a verified GNSS fix.
     */
    fun syncWithGnss(lat: Double, lon: Double, alt: Double, bearing: Float, speedMps: Float) {
        if (lat.isFinite() && lon.isFinite() && lat != 0.0 && lon != 0.0) {
            currentLat = lat
            currentLon = lon
            currentAltitude = alt
            if (bearing >= 0f) {
                currentHeading = bearing
            }
            currentSpeedMps = speedMps
            totalDeadReckoningDistanceMeters = 0.0
            lastUpdateMs = System.currentTimeMillis()
        }
    }

    /**
     * Propagates vehicle position using ML displacement prediction.
     * @param displacement Result containing (dx, dy) from DeadReckoningMlEngine
     * @param compassHeading Current sensor fused or magnetic compass heading (0..360)
     * @return Updated NavigationPosition with source = DEAD_RECKONING
     */
    fun step(
        displacement: DisplacementResult,
        compassHeading: Float
    ): NavigationPosition {
        val now = System.currentTimeMillis()
        val dt = if (lastUpdateMs > 0L) {
            ((now - lastUpdateMs) / 1000.0).coerceIn(0.1, 1.2)
        } else {
            0.5
        }
        lastUpdateMs = now

        // Use compass heading if available, otherwise maintain last known heading
        val heading = if (compassHeading >= 0f) compassHeading else currentHeading
        currentHeading = heading

        if (currentLat == 0.0 || currentLon == 0.0) {
            // Default fallback if no previous GNSS fix was recorded
            currentLat = 18.5204
            currentLon = 73.8567
        }

        if (!displacement.isStationary && displacement.speedMps > 0.2f) {
            val headingRad = Math.toRadians(heading.toDouble())

            // Scaled displacement for this elapsed time step:
            val stepDy = displacement.dy * dt
            val stepDx = displacement.dx * dt

            // Vehicle local frame to ENU (East, North, Up):
            val dNorth = stepDy * cos(headingRad) - stepDx * sin(headingRad)
            val dEast = stepDy * sin(headingRad) + stepDx * cos(headingRad)

            val dLat = dNorth / METERS_PER_DEG_LAT
            val latRad = Math.toRadians(currentLat)
            val metersPerDegLon = METERS_PER_DEG_LAT * cos(latRad).coerceAtLeast(0.01)
            val dLon = dEast / metersPerDegLon

            currentLat += dLat
            currentLon += dLon

            // Map-matching snap along active route polyline (85% centerline snap)
            val route = activeRoute
            if (route != null && route.geometry.isNotEmpty()) {
                val snapped = snapToRoute(currentLat, currentLon, route.geometry)
                if (snapped != null) {
                    currentLat = currentLat * 0.15 + snapped.latitude * 0.85
                    currentLon = currentLon * 0.15 + snapped.longitude * 0.85
                }
            }

            val stepDistance = sqrt(stepDx * stepDx + stepDy * stepDy)
            totalDeadReckoningDistanceMeters += stepDistance
            currentSpeedMps = displacement.speedMps
        } else {
            // Immediately zero speed when stationary to prevent ghost movement / speed jitter
            currentSpeedMps = 0f
        }

        val rawKmh = currentSpeedMps * 3.6f
        val displaySpeedKmh = if (displacement.isStationary || currentSpeedMps < 0.70f || rawKmh < 2.5f) 0.0f else (Math.round(rawKmh * 10.0f) / 10.0f)

        Log.d(TAG, String.format(
            Locale.US,
            "DR_POSITION: lat=%.6f, lon=%.6f | heading=%.1f° | speed=%.1f km/h (%.2f m/s) | totalDist=%.1f m | stationary=%b",
            currentLat, currentLon, heading, displaySpeedKmh, currentSpeedMps, totalDeadReckoningDistanceMeters, displacement.isStationary
        ))

        return NavigationPosition(
            latitude = currentLat,
            longitude = currentLon,
            altitude = currentAltitude,
            accuracy = 15.0f + (totalDeadReckoningDistanceMeters * 0.05).toFloat().coerceAtMost(50f),
            speedMps = currentSpeedMps,
            rawSpeedMps = currentSpeedMps,
            filteredSpeedMps = currentSpeedMps,
            displaySpeedKmh = displaySpeedKmh,
            isStationary = displacement.isStationary,
            displacementMeters = totalDeadReckoningDistanceMeters.toFloat(),
            bearing = heading,
            timestampNanos = System.nanoTime(),
            wallClockMillis = now,
            provider = "ml_dead_reckoning",
            source = PositionSource.DEAD_RECKONING
        )
    }

    /**
     * Snaps coordinate to nearest segment on route polyline if within MAX_ROUTE_SNAP_DISTANCE_METERS.
     */
    private fun snapToRoute(lat: Double, lon: Double, geometry: List<LocationPoint>): LocationPoint? {
        if (geometry.size < 2) return null
        var bestPoint: LocationPoint? = null
        var minDistance = Double.MAX_VALUE

        for (i in 0 until geometry.size - 1) {
            val p1 = geometry[i]
            val p2 = geometry[i + 1]
            val projected = projectPointOnSegment(lat, lon, p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            val dist = distanceBetweenMeters(lat, lon, projected.latitude, projected.longitude)
            if (dist < minDistance) {
                minDistance = dist
                bestPoint = projected
            }
        }

        return if (minDistance <= MAX_ROUTE_SNAP_DISTANCE_METERS) bestPoint else null
    }

    private fun projectPointOnSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): LocationPoint {
        val abX = (bLon - aLon) * cos(Math.toRadians(aLat))
        val abY = bLat - aLat
        val apX = (pLon - aLon) * cos(Math.toRadians(aLat))
        val apY = pLat - aLat

        val abLenSq = abX * abX + abY * abY
        if (abLenSq == 0.0) return LocationPoint(aLat, aLon)

        val t = ((apX * abX + apY * abY) / abLenSq).coerceIn(0.0, 1.0)
        return LocationPoint(aLat + t * (bLat - aLat), aLon + t * (bLon - aLon))
    }

    private fun distanceBetweenMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return 6371000.0 * c
    }
}
