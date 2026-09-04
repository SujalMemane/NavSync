package com.example.navsync.services

import android.location.Location
import android.util.Log
import com.example.navsync.repository.LocationPoint
import com.example.navsync.sensor.GnssStatusState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

enum class NavigationMode {
    IDLE,
    SEARCHING,
    ROUTE_PREVIEW,
    ROUTE_LOADING,
    READY,
    NAVIGATING,
    REROUTING,
    GPS_DEGRADED,
    GPS_LOST,
    ARRIVED,
    ERROR
}

data class NavEngineState(
    val mode: NavigationMode = NavigationMode.IDLE,
    val currentPosition: NavigationPosition? = null,
    val gpsStatus: GnssStatusState = GnssStatusState.WAITING_FIX,
    val activeRoute: Route? = null,
    val alternativeRoutes: List<Route> = emptyList(),
    val selectedAlternativeIndex: Int = 0,
    val currentStepIndex: Int = 0,
    val nextStep: RouteStep? = null,
    val distanceToNextTurnMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val etaFormatted: String = "--",
    val speedKmh: Float = 0.0f,
    val rawSpeedMps: Float = 0.0f,
    val derivedSpeedMps: Float = 0.0f,
    val isStationary: Boolean = true,
    val displacementMeters: Float = 0.0f,
    val headingDegrees: Float = -1f,
    val progressPercent: Float = 0.0f,
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val isFollowingMap: Boolean = true,
    val errorMessage: String? = null,
    val destinationPlace: PlaceResult? = null
)

