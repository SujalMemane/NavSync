package com.example.navsync.repository

import android.location.Location
import android.util.Log
import com.example.navsync.services.ManeuverType
import com.example.navsync.services.Route
import com.example.navsync.services.RouteResult
import com.example.navsync.services.RouteStep
import java.util.PriorityQueue
import java.util.Locale

class OfflineRoutingEngine(private val roadGraph: OfflineRoadGraph) {

    companion object {
        private const val TAG = "NAVSYNC_OFFLINE_ROUTER"
    }

    suspend fun calculateOfflineRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        destName: String = "Destination"
    ): Result<RouteResult> {
        val startSegment = roadGraph.getNearestRoadSegment(originLat, originLon, 1000.0)
            ?: return Result.failure(Exception("Unable to find a nearby road near origin in downloaded map."))

        val destSegment = roadGraph.getNearestRoadSegment(destLat, destLon, 1000.0)
            ?: return Result.failure(Exception("Unable to find a nearby road near destination in downloaded map."))

        val startNodeId = startSegment.fromNodeId
        val targetNodeId = destSegment.toNodeId

        val startNode = roadGraph.getNode(startNodeId)
        val targetNode = roadGraph.getNode(targetNodeId)

        if (startNode == null || targetNode == null) {
            return Result.failure(Exception("Invalid road graph nodes for routing."))
        }

        // A* Shortest-Path Algorithm
        val gScore = mutableMapOf<Long, Double>().withDefault { Double.MAX_VALUE }
        val fScore = mutableMapOf<Long, Double>().withDefault { Double.MAX_VALUE }
        val cameFrom = mutableMapOf<Long, Pair<Long, RoadSegment>>() // currNode -> (prevNode, segmentUsed)

        val openSet = PriorityQueue<Long>(compareBy { fScore.getValue(it) })

        gScore[startNodeId] = 0.0
        fScore[startNodeId] = heuristic(startNode.latitude, startNode.longitude, targetNode.latitude, targetNode.longitude)
        openSet.add(startNodeId)

        var found = false

        while (openSet.isNotEmpty()) {
            val current = openSet.poll() ?: break

            if (current == targetNodeId) {
                found = true
                break
            }

            val currG = gScore.getValue(current)

            for (seg in roadGraph.getOutgoingSegments(current)) {
                val neighbor = seg.toNodeId
                val tentativeG = currG + seg.lengthMeters

                if (tentativeG < gScore.getValue(neighbor)) {
                    cameFrom[neighbor] = Pair(current, seg)
                    gScore[neighbor] = tentativeG

                    val neighborNode = roadGraph.getNode(neighbor)
                    val h = if (neighborNode != null) {
                        heuristic(neighborNode.latitude, neighborNode.longitude, targetNode.latitude, targetNode.longitude)
                    } else 0.0

                    fScore[neighbor] = tentativeG + h
                    if (!openSet.contains(neighbor)) {
                        openSet.add(neighbor)
                    }
                }
            }
        }

        if (!found && startNodeId != targetNodeId) {
            Log.w(TAG, "A* path not found between $startNodeId and $targetNodeId. Falling back to direct road segment path.")
        }

        // Reconstruct path
        val pathSegments = mutableListOf<RoadSegment>()
        var curr = targetNodeId
        while (cameFrom.containsKey(curr)) {
            val (prev, seg) = cameFrom.getValue(curr)
            pathSegments.add(0, seg)
            curr = prev
        }

        if (pathSegments.isEmpty()) {
            pathSegments.add(startSegment)
            if (startSegment.segmentId != destSegment.segmentId) {
                pathSegments.add(destSegment)
            }
        }

        val fullGeometry = mutableListOf<LocationPoint>()
        var totalDistMeters = 0.0

        pathSegments.forEach { seg ->
            fullGeometry.addAll(seg.geometry)
            totalDistMeters += seg.lengthMeters
        }

        val durationSeconds = Math.round(totalDistMeters / 13.88).coerceAtLeast(10L) // ~50 km/h average speed

        val steps = mutableListOf<RouteStep>()
        pathSegments.forEachIndexed { idx, seg ->
            val maneuver = if (idx == 0) ManeuverType.DEPART else if (idx == pathSegments.size - 1) ManeuverType.ARRIVE else ManeuverType.STRAIGHT
            steps.add(
                RouteStep(
                    instruction = if (idx == 0) "Depart on ${seg.roadName}" else "Continue on ${seg.roadName}",
                    maneuverType = maneuver,
                    roadName = seg.roadName,
                    distanceMeters = seg.lengthMeters,
                    durationSeconds = Math.round(seg.lengthMeters / 13.88).toDouble(),
                    latitude = seg.startLat,
                    longitude = seg.startLon,
                    stepGeometry = seg.geometry
                )
            )
        }

        val routeObj = Route(
            id = "offline_route_${System.currentTimeMillis()}",
            name = "Offline Route",
            distanceMeters = totalDistMeters,
            durationSeconds = durationSeconds.toDouble(),
            geometry = fullGeometry,
            steps = steps,
            origin = LocationPoint(originLat, originLon),
            destination = LocationPoint(destLat, destLon),
            summary = "Offline Route via ${pathSegments.firstOrNull()?.roadName ?: "Local Roads"}",
            source = "OFFLINE"
        )

        val routeResult = RouteResult(
            routes = listOf(routeObj),
            selectedRouteIndex = 0,
            source = "OFFLINE"
        )

        Log.d(TAG, "OFFLINE_ROUTE_CALCULATED dist=${String.format(Locale.US, "%.2f km", totalDistMeters / 1000.0)} duration=${routeObj.formattedEtaString} steps=${steps.size}")
        return Result.success(routeResult)
    }

    private fun heuristic(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }
}
