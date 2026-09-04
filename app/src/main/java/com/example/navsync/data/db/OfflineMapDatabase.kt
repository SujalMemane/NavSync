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
        private const val DATABASE_VERSION = 1

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

        db.execSQL("CREATE INDEX idx_segments_bounds ON $TABLE_SEGMENTS (start_lat, start_lon, end_lat, end_lon);")
        db.execSQL("CREATE INDEX idx_places_name ON $TABLE_PLACES (name);")
        Log.d(TAG, "Offline map database tables created.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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

    suspend fun searchOfflinePlaces(query: String): List<DbOfflinePlace> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DbOfflinePlace>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_PLACES,
                null,
                "name LIKE ?",
                arrayOf("%$query%"),
                null,
                null,
                "name ASC",
                "20"
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
