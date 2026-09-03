package com.example.navsync.repository

import android.content.Context
import android.util.Log
import com.example.navsync.data.db.DbOfflinePlace
import com.example.navsync.data.db.DbRoadNode
import com.example.navsync.data.db.DbRoadSegment
import com.example.navsync.data.db.OfflineMapDatabase
import com.example.navsync.data.db.OfflineRegionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos

class OfflineMapRepository(private val context: Context) {

    private val db = OfflineMapDatabase(context)
    val roadGraph = OfflineRoadGraph()
    val routingEngine = OfflineRoutingEngine(roadGraph)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _downloadedRegions = MutableStateFlow<List<OfflineRegionRecord>>(emptyList())
    val downloadedRegions: StateFlow<List<OfflineRegionRecord>> = _downloadedRegions.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _downloadStatusText = MutableStateFlow("")
    val downloadStatusText: StateFlow<String> = _downloadStatusText.asStateFlow()

    companion object {
        private const val TAG = "NAVSYNC_OFFLINE_REPO"
    }

    init {
        scope.launch {
            loadRegionsAndGraph()
        }
    }

    suspend fun loadRegionsAndGraph() = withContext(Dispatchers.IO) {
        var regions = db.getDownloadedRegions()

        // If database is brand new, pre-populate default test region (Pune Region)
        if (regions.isEmpty()) {
            val defaultRegion = OfflineRegionRecord(
                regionId = "pune_region_01",
                name = "Pune Offline Region",
                minLat = 18.45,
                minLon = 73.75,
                maxLat = 18.62,
                maxLon = 73.95,
                downloadTimeMs = System.currentTimeMillis(),
                sizeBytes = 43L * 1024L * 1024L, // 43 MB
                status = "DOWNLOADED"
            )
            db.saveRegion(defaultRegion)
            generateSampleRoadGraph("pune_region_01", 18.5204, 73.8567)
            regions = db.getDownloadedRegions()
        }

        _downloadedRegions.value = regions

        val dbNodes = mutableListOf<DbRoadNode>()
        val dbSegments = db.getAllSegments()

        // Load segments into road graph
        roadGraph.loadFromDbData(dbNodes, dbSegments)
        Log.d(TAG, "Offline graph initialized with ${dbSegments.size} segments.")
    }

    fun getBestOfflineRegionForLocation(lat: Double, lon: Double): OfflineRegionRecord? {
        return _downloadedRegions.value.find { region ->
            region.status == "DOWNLOADED" &&
                    lat >= region.minLat && lat <= region.maxLat &&
                    lon >= region.minLon && lon <= region.maxLon
        }
    }

    suspend fun downloadMapArea(
        name: String,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double
    ) = withContext(Dispatchers.IO) {
        _isDownloading.value = true
        _downloadProgress.value = 5
        _downloadStatusText.value = "Initializing download for $name..."

        val regionId = "region_${System.currentTimeMillis()}"
        val deltaLat = radiusKm / 111.0
        val deltaLon = radiusKm / (111.0 * cos(Math.toRadians(centerLat)))

        val minLat = centerLat - deltaLat
        val maxLat = centerLat + deltaLat
        val minLon = centerLon - deltaLon
        val maxLon = centerLon + deltaLon

        val regionRecord = OfflineRegionRecord(
            regionId = regionId,
            name = name,
            minLat = minLat,
            minLon = minLon,
            maxLat = maxLat,
            maxLon = maxLon,
            downloadTimeMs = System.currentTimeMillis(),
            sizeBytes = 0L,
            status = "DOWNLOADING"
        )
        db.saveRegion(regionRecord)

        // Asynchronous download simulation steps (Road data, Places, Tiles)
        _downloadProgress.value = 25
        _downloadStatusText.value = "Downloading road graph data..."
        kotlinx.coroutines.delay(600L)

        _downloadProgress.value = 55
        _downloadStatusText.value = "Downloading place labels & POIs..."
        kotlinx.coroutines.delay(600L)

        _downloadProgress.value = 85
        _downloadStatusText.value = "Building spatial index..."
        val estSizeBytes = generateSampleRoadGraph(regionId, centerLat, centerLon)
        kotlinx.coroutines.delay(400L)

        val completedRecord = regionRecord.copy(
            sizeBytes = estSizeBytes,
            status = "DOWNLOADED"
        )
        db.saveRegion(completedRecord)

        loadRegionsAndGraph()

        _downloadProgress.value = 100
        _downloadStatusText.value = "Download Complete ✓"
        _isDownloading.value = false
        Log.d(TAG, "OFFLINE_MAP_DOWNLOADED regionId=$regionId name=$name sizeBytes=$estSizeBytes")
    }

    suspend fun deleteRegion(regionId: String) = withContext(Dispatchers.IO) {
        db.deleteRegion(regionId)
        loadRegionsAndGraph()
        Log.d(TAG, "Deleted region regionId=$regionId")
    }

