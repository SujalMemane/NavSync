package com.example.navsync.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TripSessionRecord(
    val id: Long = 0,
    val sessionId: String,
    val startTimeMs: Long,
    val endTimeMs: Long = 0L,
    val originName: String,
    val destinationName: String,
    val distanceKm: Double,
    val durationSeconds: Long,
    val status: String = "COMPLETED"
)

class NavSyncDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "NAVSYNC_DB"
        private const val DATABASE_NAME = "navsync_navigation.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_TRIPS = "trip_sessions"
        private const val TABLE_LOCATIONS = "trip_locations"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTripsSql = """
            CREATE TABLE $TABLE_TRIPS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT UNIQUE NOT NULL,
                start_time_ms INTEGER NOT NULL,
                end_time_ms INTEGER DEFAULT 0,
                origin_name TEXT,
                destination_name TEXT,
                distance_km REAL DEFAULT 0.0,
                duration_seconds INTEGER DEFAULT 0,
                status TEXT DEFAULT 'COMPLETED'
            );
        """.trimIndent()

        val createLocationsSql = """
            CREATE TABLE $TABLE_LOCATIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude REAL DEFAULT 0.0,
                speed REAL DEFAULT 0.0,
                bearing REAL DEFAULT 0.0,
                accuracy REAL DEFAULT 0.0
            );
        """.trimIndent()

        db.execSQL(createTripsSql)
        db.execSQL(createLocationsSql)
        Log.d(TAG, "Database tables created successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRIPS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOCATIONS")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            val createTripsSql = """
                CREATE TABLE IF NOT EXISTS $TABLE_TRIPS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT UNIQUE NOT NULL,
                    start_time_ms INTEGER NOT NULL,
                    end_time_ms INTEGER DEFAULT 0,
                    origin_name TEXT,
                    destination_name TEXT,
                    distance_km REAL DEFAULT 0.0,
                    duration_seconds INTEGER DEFAULT 0,
                    status TEXT DEFAULT 'COMPLETED'
                );
            """.trimIndent()
            db.execSQL(createTripsSql)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onOpen creating table: ${e.message}", e)
        }
    }

    suspend fun saveTripSession(record: TripSessionRecord) = withContext(Dispatchers.IO) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("session_id", record.sessionId)
                put("start_time_ms", record.startTimeMs)
                put("end_time_ms", record.endTimeMs)
                put("origin_name", record.originName)
                put("destination_name", record.destinationName)
                put("distance_km", record.distanceKm)
                put("duration_seconds", record.durationSeconds)
                put("status", record.status)
            }
            val id =
                db.insertWithOnConflict(TABLE_TRIPS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(
                TAG,
                "Trip session saved id=$id sessionId=${record.sessionId} dist=${record.distanceKm}km"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving trip session: ${e.message}", e)
        }
    }

    suspend fun getAllTripSessions(): List<TripSessionRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TripSessionRecord>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_TRIPS, null, null, null, null, null, "start_time_ms DESC")
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val sessIdx = c.getColumnIndexOrThrow("session_id")
                val startIdx = c.getColumnIndexOrThrow("start_time_ms")
                val endIdx = c.getColumnIndexOrThrow("end_time_ms")
                val origIdx = c.getColumnIndexOrThrow("origin_name")
                val destIdx = c.getColumnIndexOrThrow("destination_name")
                val distIdx = c.getColumnIndexOrThrow("distance_km")
                val durIdx = c.getColumnIndexOrThrow("duration_seconds")
                val statIdx = c.getColumnIndexOrThrow("status")

                while (c.moveToNext()) {
                    list.add(
                        TripSessionRecord(
                            id = c.getLong(idIdx),
                            sessionId = c.getString(sessIdx),
                            startTimeMs = c.getLong(startIdx),
                            endTimeMs = c.getLong(endIdx),
                            originName = c.getString(origIdx) ?: "Current Location",
                            destinationName = c.getString(destIdx) ?: "Destination",
                            distanceKm = c.getDouble(distIdx),
                            durationSeconds = c.getLong(durIdx),
                            status = c.getString(statIdx) ?: "COMPLETED"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying trip sessions: ${e.message}", e)
        }
        return@withContext list
    }

    suspend fun clearAllTripSessions() = withContext(Dispatchers.IO) {
        try {
            val db = writableDatabase
            db.delete(TABLE_TRIPS, null, null)
            db.delete(TABLE_LOCATIONS, null, null)
            Log.d(TAG, "All trip sessions and recorded locations cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing trip sessions: ${e.message}", e)
        }
    }
}
