package com.example.navsync.services

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class PlaceResult(
    val displayName: String,
    val shortName: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float? = null
)

interface GeocodingProvider {
    suspend fun searchPlaces(query: String, userLat: Double? = null, userLon: Double? = null): List<PlaceResult>
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String
}

class OnlineGeocodingProvider : GeocodingProvider {

    companion object {
        private const val TAG = "NAVSYNC_GEOCODING"
        private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=10&q="
        private const val NOMINATIM_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse?format=json&lat="
    }

    override suspend fun searchPlaces(query: String, userLat: Double?, userLon: Double?): List<PlaceResult> = withContext(Dispatchers.IO) {
        if (query.trim().isEmpty()) return@withContext emptyList()

        val results = mutableListOf<PlaceResult>()
        var conn: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val viewboxParam = if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                // Viewbox biased around current location (~50km)
                val minLon = userLon - 0.5
                val maxLon = userLon + 0.5
                val minLat = userLat - 0.5
                val maxLat = userLat + 0.5
                "&viewbox=$minLon,$maxLat,$maxLon,$minLat"
            } else ""

            val urlString = "$NOMINATIM_SEARCH_URL$encodedQuery$viewboxParam"
            Log.d(TAG, "searchQuery=$query url=$urlString")

            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NavSync-NavigationApp/1.0 (Android Native)")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(jsonString)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    val displayName = obj.optString("display_name", "Unknown Place")
                    
                    val addressObj = obj.optJSONObject("address")
                    val shortName = extractShortName(obj, addressObj)
                    val addressFormatted = extractFormattedAddress(addressObj, displayName)

                    var dist: Float? = null
                    if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                        val resultsArr = FloatArray(1)
                        Location.distanceBetween(userLat, userLon, lat, lon, resultsArr)
                        dist = resultsArr[0]
                    }

                    results.add(
                        PlaceResult(
                            displayName = displayName,
                            shortName = shortName,
                            address = addressFormatted,
                            latitude = lat,
                            longitude = lon,
                            distanceMeters = dist
                        )
                    )
                }

                // Sort by distance if current location available
                if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                    results.sortBy { it.distanceMeters ?: Float.MAX_VALUE }
                }
            } else {
                Log.w(TAG, "Search HTTP failed code=${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchPlaces query=$query: ${e.message}", e)
        } finally {
            conn?.disconnect()
        }
        return@withContext results
    }

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val urlString = "$NOMINATIM_REVERSE_URL$latitude&lon=$longitude"
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NavSync-NavigationApp/1.0 (Android Native)")
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonString)
                return@withContext obj.optString("display_name", String.format(Locale.US, "%.5f, %.5f", latitude, longitude))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reverseGeocode: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return@withContext String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }

    private fun extractShortName(obj: JSONObject, address: JSONObject?): String {
        if (address != null) {
            val amenity = address.optString("amenity")
            if (amenity.isNotEmpty()) return amenity
            val building = address.optString("building")
            if (building.isNotEmpty()) return building
            val road = address.optString("road")
            if (road.isNotEmpty()) return road
            val suburb = address.optString("suburb")
            if (suburb.isNotEmpty()) return suburb
            val city = address.optString("city")
            if (city.isNotEmpty()) return city
            val town = address.optString("town")
            if (town.isNotEmpty()) return town
            val village = address.optString("village")
            if (village.isNotEmpty()) return village
        }
        val full = obj.optString("display_name", "")
        return full.split(",").firstOrNull()?.trim() ?: "Destination"
    }

    private fun extractFormattedAddress(address: JSONObject?, defaultDisplay: String): String {
        if (address == null) return defaultDisplay
        val parts = mutableListOf<String>()
        val road = address.optString("road")
        val suburb = address.optString("suburb")
        val city = address.optString("city").ifEmpty { address.optString("town").ifEmpty { address.optString("village") } }
        val state = address.optString("state")
        val postcode = address.optString("postcode")

        if (road.isNotEmpty()) parts.add(road)
        if (suburb.isNotEmpty()) parts.add(suburb)
        if (city.isNotEmpty()) parts.add(city)
        if (state.isNotEmpty()) parts.add(state)
        if (postcode.isNotEmpty()) parts.add(postcode)

        return if (parts.isNotEmpty()) parts.joinToString(", ") else defaultDisplay
    }
}
