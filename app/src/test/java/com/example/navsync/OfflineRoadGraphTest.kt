package com.example.navsync

import com.example.navsync.data.db.DbRoadNode
import com.example.navsync.data.db.DbRoadSegment
import com.example.navsync.repository.LocationPoint
import com.example.navsync.repository.OfflineRoadGraph
import com.example.navsync.repository.OfflineRoutingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfflineRoadGraphTest {

    @Test
    fun testRoadGraphRoutingAndSynthesis() = runBlocking {
        val graph = OfflineRoadGraph()
        val routingEngine = OfflineRoutingEngine(graph)

        val nodes = listOf(
            DbRoadNode(1L, "reg1", 18.520, 73.850),
            DbRoadNode(2L, "reg1", 18.525, 73.855),
            DbRoadNode(3L, "reg1", 18.530, 73.860)
        )

        val segments = listOf(
            DbRoadSegment(
                segmentId = 101L,
                regionId = "reg1",
                fromNodeId = 1L,
                toNodeId = 2L,
                startLat = 18.520,
                startLon = 73.850,
                endLat = 18.525,
                endLon = 73.855,
                geometry = listOf(LocationPoint(18.520, 73.850), LocationPoint(18.525, 73.855)),
                lengthMeters = 800.0,
                bearingDegrees = 45f,
                roadName = "Main Road",
                roadType = "primary",
                oneWay = false,
                maxSpeedKmh = 50,
                curvature = 0f,
                connectedSegmentIds = listOf(102L)
            ),
            DbRoadSegment(
                segmentId = 102L,
                regionId = "reg1",
                fromNodeId = 2L,
                toNodeId = 3L,
                startLat = 18.525,
                startLon = 73.855,
                endLat = 18.530,
                endLon = 73.860,
                geometry = listOf(LocationPoint(18.525, 73.855), LocationPoint(18.530, 73.860)),
                lengthMeters = 900.0,
                bearingDegrees = 45f,
                roadName = "Station Road",
                roadType = "primary",
                oneWay = false,
                maxSpeedKmh = 50,
                curvature = 0f,
                connectedSegmentIds = emptyList()
            )
        )

        graph.loadFromDbData(nodes, segments)
        assertNotNull("Nearest segment to node 1 should exist", graph.getNearestRoadSegment(18.520, 73.850))

        val routeResult = routingEngine.calculateOfflineRoute(18.520, 73.850, 18.530, 73.860)
        assertTrue("Offline route calculation must succeed", routeResult.isSuccess)
        val route = routeResult.getOrNull()?.primaryRoute
        assertNotNull("Primary route should not be null", route)
        assertTrue("Route should contain waypoints", (route?.geometry?.size ?: 0) >= 2)
        assertTrue("Total distance should be positive", (route?.distanceMeters ?: 0.0) > 0)
    }
}