    private suspend fun generateSampleRoadGraph(regionId: String, centerLat: Double, centerLon: Double): Long {
        val nodes = mutableListOf<DbRoadNode>()
        val segments = mutableListOf<DbRoadSegment>()
        val places = mutableListOf<DbOfflinePlace>()

        // Generate synthetic grid of road segments & nodes around center lat/lon for offline testing
        val nodeGrid = Array(5) { r -> Array(5) { c -> (r * 5 + c + 100L) } }

        for (r in 0 until 5) {
            for (c in 0 until 5) {
                val nodeId = nodeGrid[r][c]
                val lat = centerLat + (r - 2) * 0.015
                val lon = centerLon + (c - 2) * 0.015
                nodes.add(DbRoadNode(nodeId, regionId, lat, lon))
            }
        }

        var segCounter = 1000L
        val roadNames = arrayOf("NH 48", "Main Street", "Station Road", "FC Road", "JM Road", "University Road", "Airport Road")
        val roadTypes = arrayOf("motorway", "primary", "secondary", "tertiary", "residential")

        // Create horizontal segments
        for (r in 0 until 5) {
            for (c in 0 until 4) {
                val u = nodeGrid[r][c]
                val v = nodeGrid[r][c + 1]
                val uNode = nodes.find { it.nodeId == u }!!
                val vNode = nodes.find { it.nodeId == v }!!
                val dist = distanceMeters(uNode.latitude, uNode.longitude, vNode.latitude, vNode.longitude)
                val roadName = roadNames[(r + c) % roadNames.size]
                val roadType = roadTypes[(r * c) % roadTypes.size]

                val seg = DbRoadSegment(
                    segmentId = segCounter++,
                    regionId = regionId,
                    fromNodeId = u,
                    toNodeId = v,
                    startLat = uNode.latitude,
                    startLon = uNode.longitude,
                    endLat = vNode.latitude,
                    endLon = vNode.longitude,
                    geometry = listOf(
                        LocationPoint(uNode.latitude, uNode.longitude),
                        LocationPoint((uNode.latitude + vNode.latitude) / 2, (uNode.longitude + vNode.longitude) / 2),
                        LocationPoint(vNode.latitude, vNode.longitude)
                    ),
                    lengthMeters = dist,
                    bearingDegrees = calculateBearing(uNode.latitude, uNode.longitude, vNode.latitude, vNode.longitude),
                    roadName = roadName,
                    roadType = roadType,
                    oneWay = (r % 2 == 1),
                    maxSpeedKmh = if (roadType == "motorway") 80 else 50,
                    curvature = 0.05f,
                    connectedSegmentIds = listOf(segCounter + 1)
                )
                segments.add(seg)
            }
        }

        // Create vertical segments
        for (r in 0 until 4) {
            for (c in 0 until 5) {
                val u = nodeGrid[r][c]
                val v = nodeGrid[r + 1][c]
                val uNode = nodes.find { it.nodeId == u }!!
                val vNode = nodes.find { it.nodeId == v }!!
                val dist = distanceMeters(uNode.latitude, uNode.longitude, vNode.latitude, vNode.longitude)
                val roadName = roadNames[(r * 2 + c) % roadNames.size]
                val roadType = roadTypes[(r + c * 2) % roadTypes.size]

                val seg = DbRoadSegment(
                    segmentId = segCounter++,
                    regionId = regionId,
                    fromNodeId = u,
                    toNodeId = v,
                    startLat = uNode.latitude,
                    startLon = uNode.longitude,
                    endLat = vNode.latitude,
                    endLon = vNode.longitude,
                    geometry = listOf(
                        LocationPoint(uNode.latitude, uNode.longitude),
                        LocationPoint(vNode.latitude, vNode.longitude)
                    ),
                    lengthMeters = dist,
                    bearingDegrees = calculateBearing(uNode.latitude, uNode.longitude, vNode.latitude, vNode.longitude),
                    roadName = roadName,
                    roadType = roadType,
                    oneWay = false,
                    maxSpeedKmh = 50,
                    curvature = 0.02f,
                    connectedSegmentIds = emptyList()
                )
                segments.add(seg)
            }
        }

        // Add places for offline search
        places.add(DbOfflinePlace(1, regionId, "Pune Railway Station", "Agarkar Nagar, Pune", centerLat + 0.01, centerLon + 0.01, "station"))
        places.add(DbOfflinePlace(2, regionId, "Pune International Airport", "Lohegaon, Pune", centerLat + 0.03, centerLon + 0.02, "airport"))
        places.add(DbOfflinePlace(3, regionId, "Sassoon Hospital", "Near Pune Station, Pune", centerLat + 0.008, centerLon + 0.012, "hospital"))
        places.add(DbOfflinePlace(4, regionId, "Fergusson College", "FC Road, Shivajinagar, Pune", centerLat - 0.01, centerLon - 0.01, "university"))
        places.add(DbOfflinePlace(5, regionId, "Phoenix Marketcity", "Viman Nagar, Pune", centerLat + 0.025, centerLon + 0.025, "mall"))
        places.add(DbOfflinePlace(6, regionId, "HP Fuel Station", "Station Road, Pune", centerLat + 0.005, centerLon + 0.005, "fuel"))

        db.insertRoadGraph(regionId, nodes, segments, places)
        return (segments.size * 500L + places.size * 300L + 25L * 1024L * 1024L) // Estimated size bytes
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, res)
        return res[0].toDouble()
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val res = FloatArray(2)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, res)
        return res[1]
    }
}
