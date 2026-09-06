package com.example.navsync.repository

import android.content.Context
import android.util.Log
import com.example.navsync.data.db.OfflineMapDatabase
import com.example.navsync.services.PlaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OfflineSearchRepository(context: Context) {

    private val db = OfflineMapDatabase(context)

    companion object {
        private const val TAG = "NAVSYNC_OFFLINE_SEARCH"
    }

    suspend fun searchOfflinePlaces(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val queryClean = query.trim()
        if (queryClean.isEmpty()) return@withContext emptyList()

        Log.d(TAG, "OFFLINE_SEARCH query=$queryClean userLat=$userLat userLon=$userLon (ZERO network requests)")

        val dbPlaces = db.searchOfflinePlaces(queryClean, userLat, userLon)
        val results = dbPlaces.map { dbPlace ->
            val dist: Float? = if (userLat != null && userLon != null) {
                val res = FloatArray(1)
                android.location.Location.distanceBetween(userLat, userLon, dbPlace.latitude, dbPlace.longitude, res)
                res[0]
            } else null

            PlaceResult(
                displayName = "${dbPlace.name}, ${dbPlace.address}",
                shortName = dbPlace.name,
                address = dbPlace.address,
                latitude = dbPlace.latitude,
                longitude = dbPlace.longitude,
                distanceMeters = dist,
                placeType = dbPlace.placeType
            )
        }

        Log.d(TAG, "OFFLINE_SEARCH resultsCount=${results.size}")
        return@withContext results
    }
}
