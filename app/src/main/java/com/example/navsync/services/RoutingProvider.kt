package com.example.navsync.services

import android.content.Context
import android.util.Log
import com.example.navsync.repository.LocationPoint
import com.example.navsync.repository.OfflineMapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class ManeuverType {
    START,
    DEPART,
    STRAIGHT,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    UTURN,
    ROUNDABOUT,
    RAMP,
    FORK,
    ARRIVE,
    UNKNOWN
}

data class RouteStep(
    val instruction: String,
    val maneuverType: ManeuverType,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val latitude: Double,
    val longitude: Double,
    val stepGeometry: List<LocationPoint> = emptyList(),
    val location: LocationPoint = LocationPoint(latitude, longitude)
)

data class Route(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<LocationPoint>,
    val steps: List<RouteStep>,
    val origin: LocationPoint,
    val destination: LocationPoint,
    val summary: String = "",
    val source: String = "ONLINE"
) {
    val distanceKm: Double
        get() = distanceMeters / 1000.0

    val durationMinutes: Int
        get() = Math.round(durationSeconds / 60.0).toInt()

    val formattedEtaString: String
        get() {
            val totalSec = Math.round(durationSeconds).toLong()
            val hours = totalSec / 3600
            val mins = (totalSec % 3600) / 60
            return if (hours > 0) {
                "${hours}h ${mins}m"
            } else {
                "${mins} min"
            }
        }
}

data class RouteOptions(
    val profile: String = "driving",
    val alternatives: Boolean = true
)

data class RouteResult(
    val routes: List<Route> = emptyList(),
    val selectedRouteIndex: Int = 0,
    val errorMessage: String? = null,
    val source: String = "ONLINE"
) {
    val isSuccess: Boolean
        get() = errorMessage == null && routes.isNotEmpty()

    val primaryRoute: Route?
        get() = routes.getOrNull(selectedRouteIndex) ?: routes.firstOrNull()
}

interface RoutingProvider {
    val providerName: String
    suspend fun calculateRoute(origin: LocationPoint, destination: LocationPoint, options: RouteOptions = RouteOptions()): RouteResult
    suspend fun reroute(currentLocation: LocationPoint, destination: LocationPoint): RouteResult
    suspend fun getRouteAlternatives(origin: LocationPoint, destination: LocationPoint): List<Route>
}

class OnlineRoutingProvider : RoutingProvider {

    override val providerName: String = "OSRM Online Routing (OpenStreetMap)"

    companion object {
        private const val TAG = "NAVSYNC_ROUTING"
        private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1/driving/"
    }

