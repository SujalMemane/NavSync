package com.example.navsync.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.navsync.repository.LocationPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class OfflineRegionRecord(
    val id: Long = 0,
    val regionId: String,
    val name: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val downloadTimeMs: Long,
    val sizeBytes: Long,
    val status: String // "DOWNLOADING", "DOWNLOADED", "FAILED"
)

data class DbRoadNode(
    val nodeId: Long,
    val regionId: String,
    val latitude: Double,
    val longitude: Double
)

data class DbRoadSegment(
    val segmentId: Long,
    val regionId: String,
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

data class DbOfflinePlace(
    val placeId: Long = 0,
    val regionId: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val placeType: String
)

class OfflineMapDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "NAVSYNC_OFFLINE_DB"
        private const val DATABASE_NAME = "navsync_offline.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_REGIONS = "offline_regions"
        private const val TABLE_NODES = "road_nodes"
        private const val TABLE_SEGMENTS = "road_segments"
        private const val TABLE_PLACES = "offline_places"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createRegionsSql = """
            CREATE TABLE $TABLE_REGIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                region_id TEXT UNIQUE NOT NULL,
                name TEXT NOT NULL,
                min_lat REAL NOT NULL,
                min_lon REAL NOT NULL,
                max_lat REAL NOT NULL,
                max_lon REAL NOT NULL,
                download_time_ms INTEGER NOT NULL,
                size_bytes INTEGER DEFAULT 0,
                status TEXT DEFAULT 'DOWNLOADING'
            );
        """.trimIndent()

        val createNodesSql = """
            CREATE TABLE $TABLE_NODES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                region_id TEXT NOT NULL,
                node_id INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL
            );
        """.trimIndent()

        val createSegmentsSql = """
            CREATE TABLE $TABLE_SEGMENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                region_id TEXT NOT NULL,
                segment_id INTEGER NOT NULL,
                from_node_id INTEGER NOT NULL,
                to_node_id INTEGER NOT NULL,
                start_lat REAL NOT NULL,
                start_lon REAL NOT NULL,
                end_lat REAL NOT NULL,
                end_lon REAL NOT NULL,
                geometry_json TEXT NOT NULL,
                length_meters REAL NOT NULL,
                bearing_degrees REAL NOT NULL,
                road_name TEXT,
                road_type TEXT,
                one_way INTEGER DEFAULT 0,
                max_speed_kmh INTEGER DEFAULT 50,
                curvature REAL DEFAULT 0.0,
                connected_segment_ids TEXT
            );
        """.trimIndent()

        val createPlacesSql = """
            CREATE TABLE $TABLE_PLACES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                region_id TEXT NOT NULL,
                name TEXT NOT NULL,
                address TEXT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                place_type TEXT
            );
        """.trimIndent()

        db.execSQL(createRegionsSql)
        db.execSQL(createNodesSql)
        db.execSQL(createSegmentsSql)
        db.execSQL(createPlacesSql)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_bounds ON $TABLE_SEGMENTS (start_lat, start_lon, end_lat, end_lon);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_road ON $TABLE_SEGMENTS (road_name);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_places_name ON $TABLE_PLACES (name);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_places_type ON $TABLE_PLACES (place_type);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_places_region ON $TABLE_PLACES (region_id);")
        Log.d(TAG, "Offline map database tables and indexes created.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading offline map database from v$oldVersion to v$newVersion - refreshing schema.")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REGIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NODES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SEGMENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PLACES")
        onCreate(db)
    }

    suspend fun saveRegion(region: OfflineRegionRecord) = withContext(Dispatchers.IO) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("region_id", region.regionId)
                put("name", region.name)
                put("min_lat", region.minLat)
                put("min_lon", region.minLon)
                put("max_lat", region.maxLat)
                put("max_lon", region.maxLon)
                put("download_time_ms", region.downloadTimeMs)
                put("size_bytes", region.sizeBytes)
                put("status", region.status)
            }
            db.insertWithOnConflict(TABLE_REGIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(TAG, "Offline region saved regionId=${region.regionId} status=${region.status}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving offline region: ${e.message}", e)
        }
    }

    suspend fun getDownloadedRegions(): List<OfflineRegionRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<OfflineRegionRecord>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_REGIONS, null, null, null, null, null, "download_time_ms DESC")
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val regIdIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                val minLatIdx = c.getColumnIndexOrThrow("min_lat")
                val minLonIdx = c.getColumnIndexOrThrow("min_lon")
                val maxLatIdx = c.getColumnIndexOrThrow("max_lat")
                val maxLonIdx = c.getColumnIndexOrThrow("max_lon")
                val timeIdx = c.getColumnIndexOrThrow("download_time_ms")
                val sizeIdx = c.getColumnIndexOrThrow("size_bytes")
                val statIdx = c.getColumnIndexOrThrow("status")

                while (c.moveToNext()) {
                    list.add(
                        OfflineRegionRecord(
                            id = c.getLong(idIdx),
                            regionId = c.getString(regIdIdx),
                            name = c.getString(nameIdx),
                            minLat = c.getDouble(minLatIdx),
                            minLon = c.getDouble(minLonIdx),
                            maxLat = c.getDouble(maxLatIdx),
                            maxLon = c.getDouble(maxLonIdx),
                            downloadTimeMs = c.getLong(timeIdx),
                            sizeBytes = c.getLong(sizeIdx),
                            status = c.getString(statIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching offline regions: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun deleteRegion(regionId: String) = withContext(Dispatchers.IO) {
        try {
            val db = writableDatabase
            db.delete(TABLE_REGIONS, "region_id = ?", arrayOf(regionId))
            db.delete(TABLE_NODES, "region_id = ?", arrayOf(regionId))
            db.delete(TABLE_SEGMENTS, "region_id = ?", arrayOf(regionId))
            db.delete(TABLE_PLACES, "region_id = ?", arrayOf(regionId))
            Log.d(TAG, "Deleted offline region regionId=$regionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting region: ${e.message}", e)
        }
    }

    suspend fun insertRoadGraph(
        regionId: String,
        nodes: List<DbRoadNode>,
        segments: List<DbRoadSegment>,
        places: List<DbOfflinePlace>
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            nodes.forEach { node ->
                val v = ContentValues().apply {
                    put("region_id", regionId)
                    put("node_id", node.nodeId)
                    put("latitude", node.latitude)
                    put("longitude", node.longitude)
                }
                db.insert(TABLE_NODES, null, v)
            }

            segments.forEach { seg ->
                val geomArr = JSONArray()
                seg.geometry.forEach { pt ->
                    geomArr.put(JSONObject().put("lat", pt.latitude).put("lon", pt.longitude))
                }

                val connArr = JSONArray()
                seg.connectedSegmentIds.forEach { connArr.put(it) }

                val v = ContentValues().apply {
                    put("region_id", regionId)
                    put("segment_id", seg.segmentId)
                    put("from_node_id", seg.fromNodeId)
                    put("to_node_id", seg.toNodeId)
                    put("start_lat", seg.startLat)
                    put("start_lon", seg.startLon)
                    put("end_lat", seg.endLat)
                    put("end_lon", seg.endLon)
                    put("geometry_json", geomArr.toString())
                    put("length_meters", seg.lengthMeters)
                    put("bearing_degrees", seg.bearingDegrees)
                    put("road_name", seg.roadName)
                    put("road_type", seg.roadType)
                    put("one_way", if (seg.oneWay) 1 else 0)
                    put("max_speed_kmh", seg.maxSpeedKmh)
                    put("curvature", seg.curvature)
                    put("connected_segment_ids", connArr.toString())
                }
                db.insert(TABLE_SEGMENTS, null, v)
            }

            places.forEach { place ->
                val v = ContentValues().apply {
                    put("region_id", regionId)
                    put("name", place.name)
                    put("address", place.address)
                    put("latitude", place.latitude)
                    put("longitude", place.longitude)
                    put("place_type", place.placeType)
                }
                db.insert(TABLE_PLACES, null, v)
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "Road graph inserted into DB: ${nodes.size} nodes, ${segments.size} segments, ${places.size} places")
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting road graph: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getAllNodes(): List<DbRoadNode> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbRoadNode>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_NODES, null, null, null, null, null, null)
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("node_id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val latIdx = c.getColumnIndexOrThrow("latitude")
                val lonIdx = c.getColumnIndexOrThrow("longitude")
                while (c.moveToNext()) {
                    list.add(
                        DbRoadNode(
                            nodeId = c.getLong(idIdx),
                            regionId = c.getString(regIdx),
                            latitude = c.getDouble(latIdx),
                            longitude = c.getDouble(lonIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching road nodes: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun getNodesForRegion(regionId: String): List<DbRoadNode> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbRoadNode>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_NODES, null, "region_id = ?", arrayOf(regionId), null, null, null)
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("node_id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val latIdx = c.getColumnIndexOrThrow("latitude")
                val lonIdx = c.getColumnIndexOrThrow("longitude")
                while (c.moveToNext()) {
                    list.add(
                        DbRoadNode(
                            nodeId = c.getLong(idIdx),
                            regionId = c.getString(regIdx),
                            latitude = c.getDouble(latIdx),
                            longitude = c.getDouble(lonIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching road nodes for region: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun getAllSegments(): List<DbRoadSegment> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbRoadSegment>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_SEGMENTS, null, null, null, null, null, null)
            cursor.use { c ->
                val segIdIdx = c.getColumnIndexOrThrow("segment_id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val fromIdx = c.getColumnIndexOrThrow("from_node_id")
                val toIdx = c.getColumnIndexOrThrow("to_node_id")
                val startLatIdx = c.getColumnIndexOrThrow("start_lat")
                val startLonIdx = c.getColumnIndexOrThrow("start_lon")
                val endLatIdx = c.getColumnIndexOrThrow("end_lat")
                val endLonIdx = c.getColumnIndexOrThrow("end_lon")
                val geomIdx = c.getColumnIndexOrThrow("geometry_json")
                val lenIdx = c.getColumnIndexOrThrow("length_meters")
                val bearIdx = c.getColumnIndexOrThrow("bearing_degrees")
                val nameIdx = c.getColumnIndexOrThrow("road_name")
                val typeIdx = c.getColumnIndexOrThrow("road_type")
                val oneWayIdx = c.getColumnIndexOrThrow("one_way")
                val speedIdx = c.getColumnIndexOrThrow("max_speed_kmh")
                val curvIdx = c.getColumnIndexOrThrow("curvature")
                val connIdx = c.getColumnIndexOrThrow("connected_segment_ids")

                while (c.moveToNext()) {
                    val geomJsonStr = c.getString(geomIdx) ?: "[]"
                    val geomList = mutableListOf<LocationPoint>()
                    val jsonArr = JSONArray(geomJsonStr)
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        geomList.add(LocationPoint(obj.getDouble("lat"), obj.getDouble("lon")))
                    }

                    val connJsonStr = c.getString(connIdx) ?: "[]"
                    val connList = mutableListOf<Long>()
                    val connArr = JSONArray(connJsonStr)
                    for (i in 0 until connArr.length()) {
                        connList.add(connArr.getLong(i))
                    }

                    list.add(
                        DbRoadSegment(
                            segmentId = c.getLong(segIdIdx),
                            regionId = c.getString(regIdx),
                            fromNodeId = c.getLong(fromIdx),
                            toNodeId = c.getLong(toIdx),
                            startLat = c.getDouble(startLatIdx),
                            startLon = c.getDouble(startLonIdx),
                            endLat = c.getDouble(endLatIdx),
                            endLon = c.getDouble(endLonIdx),
                            geometry = geomList,
                            lengthMeters = c.getDouble(lenIdx),
                            bearingDegrees = c.getFloat(bearIdx),
                            roadName = c.getString(nameIdx) ?: "Local Road",
                            roadType = c.getString(typeIdx) ?: "secondary",
                            oneWay = c.getInt(oneWayIdx) == 1,
                            maxSpeedKmh = c.getInt(speedIdx),
                            curvature = c.getFloat(curvIdx),
                            connectedSegmentIds = connList
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching road segments: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun getSegmentsForRegion(regionId: String): List<DbRoadSegment> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbRoadSegment>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_SEGMENTS, null, "region_id = ?", arrayOf(regionId), null, null, null)
            cursor.use { c ->
                val segIdIdx = c.getColumnIndexOrThrow("segment_id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val fromIdx = c.getColumnIndexOrThrow("from_node_id")
                val toIdx = c.getColumnIndexOrThrow("to_node_id")
                val startLatIdx = c.getColumnIndexOrThrow("start_lat")
                val startLonIdx = c.getColumnIndexOrThrow("start_lon")
                val endLatIdx = c.getColumnIndexOrThrow("end_lat")
                val endLonIdx = c.getColumnIndexOrThrow("end_lon")
                val geomIdx = c.getColumnIndexOrThrow("geometry_json")
                val lenIdx = c.getColumnIndexOrThrow("length_meters")
                val bearIdx = c.getColumnIndexOrThrow("bearing_degrees")
                val nameIdx = c.getColumnIndexOrThrow("road_name")
                val typeIdx = c.getColumnIndexOrThrow("road_type")
                val oneWayIdx = c.getColumnIndexOrThrow("one_way")
                val speedIdx = c.getColumnIndexOrThrow("max_speed_kmh")
                val curvIdx = c.getColumnIndexOrThrow("curvature")
                val connIdx = c.getColumnIndexOrThrow("connected_segment_ids")

                while (c.moveToNext()) {
                    val geomJsonStr = c.getString(geomIdx) ?: "[]"
                    val geomList = mutableListOf<LocationPoint>()
                    val jsonArr = JSONArray(geomJsonStr)
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        geomList.add(LocationPoint(obj.getDouble("lat"), obj.getDouble("lon")))
                    }

                    val connJsonStr = c.getString(connIdx) ?: "[]"
                    val connList = mutableListOf<Long>()
                    val connArr = JSONArray(connJsonStr)
                    for (i in 0 until connArr.length()) {
                        connList.add(connArr.getLong(i))
                    }

                    list.add(
                        DbRoadSegment(
                            segmentId = c.getLong(segIdIdx),
                            regionId = c.getString(regIdx),
                            fromNodeId = c.getLong(fromIdx),
                            toNodeId = c.getLong(toIdx),
                            startLat = c.getDouble(startLatIdx),
                            startLon = c.getDouble(startLonIdx),
                            endLat = c.getDouble(endLatIdx),
                            endLon = c.getDouble(endLonIdx),
                            geometry = geomList,
                            lengthMeters = c.getDouble(lenIdx),
                            bearingDegrees = c.getFloat(bearIdx),
                            roadName = c.getString(nameIdx) ?: "Local Road",
                            roadType = c.getString(typeIdx) ?: "secondary",
                            oneWay = c.getInt(oneWayIdx) == 1,
                            maxSpeedKmh = c.getInt(speedIdx),
                            curvature = c.getFloat(curvIdx),
                            connectedSegmentIds = connList
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching road segments for region $regionId: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun getPlacesForRegion(regionId: String): List<DbOfflinePlace> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbOfflinePlace>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_PLACES, null, "region_id = ?", arrayOf(regionId), null, null, "name ASC")
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val latIdx = c.getColumnIndexOrThrow("latitude")
                val lonIdx = c.getColumnIndexOrThrow("longitude")
                val typeIdx = c.getColumnIndexOrThrow("place_type")

                while (c.moveToNext()) {
                    list.add(
                        DbOfflinePlace(
                            placeId = c.getLong(idIdx),
                            regionId = c.getString(regIdx),
                            name = c.getString(nameIdx),
                            address = c.getString(addrIdx) ?: "",
                            latitude = c.getDouble(latIdx),
                            longitude = c.getDouble(lonIdx),
                            placeType = c.getString(typeIdx) ?: "poi"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching places for region $regionId: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun searchOfflinePlaces(query: String): List<DbOfflinePlace> = searchOfflinePlaces(query, null, null)

    suspend fun searchOfflinePlaces(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<DbOfflinePlace> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbOfflinePlace>()
        val qClean = query.trim()
        if (qClean.isEmpty()) return@withContext list

        val qLower = qClean.lowercase(Locale.US)
        val tokens = qLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 1 }

        val genericStopWords = setOf(
            "road", "rd", "street", "st", "lane", "ln", "avenue", "ave", "highway", "hwy",
            "marg", "chowk", "chawk", "circle", "bypass", "expressway", "cross",
            "near", "opp", "opposite", "behind", "beside", "at", "in", "the", "of", "and"
        )
        val distinctiveTokens = tokens.filter { it !in genericStopWords }

        // Category keywords to place_type mapping
        val wantsFuel = listOf("fuel", "petrol", "diesel", "pump", "cng", "shell", "bharat", "hp").any { qLower.contains(it) }
        val wantsHospital = listOf("hospital", "clinic", "doctor", "health", "medical", "phc").any { qLower.contains(it) }
        val wantsTransit = listOf("station", "railway", "train", "metro", "bus", "terminal").any { qLower.contains(it) }
        val wantsFood = listOf("food", "restaurant", "cafe", "hotel", "bakery", "dhaba", "lunch", "dinner").any { qLower.contains(it) }
        val wantsShopping = listOf("mall", "market", "bazaar", "shop", "store").any { qLower.contains(it) }

        data class Candidate(val place: DbOfflinePlace, var score: Double, val distMeters: Float?)
        val candidateMap = mutableMapOf<String, Candidate>()

        try {
            val db = readableDatabase

            // 1. Search TABLE_PLACES (POIs, Landmarks, Facilities)
            val placeWhereParts = mutableListOf<String>()
            val placeArgs = mutableListOf<String>()

            placeWhereParts.add("(name LIKE ? OR address LIKE ? OR place_type LIKE ?)")
            placeArgs.add("%$qClean%")
            placeArgs.add("%$qClean%")
            placeArgs.add("%$qClean%")

            for (token in tokens) {
                placeWhereParts.add("(name LIKE ? OR address LIKE ? OR place_type LIKE ?)")
                placeArgs.add("%$token%")
                placeArgs.add("%$token%")
                placeArgs.add("%$token%")
            }

            val placeWhere = placeWhereParts.joinToString(" OR ")
            val placeCursor = db.query(
                TABLE_PLACES,
                null,
                placeWhere,
                placeArgs.toTypedArray(),
                null,
                null,
                "id ASC",
                "60"
            )

            placeCursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val latIdx = c.getColumnIndexOrThrow("latitude")
                val lonIdx = c.getColumnIndexOrThrow("longitude")
                val typeIdx = c.getColumnIndexOrThrow("place_type")

                while (c.moveToNext()) {
                    val pId = c.getLong(idIdx)
                    val rId = c.getString(regIdx)
                    val name = c.getString(nameIdx)
                    val addr = c.getString(addrIdx) ?: ""
                    val lat = c.getDouble(latIdx)
                    val lon = c.getDouble(lonIdx)
                    val pType = c.getString(typeIdx) ?: "poi"

                    val nLower = name.lowercase(Locale.US)
                    val aLower = addr.lowercase(Locale.US)

                    // Must match at least one distinctive token if query contains distinctive words
                    if (distinctiveTokens.isNotEmpty()) {
                        val hasDistinctiveMatch = distinctiveTokens.any { t -> nLower.contains(t) || aLower.contains(t) }
                        val isCategoryMatch = (wantsFuel && pType == "fuel") ||
                                (wantsHospital && pType == "hospital") ||
                                (wantsTransit && pType == "transit") ||
                                (wantsFood && pType == "food") ||
                                (wantsShopping && pType == "shopping")
                        if (!hasDistinctiveMatch && !isCategoryMatch && !nLower.contains(qLower)) {
                            continue // Skip false positive where only generic stop-words (like 'road') matched the address
                        }
                    }

                    var score = 0.0

                    if (nLower == qLower) {
                        score += 1500.0
                    } else if (nLower.startsWith(qLower)) {
                        score += 850.0
                    } else if (nLower.contains(qLower)) {
                        score += 550.0
                    } else if (aLower.contains(qLower)) {
                        score += 250.0
                    }

                    var matchedTokens = 0
                    for (t in tokens) {
                        if (nLower.contains(t)) {
                            score += 180.0
                            matchedTokens++
                        } else if (aLower.contains(t)) {
                            score += 80.0
                            matchedTokens++
                        }
                    }
                    if (tokens.isNotEmpty() && matchedTokens >= tokens.size) {
                        score += 350.0 // All keywords found in entry
                    }

                    if (wantsFuel && (pType == "fuel" || nLower.contains("petrol") || nLower.contains("pump"))) score += 300.0
                    if (wantsHospital && (pType == "hospital" || nLower.contains("hospital") || nLower.contains("clinic"))) score += 300.0
                    if (wantsTransit && (pType == "transit" || nLower.contains("station") || nLower.contains("metro"))) score += 300.0
                    if (wantsFood && (pType == "food" || nLower.contains("restaurant") || nLower.contains("cafe"))) score += 250.0
                    if (wantsShopping && (pType == "shopping" || nLower.contains("mall") || nLower.contains("market"))) score += 250.0

                    if (score > 0) {
                        var dist: Float? = null
                        if (userLat != null && userLon != null) {
                            val res = FloatArray(1)
                            android.location.Location.distanceBetween(userLat, userLon, lat, lon, res)
                            dist = res[0]
                            val distKm = dist / 1000.0
                            score += maxOf(0.0, 150.0 - distKm * 2.0)
                        }

                        val key = "place_${name.lowercase(Locale.US)}_${String.format(Locale.US, "%.3f_%.3f", lat, lon)}"
                        val placeObj = DbOfflinePlace(pId, rId, name, addr, lat, lon, pType)
                        candidateMap[key] = Candidate(placeObj, score, dist)
                    }
                }
            }

            // 2. Search TABLE_SEGMENTS (Roads, Highways, Streets)
            val segWhereParts = mutableListOf<String>()
            val segArgs = mutableListOf<String>()

            segWhereParts.add("road_name LIKE ?")
            segArgs.add("%$qClean%")

            for (token in tokens) {
                segWhereParts.add("road_name LIKE ?")
                segArgs.add("%$token%")
            }

            val segWhere = "(" + segWhereParts.joinToString(" OR ") + ") AND road_name IS NOT NULL AND road_name != ''"
            val segCursor = db.query(
                TABLE_SEGMENTS,
                arrayOf("segment_id", "region_id", "road_name", "road_type", "start_lat", "start_lon", "end_lat", "end_lon"),
                segWhere,
                segArgs.toTypedArray(),
                null,
                null,
                "segment_id ASC",
                "80"
            )

            val seenRoadNames = mutableMapOf<String, Candidate>()

            segCursor.use { c ->
                val segIdIdx = c.getColumnIndexOrThrow("segment_id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("road_name")
                val typeIdx = c.getColumnIndexOrThrow("road_type")
                val sLatIdx = c.getColumnIndexOrThrow("start_lat")
                val sLonIdx = c.getColumnIndexOrThrow("start_lon")
                val eLatIdx = c.getColumnIndexOrThrow("end_lat")
                val eLonIdx = c.getColumnIndexOrThrow("end_lon")

                while (c.moveToNext()) {
                    val segId = c.getLong(segIdIdx)
                    val rId = c.getString(regIdx)
                    val roadName = c.getString(nameIdx)
                    val rType = c.getString(typeIdx) ?: "highway"
                    val sLat = c.getDouble(sLatIdx)
                    val sLon = c.getDouble(sLonIdx)
                    val eLat = c.getDouble(eLatIdx)
                    val eLon = c.getDouble(eLonIdx)
                    val midLat = (sLat + eLat) / 2.0
                    val midLon = (sLon + eLon) / 2.0

                    val rLower = roadName.lowercase(Locale.US)

                    // Must match at least one distinctive token if query contains distinctive words
                    if (distinctiveTokens.isNotEmpty()) {
                        val hasDistinctiveMatch = distinctiveTokens.any { t -> rLower.contains(t) }
                        if (!hasDistinctiveMatch && !rLower.contains(qLower)) {
                            continue
                        }
                    }

                    var score = 0.0

                    if (rLower == qLower) {
                        score += 1400.0
                    } else if (rLower.startsWith(qLower)) {
                        score += 800.0
                    } else if (rLower.contains(qLower)) {
                        score += 500.0
                    }

                    var matchedTokens = 0
                    for (t in tokens) {
                        if (rLower.contains(t)) {
                            score += 180.0
                            matchedTokens++
                        }
                    }
                    if (tokens.isNotEmpty() && matchedTokens >= tokens.size) {
                        score += 300.0
                    }

                    if (rType == "motorway" || rType == "trunk" || rType == "primary") {
                        score += 50.0
                    }

                    if (score > 0) {
                        var dist: Float? = null
                        if (userLat != null && userLon != null) {
                            val res = FloatArray(1)
                            android.location.Location.distanceBetween(userLat, userLon, midLat, midLon, res)
                            dist = res[0]
                            val distKm = dist / 1000.0
                            score += maxOf(0.0, 120.0 - distKm * 1.5)
                        }

                        val roadKey = rLower.trim()
                        val existing = seenRoadNames[roadKey]
                        if (existing == null || score > existing.score || (dist != null && existing.distMeters != null && dist < existing.distMeters)) {
                            val roadPlace = DbOfflinePlace(
                                placeId = 80000L + segId,
                                regionId = rId,
                                name = roadName,
                                address = "Road / Highway in Offline Map",
                                latitude = midLat,
                                longitude = midLon,
                                placeType = "highway"
                            )
                            val cand = Candidate(roadPlace, score, dist)
                            seenRoadNames[roadKey] = cand
                        }
                    }
                }
            }

            // Merge deduplicated roads into candidates
            seenRoadNames.forEach { (key, cand) ->
                candidateMap["road_$key"] = cand
            }

            // 3. Search TABLE_REGIONS (Downloaded Area Centers)
            val regWhereParts = mutableListOf<String>()
            val regArgs = mutableListOf<String>()
            regWhereParts.add("name LIKE ?")
            regArgs.add("%$qClean%")
            for (token in tokens) {
                regWhereParts.add("name LIKE ?")
                regArgs.add("%$token%")
            }

            val regWhere = regWhereParts.joinToString(" OR ")
            val regCursor = db.query(
                TABLE_REGIONS,
                null,
                regWhere,
                regArgs.toTypedArray(),
                null,
                null,
                "name ASC",
                "10"
            )

            regCursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                val minLatIdx = c.getColumnIndexOrThrow("min_lat")
                val minLonIdx = c.getColumnIndexOrThrow("min_lon")
                val maxLatIdx = c.getColumnIndexOrThrow("max_lat")
                val maxLonIdx = c.getColumnIndexOrThrow("max_lon")

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val rId = c.getString(regIdx)
                    val name = c.getString(nameIdx)
                    val minLat = c.getDouble(minLatIdx)
                    val minLon = c.getDouble(minLonIdx)
                    val maxLat = c.getDouble(maxLatIdx)
                    val maxLon = c.getDouble(maxLonIdx)
                    val cLat = (minLat + maxLat) / 2.0
                    val cLon = (minLon + maxLon) / 2.0

                    val nLower = name.lowercase(Locale.US)

                    // Must match at least one distinctive token if query contains distinctive words
                    if (distinctiveTokens.isNotEmpty()) {
                        val hasDistinctiveMatch = distinctiveTokens.any { t -> nLower.contains(t) }
                        if (!hasDistinctiveMatch && !nLower.contains(qLower)) {
                            continue
                        }
                    }
                    var score = 0.0

                    if (nLower == qLower) {
                        score += 1200.0
                    } else if (nLower.startsWith(qLower)) {
                        score += 700.0
                    } else if (nLower.contains(qLower)) {
                        score += 450.0
                    }

                    for (t in tokens) {
                        if (nLower.contains(t)) score += 150.0
                    }

                    if (score > 0) {
                        var dist: Float? = null
                        if (userLat != null && userLon != null) {
                            val res = FloatArray(1)
                            android.location.Location.distanceBetween(userLat, userLon, cLat, cLon, res)
                            dist = res[0]
                            val distKm = dist / 1000.0
                            score += maxOf(0.0, 100.0 - distKm * 1.5)
                        }

                        val cleanAreaTitle = name.replace("Offline Region", "").replace("Selected Area", "").trim()
                        val centerPlace = DbOfflinePlace(
                            placeId = 90000L + id,
                            regionId = rId,
                            name = "$cleanAreaTitle (Center)",
                            address = "Downloaded Offline Map Area",
                            latitude = cLat,
                            longitude = cLon,
                            placeType = "landmark"
                        )
                        candidateMap["region_$rId"] = Candidate(centerPlace, score, dist)
                    }
                }
            }

            // 4. Sort strictly by relevance score descending
            val sorted = candidateMap.values
                .filter { it.score > 0 }
                .sortedByDescending { it.score }
                .map { it.place }

            list.addAll(sorted.take(25))
            Log.d(TAG, "searchOfflinePlaces query=\"$qClean\" candidates=${candidateMap.size} returned=${list.size} (ZERO arbitrary fallbacks)")

        } catch (e: Exception) {
            Log.e(TAG, "Error searching offline places: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun getAllOfflinePlaces(): List<DbOfflinePlace> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbOfflinePlace>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_PLACES, null, null, null, null, null, "name ASC", "100")
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val latIdx = c.getColumnIndexOrThrow("latitude")
                val lonIdx = c.getColumnIndexOrThrow("longitude")
                val typeIdx = c.getColumnIndexOrThrow("place_type")

                while (c.moveToNext()) {
                    list.add(
                        DbOfflinePlace(
                            placeId = c.getLong(idIdx),
                            regionId = c.getString(regIdx),
                            name = c.getString(nameIdx),
                            address = c.getString(addrIdx) ?: "",
                            latitude = c.getDouble(latIdx),
                            longitude = c.getDouble(lonIdx),
                            placeType = c.getString(typeIdx) ?: "poi"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching all offline places: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun getPlacesByCategory(placeType: String): List<DbOfflinePlace> = withContext(Dispatchers.IO) {
        if (placeType.equals("ALL", ignoreCase = true)) return@withContext getAllOfflinePlaces()
        val list = mutableListOf<DbOfflinePlace>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_PLACES,
                null,
                "place_type = ?",
                arrayOf(placeType.lowercase()),
                null,
                null,
                "name ASC",
                "50"
            )
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val regIdx = c.getColumnIndexOrThrow("region_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val latIdx = c.getColumnIndexOrThrow("latitude")
                val lonIdx = c.getColumnIndexOrThrow("longitude")
                val typeIdx = c.getColumnIndexOrThrow("place_type")

                while (c.moveToNext()) {
                    list.add(
                        DbOfflinePlace(
                            placeId = c.getLong(idIdx),
                            regionId = c.getString(regIdx),
                            name = c.getString(nameIdx),
                            address = c.getString(addrIdx) ?: "",
                            latitude = c.getDouble(latIdx),
                            longitude = c.getDouble(lonIdx),
                            placeType = c.getString(typeIdx) ?: "poi"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching places by category: ${e.message}", e)
        }
        return@withContext list
    }
}
