package com.example.navsync.repository

import android.location.Location
import android.util.Log
import com.example.navsync.data.db.DbRoadNode
import com.example.navsync.data.db.DbRoadSegment
import kotlin.math.*

data class RoadNode(
    val nodeId: Long,
    val latitude: Double,
    val longitude: Double
)

data class RoadSegment(
    val segmentId: Long,
    val fromNodeId: Long,
    val toNodeId: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val geometry: List<LocationPoint>,
    val lengthMeters: Double,
    val bearingDegrees: Float,
    val roadName: String,
    val roadType: String,
    val oneWay: Boolean,
    val maxSpeedKmh: Int,
    val curvature: Float,
    val connectedSegmentIds: List<Long>
)

class OfflineRoadGraph {

    private val nodesMap = mutableMapOf<Long, RoadNode>()
    private val segmentsMap = mutableMapOf<Long, RoadSegment>()
    private val adjacencyList = mutableMapOf<Long, MutableList<RoadSegment>>() // nodeId -> outgoing segments

    companion object {
        private const val TAG = "NAVSYNC_ROAD_GRAPH"
    }

    fun loadFromDbData(dbNodes: List<DbRoadNode>, dbSegments: List<DbRoadSegment>) {
        nodesMap.clear()
        segmentsMap.clear()
        adjacencyList.clear()

        dbNodes.forEach { dbNode ->
            nodesMap[dbNode.nodeId] = RoadNode(dbNode.nodeId, dbNode.latitude, dbNode.longitude)
        }

        dbSegments.forEach { seg ->
            // Ensure endpoint nodes exist in nodesMap even if omitted from dbNodes
            if (!nodesMap.containsKey(seg.fromNodeId)) {
                nodesMap[seg.fromNodeId] = RoadNode(seg.fromNodeId, seg.startLat, seg.startLon)
            }
            if (!nodesMap.containsKey(seg.toNodeId)) {
                nodesMap[seg.toNodeId] = RoadNode(seg.toNodeId, seg.endLat, seg.endLon)
            }

            val domainSeg = RoadSegment(
                segmentId = seg.segmentId,
                fromNodeId = seg.fromNodeId,
                toNodeId = seg.toNodeId,
                startLat = seg.startLat,
                startLon = seg.startLon,
                endLat = seg.endLat,
                endLon = seg.endLon,
                geometry = seg.geometry,
                lengthMeters = seg.lengthMeters,
                bearingDegrees = seg.bearingDegrees,
                roadName = seg.roadName,
                roadType = seg.roadType,
                oneWay = seg.oneWay,
                maxSpeedKmh = seg.maxSpeedKmh,
                curvature = seg.curvature,
                connectedSegmentIds = seg.connectedSegmentIds
            )
            segmentsMap[seg.segmentId] = domainSeg

            // Add to forward adjacency list
            adjacencyList.getOrPut(seg.fromNodeId) { mutableListOf() }.add(domainSeg)

            // If two-way road, add reverse traversal
            if (!seg.oneWay) {
                val reverseSeg = domainSeg.copy(
                    fromNodeId = seg.toNodeId,
                    toNodeId = seg.fromNodeId,
                    startLat = seg.endLat,
                    startLon = seg.endLon,
                    endLat = seg.startLat,
                    endLon = seg.startLon,
                    geometry = seg.geometry.reversed(),
                    bearingDegrees = (seg.bearingDegrees + 180f) % 360f
                )
                adjacencyList.getOrPut(seg.toNodeId) { mutableListOf() }.add(reverseSeg)
            }
        }
        Log.d(TAG, "Loaded RoadGraph: ${nodesMap.size} nodes, ${segmentsMap.size} segments into graph")
    }

    fun getNode(nodeId: Long): RoadNode? = nodesMap[nodeId]

    fun getSegment(segmentId: Long): RoadSegment? = segmentsMap[segmentId]

    fun getOutgoingSegments(nodeId: Long): List<RoadSegment> = adjacencyList[nodeId] ?: emptyList()

    fun getNearestRoadSegment(lat: Double, lon: Double, maxRadiusMeters: Double = 5000.0): RoadSegment? {
        var minDistance = Double.MAX_VALUE
        var nearestSegment: RoadSegment? = null
        var closestOverall: RoadSegment? = null
        var minOverallDist = Double.MAX_VALUE

        for (seg in segmentsMap.values) {
            val distToStart = distanceMeters(lat, lon, seg.startLat, seg.startLon)
            val distToEnd = distanceMeters(lat, lon, seg.endLat, seg.endLon)
            val approxDist = min(distToStart, distToEnd)

            if (approxDist < minOverallDist) {
                minOverallDist = approxDist
                closestOverall = seg
            }

            if (approxDist < minDistance && approxDist <= maxRadiusMeters) {
                minDistance = approxDist
                nearestSegment = seg
            }
        }
        return nearestSegment ?: closestOverall
    }

    fun getCandidateRoads(lat: Double, lon: Double, maxRadiusMeters: Double = 300.0): List<RoadSegment> {
        val candidates = mutableListOf<RoadSegment>()
        for (seg in segmentsMap.values) {
            val dist = distanceMeters(lat, lon, seg.startLat, seg.startLon)
            if (dist <= maxRadiusMeters) {
                candidates.add(seg)
            }
        }
        return candidates
    }

    fun getConnectedSegments(segmentId: Long): List<RoadSegment> {
        val seg = segmentsMap[segmentId] ?: return emptyList()
        return getOutgoingSegments(seg.toNodeId)
    }

    fun getRoadGeometry(segmentId: Long): List<LocationPoint> {
        return segmentsMap[segmentId]?.geometry ?: emptyList()
    }

    fun getRoadBearing(segmentId: Long): Float {
        return segmentsMap[segmentId]?.bearingDegrees ?: 0f
    }

    fun getRoadLength(segmentId: Long): Double {
        return segmentsMap[segmentId]?.lengthMeters ?: 0.0
    }

    fun getRoadName(segmentId: Long): String {
        return segmentsMap[segmentId]?.roadName ?: "Local Road"
    }

    fun getAllowedDirection(segmentId: Long): String {
        val seg = segmentsMap[segmentId] ?: return "BOTH"
        return if (seg.oneWay) "ONE_WAY" else "TWO_WAY"
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    val totalNodes: Int get() = nodesMap.size
    val totalSegments: Int get() = segmentsMap.size
    val totalDirectedEdges: Int get() = adjacencyList.values.sumOf { it.size }
    fun getAllNodes(): List<RoadNode> = nodesMap.values.toList()
    fun getAllSegments(): List<RoadSegment> = segmentsMap.values.toList()
}