class NavigationEngine(
    private val routingProvider: RoutingProvider,
    private val geocodingProvider: GeocodingProvider
) {

    companion object {
        private const val TAG = "NAVSYNC_NAV"
        private const val OFF_ROUTE_THRESHOLD_METERS = 35.0
        private const val OFF_ROUTE_CONSECUTIVE_LIMIT = 3
        private const val ARRIVAL_THRESHOLD_METERS = 25.0
    }

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _engineState = MutableStateFlow(NavEngineState())
    val engineState: StateFlow<NavEngineState> = _engineState.asStateFlow()

    private var offRouteCount = 0
    private var isRerouteInFlight = false
    private var routeJob: Job? = null
    private var rerouteJob: Job? = null

    fun updatePosition(position: NavigationPosition, gpsStatus: GnssStatusState) {
        val currentState = _engineState.value
        val nowMs = System.currentTimeMillis()

        val effectiveGpsStatus = when {
            position.source == PositionSource.DEAD_RECKONING || gpsStatus == GnssStatusState.DEAD_RECKONING -> GnssStatusState.DEAD_RECKONING
            else -> gpsStatus
        }

        val speed = position.displaySpeedKmh
        val heading = if (position.bearing >= 0f) position.bearing else currentState.headingDegrees

        if (effectiveGpsStatus == GnssStatusState.GNSS_LOST && currentState.mode == NavigationMode.NAVIGATING) {
            Log.w(TAG, "GPS SIGNAL LOST during active navigation")
        }

        if (currentState.mode != NavigationMode.NAVIGATING && currentState.mode != NavigationMode.REROUTING) {
            _engineState.value = currentState.copy(
                currentPosition = position,
                gpsStatus = effectiveGpsStatus,
                speedKmh = speed,
                rawSpeedMps = position.rawSpeedMps,
                derivedSpeedMps = position.filteredSpeedMps,
                isStationary = position.isStationary,
                displacementMeters = position.displacementMeters,
                headingDegrees = heading
            )
            return
        }

        val activeRoute = currentState.activeRoute
        if (activeRoute == null || activeRoute.geometry.isEmpty()) {
            _engineState.value = currentState.copy(
                currentPosition = position,
                gpsStatus = effectiveGpsStatus,
                speedKmh = speed,
                rawSpeedMps = position.rawSpeedMps,
                derivedSpeedMps = position.filteredSpeedMps,
                isStationary = position.isStationary,
                displacementMeters = position.displacementMeters,
                headingDegrees = heading
            )
            return
        }

        // 1. Arrival Check
        val distToDest = distanceBetweenMeters(
            position.latitude, position.longitude,
            activeRoute.destination.latitude, activeRoute.destination.longitude
        )

        if (distToDest <= ARRIVAL_THRESHOLD_METERS) {
            Log.d(TAG, "Destination reached! distToDest=$distToDest state=ARRIVED")
            _engineState.value = currentState.copy(
                mode = NavigationMode.ARRIVED,
                currentPosition = position,
                gpsStatus = effectiveGpsStatus,
                speedKmh = 0.0f,
                isStationary = true,
                headingDegrees = heading,
                remainingDistanceMeters = 0.0,
                remainingDurationSeconds = 0.0,
                progressPercent = 100.0f,
                distanceToNextTurnMeters = 0.0,
                nextStep = RouteStep("Arrived at destination", ManeuverType.ARRIVE, "", 0.0, 0.0, activeRoute.destination.latitude, activeRoute.destination.longitude)
            )
            return
        }

        // 2. Off-Route Evaluation
        val minDistToPolyline = computeMinDistanceToPolyline(
            LocationPoint(position.latitude, position.longitude),
            activeRoute.geometry
        )

        val isPointOffRoute = minDistToPolyline > OFF_ROUTE_THRESHOLD_METERS

        if (isPointOffRoute) {
            offRouteCount++
            Log.d(TAG, "offRouteCount=$offRouteCount minDist=$minDistToPolyline threshold=$OFF_ROUTE_THRESHOLD_METERS")
        } else {
            offRouteCount = 0
        }

        val sustainedOffRoute = offRouteCount >= OFF_ROUTE_CONSECUTIVE_LIMIT

        if (sustainedOffRoute && !isRerouteInFlight) {
            Log.w(TAG, "Sustained OFF ROUTE detected! Triggering rerouting... minDist=$minDistToPolyline")
            triggerReroute(position, activeRoute.destination)
        }

        // 3. Navigation Metrics Calculation (Steps, Turn Distance, Progress)
        val (closestStepIndex, distToStep, remDist) = calculateRouteProgress(
            position,
            activeRoute
        )

        val progress = if (activeRoute.distanceMeters > 0) {
            ((activeRoute.distanceMeters - remDist) / activeRoute.distanceMeters * 100.0).coerceIn(0.0, 100.0).toFloat()
        } else 0f

        val currentStep = activeRoute.steps.getOrNull(closestStepIndex)
        val nextStep = activeRoute.steps.getOrNull(closestStepIndex + 1) ?: currentStep

        val remSec = if (speed > 5.0f) (remDist / (speed / 3.6)) else (remDist / 10.0) // fallback ~36km/h
        val etaStr = formatEtaTime(remSec)

        _engineState.value = currentState.copy(
            currentPosition = position,
            gpsStatus = effectiveGpsStatus,
            speedKmh = speed,
            rawSpeedMps = position.rawSpeedMps,
            derivedSpeedMps = position.filteredSpeedMps,
            isStationary = position.isStationary,
            displacementMeters = position.displacementMeters,
            headingDegrees = heading,
            currentStepIndex = closestStepIndex,
            nextStep = nextStep,
            distanceToNextTurnMeters = distToStep,
            remainingDistanceMeters = remDist,
            remainingDurationSeconds = remSec,
            etaFormatted = etaStr,
            progressPercent = progress,
            isOffRoute = sustainedOffRoute,
            isRerouting = isRerouteInFlight
        )

        Log.d(TAG, "nextTurn=${nextStep?.maneuverType} distanceToTurn=${distToStep.toInt()}m remDist=${(remDist/1000).toInt()}km eta=$etaStr offRoute=$sustainedOffRoute rerouting=$isRerouteInFlight")
    }

    fun requestRouteToDestination(destination: PlaceResult, currentLocation: LocationPoint) {
        routeJob?.cancel()
        rerouteJob?.cancel()
        isRerouteInFlight = false
        _engineState.value = _engineState.value.copy(
            mode = NavigationMode.ROUTE_LOADING,
            destinationPlace = destination,
            errorMessage = null
        )

        routeJob = engineScope.launch {
            val result = routingProvider.calculateRoute(currentLocation, LocationPoint(destination.latitude, destination.longitude))
            if (!isActive) return@launch
            if (result.isSuccess) {
                val primary = result.primaryRoute
                _engineState.value = _engineState.value.copy(
                    mode = NavigationMode.ROUTE_PREVIEW,
                    activeRoute = primary,
                    alternativeRoutes = result.routes,
                    selectedAlternativeIndex = result.selectedRouteIndex,
                    remainingDistanceMeters = primary?.distanceMeters ?: 0.0,
                    remainingDurationSeconds = primary?.durationSeconds ?: 0.0,
                    etaFormatted = primary?.formattedEtaString ?: "--",
                    errorMessage = null
                )
                Log.d(TAG, "routeCalculated success=true routeName=${primary?.name} dist=${primary?.distanceMeters}m steps=${primary?.steps?.size}")
            } else {
                _engineState.value = _engineState.value.copy(
                    mode = NavigationMode.ERROR,
                    errorMessage = result.errorMessage ?: "Unable to calculate route"
                )
                Log.e(TAG, "routeCalculated failed err=${result.errorMessage}")
            }
        }
    }

    fun selectAlternativeRoute(index: Int) {
        val alts = _engineState.value.alternativeRoutes
        if (index in alts.indices) {
            val selected = alts[index]
            _engineState.value = _engineState.value.copy(
                selectedAlternativeIndex = index,
                activeRoute = selected,
                remainingDistanceMeters = selected.distanceMeters,
                remainingDurationSeconds = selected.durationSeconds,
                etaFormatted = selected.formattedEtaString
            )
        }
    }

    fun startNavigation() {
        if (_engineState.value.activeRoute != null) {
            _engineState.value = _engineState.value.copy(
                mode = NavigationMode.NAVIGATING,
                isFollowingMap = true
            )
            Log.d(TAG, "Navigation started mode=NAVIGATING")
        }
    }

    fun stopNavigation() {
        routeJob?.cancel()
        routeJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        offRouteCount = 0
        isRerouteInFlight = false
        _engineState.value = NavEngineState(
            mode = NavigationMode.IDLE,
            currentPosition = _engineState.value.currentPosition,
            gpsStatus = _engineState.value.gpsStatus
        )
        Log.d(TAG, "Navigation stopped mode=IDLE")
    }

    fun setMapFollowing(following: Boolean) {
        _engineState.value = _engineState.value.copy(isFollowingMap = following)
    }

    private fun triggerReroute(currentPos: NavigationPosition, destination: LocationPoint) {
        if (_engineState.value.mode == NavigationMode.IDLE) return
        isRerouteInFlight = true
        _engineState.value = _engineState.value.copy(
            isRerouting = true,
            mode = NavigationMode.REROUTING
        )

        rerouteJob?.cancel()
        rerouteJob = engineScope.launch {
            val result = routingProvider.reroute(
                LocationPoint(currentPos.latitude, currentPos.longitude),
                destination
            )
            if (!isActive) return@launch
            if (result.isSuccess && result.primaryRoute != null) {
                val newRoute = result.primaryRoute
                offRouteCount = 0
                isRerouteInFlight = false
                _engineState.value = _engineState.value.copy(
                    mode = NavigationMode.NAVIGATING,
                    activeRoute = newRoute,
                    isOffRoute = false,
                    isRerouting = false
                )
                Log.d(TAG, "Rerouting succeeded! New route dist=${newRoute?.distanceMeters}m")
            } else {
                isRerouteInFlight = false
                _engineState.value = _engineState.value.copy(
                    isRerouting = false
                )
                Log.e(TAG, "Rerouting failed: ${result.errorMessage}")
            }
        }
    }

    private fun calculateRouteProgress(
        pos: NavigationPosition,
        route: Route
    ): Triple<Int, Double, Double> {
        val geom = route.geometry
        if (geom.isEmpty()) return Triple(0, 0.0, 0.0)

        // Find closest point index on geometry
        var minSqDist = Double.MAX_VALUE
        var closestGeomIndex = 0
        for (i in geom.indices) {
            val d = distanceBetweenMeters(pos.latitude, pos.longitude, geom[i].latitude, geom[i].longitude)
            if (d < minSqDist) {
                minSqDist = d
                closestGeomIndex = i
            }
        }

        // Compute remaining distance along remaining geometry points
        var remDist = 0.0
        for (i in closestGeomIndex until geom.size - 1) {
            remDist += distanceBetweenMeters(geom[i].latitude, geom[i].longitude, geom[i+1].latitude, geom[i+1].longitude)
        }

        // Find closest step maneuver point
        var minStepDist = Double.MAX_VALUE
        var stepIndex = 0
        for (s in route.steps.indices) {
            val step = route.steps[s]
            val d = distanceBetweenMeters(pos.latitude, pos.longitude, step.latitude, step.longitude)
            if (d < minStepDist) {
                minStepDist = d
                stepIndex = s
            }
        }

        // Distance to next maneuver
        val nextStep = route.steps.getOrNull(stepIndex + 1) ?: route.steps.getOrNull(stepIndex)
        val distToTurn = if (nextStep != null) {
            distanceBetweenMeters(pos.latitude, pos.longitude, nextStep.latitude, nextStep.longitude)
        } else 0.0

        return Triple(stepIndex, distToTurn, remDist)
    }

    private fun computeMinDistanceToPolyline(point: LocationPoint, polyline: List<LocationPoint>): Double {
        if (polyline.size < 2) return Double.MAX_VALUE
        var minDist = Double.MAX_VALUE

        for (i in 0 until polyline.size - 1) {
            val segStart = polyline[i]
            val segEnd = polyline[i + 1]
            val dist = distanceToSegmentMeters(point, segStart, segEnd)
            if (dist < minDist) {
                minDist = dist
            }
        }
        return minDist
    }

    private fun distanceToSegmentMeters(p: LocationPoint, a: LocationPoint, b: LocationPoint): Double {
        val l2 = distanceBetweenMeters(a.latitude, a.longitude, b.latitude, b.longitude).pow(2)
        if (l2 == 0.0) return distanceBetweenMeters(p.latitude, p.longitude, a.latitude, a.longitude)

        val t = (((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / ( (b.latitude - a.latitude).pow(2) + (b.longitude - a.longitude).pow(2) )).coerceIn(0.0, 1.0)
        val projLat = a.latitude + t * (b.latitude - a.latitude)
        val projLon = a.longitude + t * (b.longitude - a.longitude)

        return distanceBetweenMeters(p.latitude, p.longitude, projLat, projLon)
    }

    private fun distanceBetweenMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun formatEtaTime(durationSec: Double): String {
        val etaMillis = System.currentTimeMillis() + (durationSec * 1000).toLong()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date(etaMillis))
    }
}