    override suspend fun calculateRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        options: RouteOptions
    ): RouteResult = withContext(Dispatchers.IO) {
        val alternativesParam = if (options.alternatives) "true" else "false"
        val urlString = "$OSRM_BASE_URL${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true&alternatives=$alternativesParam"

        Log.d(TAG, "calculateRoute started origin=${origin.latitude},${origin.longitude} dest=${destination.latitude},${destination.longitude} url=$urlString")

        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NavSync-NavigationApp/1.0")
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(jsonString)
                val code = jsonObj.optString("code")

                if (code == "Ok") {
                    val routesArray = jsonObj.getJSONArray("routes")
                    val parsedRoutes = mutableListOf<Route>()

                    for (r in 0 until routesArray.length()) {
                        val routeObj = routesArray.getJSONObject(r)
                        val totalDist = routeObj.getDouble("distance")
                        val totalDuration = routeObj.getDouble("duration")

                        // Extract main polyline geometry
                        val mainGeoObj = routeObj.getJSONObject("geometry")
                        val mainCoords = mainGeoObj.getJSONArray("coordinates")
                        val geometryPoints = mutableListOf<LocationPoint>()
                        for (i in 0 until mainCoords.length()) {
                            val coordPair = mainCoords.getJSONArray(i)
                            val lon = coordPair.getDouble(0)
                            val lat = coordPair.getDouble(1)
                            geometryPoints.add(LocationPoint(latitude = lat, longitude = lon))
                        }

                        // Extract legs & steps
                        val legs = routeObj.getJSONArray("legs")
                        val routeSteps = mutableListOf<RouteStep>()
                        var legSummary = ""

                        if (legs.length() > 0) {
                            val leg = legs.getJSONObject(0)
                            legSummary = leg.optString("summary", "")
                            val stepsArray = leg.getJSONArray("steps")

                            for (s in 0 until stepsArray.length()) {
                                val stepObj = stepsArray.getJSONObject(s)
                                val stepDist = stepObj.getDouble("distance")
                                val stepDuration = stepObj.getDouble("duration")
                                val roadName = stepObj.optString("name", "").ifEmpty { "unnamed road" }

                                val maneuverObj = stepObj.getJSONObject("maneuver")
                                val manTypeStr = maneuverObj.optString("type", "")
                                val manModStr = maneuverObj.optString("modifier", "")
                                val manLocation = maneuverObj.getJSONArray("location")
                                val stepLon = manLocation.getDouble(0)
                                val stepLat = manLocation.getDouble(1)

                                val maneuverType = parseManeuverType(manTypeStr, manModStr)
                                val instruction = buildInstruction(maneuverType, manTypeStr, manModStr, roadName, s == 0, s == stepsArray.length() - 1)

                                // Parse step geometry if available
                                val stepGeoPoints = mutableListOf<LocationPoint>()
                                if (stepObj.has("geometry")) {
                                    val stepGeoObj = stepObj.getJSONObject("geometry")
                                    val stepCoords = stepGeoObj.getJSONArray("coordinates")
                                    for (c in 0 until stepCoords.length()) {
                                        val pair = stepCoords.getJSONArray(c)
                                        stepGeoPoints.add(LocationPoint(latitude = pair.getDouble(1), longitude = pair.getDouble(0)))
                                    }
                                }

                                routeSteps.add(
                                    RouteStep(
                                        instruction = instruction,
                                        maneuverType = maneuverType,
                                        roadName = roadName,
                                        distanceMeters = stepDist,
                                        durationSeconds = stepDuration,
                                        latitude = stepLat,
                                        longitude = stepLon,
                                        stepGeometry = stepGeoPoints
                                    )
                                )
                            }
                        }

                        val routeName = if (r == 0) "Fastest Route" else "Alternative ${r}"
                        val fullSummary = if (legSummary.isNotEmpty()) "$routeName via $legSummary" else routeName

                        parsedRoutes.add(
                            Route(
                                id = "route_${r}_${System.currentTimeMillis()}",
                                name = routeName,
                                distanceMeters = totalDist,
                                durationSeconds = totalDuration,
                                geometry = geometryPoints,
                                steps = routeSteps,
                                origin = origin,
                                destination = destination,
                                summary = fullSummary,
                                source = "ONLINE"
                            )
                        )
                    }

                    Log.d(TAG, "routeReceived count=${parsedRoutes.size} distance=${parsedRoutes.firstOrNull()?.distanceMeters} duration=${parsedRoutes.firstOrNull()?.durationSeconds}")
                    return@withContext RouteResult(routes = parsedRoutes, selectedRouteIndex = 0, source = "ONLINE")
                } else {
                    Log.w(TAG, "OSRM returned code=$code")
                    return@withContext RouteResult(errorMessage = "No route found ($code)")
                }
            } else {
                Log.e(TAG, "OSRM HTTP Error code=$responseCode")
                return@withContext RouteResult(errorMessage = "Server error ($responseCode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Route request failed: ${e.message}", e)
            return@withContext RouteResult(errorMessage = "Network error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    override suspend fun reroute(currentLocation: LocationPoint, destination: LocationPoint): RouteResult {
        Log.d(TAG, "reroute triggered from current location lat=${currentLocation.latitude} lon=${currentLocation.longitude}")
        return calculateRoute(currentLocation, destination, RouteOptions(alternatives = false))
    }

    override suspend fun getRouteAlternatives(origin: LocationPoint, destination: LocationPoint): List<Route> {
        val result = calculateRoute(origin, destination, RouteOptions(alternatives = true))
        return result.routes
    }

    private fun parseManeuverType(type: String, modifier: String): ManeuverType {
        return when (type) {
            "depart" -> ManeuverType.START
            "arrive" -> ManeuverType.ARRIVE
            "roundabout", "rotary", "roundabout turn" -> ManeuverType.ROUNDABOUT
            "ramp", "on ramp", "off ramp" -> ManeuverType.RAMP
            "fork" -> ManeuverType.FORK
            "turn", "end of road", "new name" -> when (modifier) {
                "slight left" -> ManeuverType.TURN_SLIGHT_LEFT
                "left" -> ManeuverType.TURN_LEFT
                "sharp left" -> ManeuverType.TURN_SHARP_LEFT
                "slight right" -> ManeuverType.TURN_SLIGHT_RIGHT
                "right" -> ManeuverType.TURN_RIGHT
                "sharp right" -> ManeuverType.TURN_SHARP_RIGHT
                "uturn" -> ManeuverType.UTURN
                "straight" -> ManeuverType.STRAIGHT
                else -> ManeuverType.TURN_RIGHT
            }
            else -> ManeuverType.STRAIGHT
        }
    }

    private fun buildInstruction(
        type: ManeuverType,
        typeStr: String,
        modifierStr: String,
        roadName: String,
        isFirst: Boolean,
        isLast: Boolean
    ): String {
        if (isFirst) return "Head towards $roadName"
        if (isLast || type == ManeuverType.ARRIVE) return "Arrive at destination"

        val roadText = if (roadName.isNotEmpty() && roadName != "unnamed road") " onto $roadName" else ""

        return when (type) {
            ManeuverType.START -> "Head towards $roadName"
            ManeuverType.STRAIGHT -> "Continue straight$roadText"
            ManeuverType.TURN_SLIGHT_LEFT -> "Keep left$roadText"
            ManeuverType.TURN_LEFT -> "Turn left$roadText"
            ManeuverType.TURN_SHARP_LEFT -> "Turn sharp left$roadText"
            ManeuverType.TURN_SLIGHT_RIGHT -> "Keep right$roadText"
            ManeuverType.TURN_RIGHT -> "Turn right$roadText"
            ManeuverType.TURN_SHARP_RIGHT -> "Turn sharp right$roadText"
            ManeuverType.UTURN -> "Make a U-turn$roadText"
            ManeuverType.ROUNDABOUT -> "At the roundabout, take the exit$roadText"
            ManeuverType.RAMP -> "Take the ramp$roadText"
            ManeuverType.FORK -> "At the fork, stay on $roadName"
            ManeuverType.ARRIVE -> "Destination ahead"
            else -> "Proceed onto $roadName"
        }
    }
}

