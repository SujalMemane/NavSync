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

        // If database is brand new or lacks rich landmarks, pre-populate default test region (Pune Region)
        val existingPlaces = db.getAllOfflinePlaces()
        if (regions.isEmpty() || existingPlaces.size < 15) {
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
            generateSampleRoadGraph("pune_region_01", "Pune Offline Region", 18.5204, 73.8567)
            regions = db.getDownloadedRegions()
        }

        _downloadedRegions.value = regions

        val dbNodes = db.getAllNodes()
        val dbSegments = db.getAllSegments()

        // Load segments into road graph
        roadGraph.loadFromDbData(dbNodes, dbSegments)
        Log.d(TAG, "Offline graph initialized with ${dbNodes.size} nodes, ${dbSegments.size} segments.")
    }

    fun getBestOfflineRegionForLocation(lat: Double, lon: Double): OfflineRegionRecord? {
        return _downloadedRegions.value.find { region ->
            region.status == "DOWNLOADED" &&
                    lat >= region.minLat && lat <= region.maxLat &&
                    lon >= region.minLon && lon <= region.maxLon
        }
    }

    fun resetDownloadState() {
        _isDownloading.value = false
        _downloadProgress.value = 0
        _downloadStatusText.value = ""
    }

    suspend fun downloadMapArea(
        name: String,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double
    ) = withContext(Dispatchers.IO) {
        _isDownloading.value = true
        try {
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
            val estSizeBytes = generateSampleRoadGraph(regionId, name, centerLat, centerLon)
            kotlinx.coroutines.delay(400L)

            val completedRecord = regionRecord.copy(
                sizeBytes = estSizeBytes,
                status = "DOWNLOADED"
            )
            db.saveRegion(completedRecord)

            loadRegionsAndGraph()

            _downloadProgress.value = 100
            _downloadStatusText.value = "Download Complete ✓"
            Log.d(TAG, "OFFLINE_MAP_DOWNLOADED regionId=$regionId name=$name sizeBytes=$estSizeBytes")
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading map area $name: ${e.message}", e)
            _downloadStatusText.value = "Download failed: ${e.message}"
        } finally {
            _isDownloading.value = false
        }
    }

    suspend fun deleteRegion(regionId: String) = withContext(Dispatchers.IO) {
        db.deleteRegion(regionId)
        loadRegionsAndGraph()
        Log.d(TAG, "Deleted region regionId=$regionId")
    }

    private suspend fun generateSampleRoadGraph(regionId: String, regionName: String, centerLat: Double, centerLon: Double): Long {
        val nodes = mutableListOf<DbRoadNode>()
        val segments = mutableListOf<DbRoadSegment>()
        val places = mutableListOf<DbOfflinePlace>()

        // Generate synthetic grid of road segments & nodes around center lat/lon with unique base ID per download
        val nodeBaseId = (System.currentTimeMillis() % 100000L) * 100L
        val nodeGrid = Array(5) { r -> Array(5) { c -> (nodeBaseId + r * 5 + c + 1L) } }

        for (r in 0 until 5) {
            for (c in 0 until 5) {
                val nodeId = nodeGrid[r][c]
                val lat = centerLat + (r - 2) * 0.015
                val lon = centerLon + (c - 2) * 0.015
                nodes.add(DbRoadNode(nodeId, regionId, lat, lon))
            }
        }

        var segCounter = nodeBaseId + 1000L
        val cleanName = regionName.replace("Offline Region", "").replace("Selected Area", "").trim().ifEmpty { "Area" }
        val roadNames = arrayOf(
            "$cleanName Main Highway",
            "$cleanName Station Road",
            "$cleanName Ring Road",
            "$cleanName Central Avenue",
            "$cleanName Commercial Street",
            "$cleanName Express Link",
            "NH 48"
        )
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

        // Generate tailored landmark places across categories specifically for this downloaded region
        var pId = System.currentTimeMillis() % 100000L

        // Primary Center & Transit Hubs
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Center", "Central District, $cleanName", centerLat, centerLon, "landmark"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Central Station", "Station Road, $cleanName", centerLat + 0.008, centerLon + 0.012, "transit"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Metro Station", "Metro Line, $cleanName", centerLat + 0.005, centerLon - 0.008, "transit"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Bus Terminal", "Terminal Road, $cleanName", centerLat - 0.015, centerLon + 0.002, "transit"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Airport", "Airport Road, $cleanName", centerLat + 0.035, centerLon + 0.030, "transit"))

        // Hospitals & Medical
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName General Hospital", "Health Avenue, $cleanName", centerLat + 0.007, centerLon + 0.014, "hospital"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Emergency Clinic", "Cross Road, $cleanName", centerLat - 0.006, centerLon - 0.009, "hospital"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Multi-Specialty Hospital", "Ring Road, $cleanName", centerLat + 0.015, centerLon + 0.018, "hospital"))

        // Fuel & EV Stations
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName HP AutoCare Fuel Station", "Station Road, $cleanName", centerLat + 0.004, centerLon + 0.005, "fuel"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Shell Petrol Pump", "Main Highway, $cleanName", centerLat + 0.012, centerLon - 0.018, "fuel"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Indian Oil EV Charging Hub", "Central Avenue, $cleanName", centerLat + 0.003, centerLon - 0.004, "fuel"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Bharat Petroleum Pump", "Ring Road, $cleanName", centerLat - 0.004, centerLon - 0.012, "fuel"))

        // Landmarks & Heritage
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Heritage Monument", "Old City, $cleanName", centerLat + 0.001, centerLon - 0.002, "landmark"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName City Park & Lake", "Lake Road, $cleanName", centerLat - 0.002, centerLon + 0.001, "landmark"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Cultural Center", "Arts Square, $cleanName", centerLat - 0.009, centerLon + 0.003, "landmark"))

        // Food & Dining
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Grand Restaurant", "Food Street, $cleanName", centerLat - 0.007, centerLon - 0.015, "food"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Cafe & Bakery", "Main Market, $cleanName", centerLat - 0.005, centerLon - 0.014, "food"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Food Plaza", "Station Road, $cleanName", centerLat + 0.018, centerLon + 0.032, "food"))

        // Shopping & Markets
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Shopping Mall", "Commercial Street, $cleanName", centerLat + 0.032, centerLon + 0.040, "shopping"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName Central Market", "Market Yard, $cleanName", centerLat + 0.014, centerLon - 0.016, "shopping"))

        // Key road intersections
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName North Junction", "North Highway Cross", centerLat + 0.025, centerLon, "landmark"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName South Junction", "South Highway Cross", centerLat - 0.025, centerLon, "landmark"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName East Junction", "East Bypass Cross", centerLat, centerLon + 0.025, "landmark"))
        places.add(DbOfflinePlace(pId++, regionId, "$cleanName West Junction", "West Bypass Cross", centerLat, centerLon - 0.025, "landmark"))

        // Also include classic Pune landmarks if in Pune region
        if (cleanName.contains("pune", ignoreCase = true)) {
            places.add(DbOfflinePlace(pId++, regionId, "Shaniwar Wada", "Bajirao Road, Shaniwar Peth, Pune", centerLat + 0.001, centerLon - 0.002, "landmark"))
            places.add(DbOfflinePlace(pId++, regionId, "Aga Khan Palace", "Nagar Road, Kalyani Nagar, Pune", centerLat + 0.028, centerLon + 0.038, "landmark"))
            places.add(DbOfflinePlace(pId++, regionId, "Dagdusheth Halwai Ganpati Temple", "Budhwar Peth, Pune", centerLat - 0.002, centerLon + 0.001, "landmark"))
            places.add(DbOfflinePlace(pId++, regionId, "Fergusson College", "FC Road, Shivajinagar, Pune", centerLat - 0.008, centerLon - 0.016, "landmark"))
            places.add(DbOfflinePlace(pId++, regionId, "Vaishali Restaurant", "FC Road, Deccan Gymkhana, Pune", centerLat - 0.007, centerLon - 0.015, "food"))
            places.add(DbOfflinePlace(pId++, regionId, "Goodluck Cafe", "FC Road, Deccan, Pune", centerLat - 0.005, centerLon - 0.014, "food"))
            places.add(DbOfflinePlace(pId++, regionId, "German Bakery", "Koregaon Park, Pune", centerLat + 0.018, centerLon + 0.032, "food"))
            places.add(DbOfflinePlace(pId++, regionId, "Phoenix Marketcity Mall", "Viman Nagar, Pune", centerLat + 0.032, centerLon + 0.040, "shopping"))
        }

        db.insertRoadGraph(regionId, nodes, segments, places)
        return (segments.size * 500L + places.size * 300L + 25L * 1024L * 1024L) // Estimated size bytes
    }

    suspend fun getAllLandmarks(): List<DbOfflinePlace> = db.getAllOfflinePlaces()
    suspend fun getLandmarksByCategory(category: String): List<DbOfflinePlace> = db.getPlacesByCategory(category)

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