/**
 * Offline Routing Provider calculating local shortest-path routes using local A* routing engine.
 */
class OfflineRoutingProvider(private val offlineMapRepository: OfflineMapRepository) : RoutingProvider {

    override val providerName: String = "Offline Local A* Routing Engine"

    companion object {
        private const val TAG = "NAVSYNC_OFFLINE_ROUTING"
    }

    override suspend fun calculateRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        options: RouteOptions
    ): RouteResult {
        Log.d(TAG, "OFFLINE_ROUTING_REQUEST origin=${origin.latitude},${origin.longitude} dest=${destination.latitude},${destination.longitude} (ZERO network requests)")
        val res = offlineMapRepository.routingEngine.calculateOfflineRoute(
            originLat = origin.latitude,
            originLon = origin.longitude,
            destLat = destination.latitude,
            destLon = destination.longitude
        )

        return if (res.isSuccess) {
            res.getOrThrow()
        } else {
            val err = res.exceptionOrNull()?.message ?: "Offline route calculation failed"
            RouteResult(errorMessage = err, source = "OFFLINE")
        }
    }

    override suspend fun reroute(currentLocation: LocationPoint, destination: LocationPoint): RouteResult {
        return calculateRoute(currentLocation, destination)
    }

    override suspend fun getRouteAlternatives(origin: LocationPoint, destination: LocationPoint): List<Route> {
        val result = calculateRoute(origin, destination)
        return result.routes
    }
}

/**
 * RoutingProviderManager selecting Online vs Offline RoutingProvider based on NavigationModeManager state.
 */
class RoutingProviderManager(
    val onlineRoutingProvider: OnlineRoutingProvider,
    val offlineRoutingProvider: OfflineRoutingProvider,
    val navigationModeManager: NavigationModeManager
) : RoutingProvider {

    override val providerName: String
        get() = if (navigationModeManager.connectivityState.value == ConnectivityState.ONLINE) {
            onlineRoutingProvider.providerName
        } else {
            offlineRoutingProvider.providerName
        }

    override suspend fun calculateRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        options: RouteOptions
    ): RouteResult {
        return if (navigationModeManager.connectivityState.value == ConnectivityState.ONLINE) {
            val onlineResult = onlineRoutingProvider.calculateRoute(origin, destination, options)
            if (onlineResult.isSuccess) onlineResult else offlineRoutingProvider.calculateRoute(origin, destination, options)
        } else {
            offlineRoutingProvider.calculateRoute(origin, destination, options)
        }
    }

    override suspend fun reroute(currentLocation: LocationPoint, destination: LocationPoint): RouteResult {
        return calculateRoute(currentLocation, destination)
    }

    override suspend fun getRouteAlternatives(origin: LocationPoint, destination: LocationPoint): List<Route> {
        val res = calculateRoute(origin, destination)
        return res.routes
    }
}
