package com.example.navsync.services

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val distanceMeters: Float? = null,
    val placeType: String? = null
)

interface GeocodingProvider {
    suspend fun searchPlaces(query: String, userLat: Double? = null, userLon: Double? = null): List<PlaceResult>
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String
}

class OnlineGeocodingProvider : GeocodingProvider {

    companion object {
        private const val TAG = "NAVSYNC_GEOCODING"
        private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&extratags=1&dedupe=1&limit=15&countrycodes=in&q="
        private const val NOMINATIM_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse?format=json&lat="
        private const val PHOTON_SEARCH_URL = "https://photon.komoot.io/api/?limit=10&q="
    }

    override suspend fun searchPlaces(query: String, userLat: Double?, userLon: Double?): List<PlaceResult> = withContext(Dispatchers.IO) {
        val queryClean = query.trim()
        if (queryClean.isEmpty()) return@withContext emptyList()

        val allScored = mutableListOf<ScoredPlaceResult>()

        // 1. Query Nominatim and Photon in PARALLEL for maximum coverage and zero regional blindspots
        coroutineScope {
            val nominatimDeferred = async {
                val list = mutableListOf<ScoredPlaceResult>()
                searchNominatim(queryClean, userLat, userLon, list)
                list
            }
            val photonDeferred = async {
                val list = mutableListOf<ScoredPlaceResult>()
                searchPhoton(queryClean, userLat, userLon, list)
                list
            }

            allScored.addAll(nominatimDeferred.await())
            allScored.addAll(photonDeferred.await())
        }

        // 2. Sort by relevance score descending
        val sortedResults = allScored.sortedByDescending { it.score }

        // 3. Smart Deduplication:
        //    - Normalize place names (strip punctuation, resolve hwy/highway, rd/road)
        //    - Deduplicate highway/road entries globally (never show duplicate road segments)
        //    - Deduplicate places within 15km with identical or near-identical names
        val deduplicated = mutableListOf<ScoredPlaceResult>()
        val seenNormalizedHighways = mutableSetOf<String>()

        for (item in sortedResults) {
            val normName = normalizePlaceName(item.result.shortName)
            val isRoadOrHighway = item.placeClass == "highway" ||
                    normName.contains("highway") || normName.contains("expressway") || normName.contains("bypass")

            if (isRoadOrHighway) {
                // If we've already included a highway with this name or containing this highway name, skip duplicate segments
                val alreadyHasHighway = seenNormalizedHighways.any { seen ->
                    seen == normName || seen.contains(normName) || normName.contains(seen)
                }
                if (alreadyHasHighway) {
                    continue
                }
                seenNormalizedHighways.add(normName)
            }

            val isDuplicate = deduplicated.any { existing ->
                val existingNorm = normalizePlaceName(existing.result.shortName)
                val exactNormMatch = existingNorm == normName
                val distBetween = FloatArray(1)
                Location.distanceBetween(
                    existing.result.latitude, existing.result.longitude,
                    item.result.latitude, item.result.longitude,
                    distBetween
                )

                // Duplicate if same normalized name within 15km or identical title
                exactNormMatch && (distBetween[0] < 15000f || isRoadOrHighway)
            }

            if (!isDuplicate) {
                deduplicated.add(item)
            }
        }

        Log.d(TAG, "Search completed for \"$queryClean\": ${deduplicated.size} clean exact results")
        return@withContext deduplicated.take(10).map { it.result }
    }

    private fun searchNominatim(
        query: String,
        userLat: Double?,
        userLon: Double?,
        outResults: MutableList<ScoredPlaceResult>
    ) {
        var conn: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "$NOMINATIM_SEARCH_URL$encodedQuery"
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NavSync-NavigationApp/1.0 (Android Native)")
                connectTimeout = 6000
                readTimeout = 6000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(jsonString)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    val displayName = obj.optString("display_name", "Unknown Place")
                    val placeClass = obj.optString("class")
                    val placeType = obj.optString("type")
                    val importance = obj.optDouble("importance", 0.0)

                    val addressObj = obj.optJSONObject("address")
                    val extraTags = obj.optJSONObject("extratags")
                    val shortName = extractShortName(obj, addressObj, extraTags, placeClass, placeType)
                    val addressFormatted = extractFormattedAddress(addressObj, displayName, shortName)

                    var dist: Float? = null
                    if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                        val resultsArr = FloatArray(1)
                        Location.distanceBetween(userLat, userLon, lat, lon, resultsArr)
                        dist = resultsArr[0]
                    }

                    val score = calculateRelevanceScore(
                        query = query,
                        shortName = shortName,
                        address = addressFormatted,
                        placeClass = placeClass,
                        placeType = placeType,
                        importance = importance,
                        distanceMeters = dist
                    )

                    val category = determineCategory(placeClass, placeType, extraTags)

                    outResults.add(
                        ScoredPlaceResult(
                            result = PlaceResult(
                                displayName = displayName,
                                shortName = shortName,
                                address = addressFormatted,
                                latitude = lat,
                                longitude = lon,
                                distanceMeters = dist,
                                placeType = category
                            ),
                            score = score,
                            placeClass = placeClass
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nominatim error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun searchPhoton(
        query: String,
        userLat: Double?,
        userLon: Double?,
        outResults: MutableList<ScoredPlaceResult>
    ) {
        var conn: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val locParam = if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                "&lat=$userLat&lon=$userLon"
            } else ""

            val url = URL("$PHOTON_SEARCH_URL$encodedQuery$locParam")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NavSync-NavigationApp/1.0 (Android Native)")
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonString)
                val features = root.optJSONArray("features") ?: return

                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    val geom = feat.optJSONObject("geometry") ?: continue
                    val coords = geom.optJSONArray("coordinates") ?: continue
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)

                    val props = feat.optJSONObject("properties") ?: continue
                    val name = props.optString("name").trim()
                    if (name.isEmpty()) continue

                    val street = props.optString("street")
                    val district = props.optString("district").ifEmpty { props.optString("locality") }
                    val city = props.optString("city")
                    val state = props.optString("state")
                    val osmKey = props.optString("osm_key")
                    val osmValue = props.optString("osm_value")

                    val addrParts = listOf(street, district, city, state).filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
                    val formattedAddr = if (addrParts.isNotEmpty()) addrParts.joinToString(", ") else city

                    var dist: Float? = null
                    if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                        val resultsArr = FloatArray(1)
                        Location.distanceBetween(userLat, userLon, lat, lon, resultsArr)
                        dist = resultsArr[0]
                    }

                    val score = calculateRelevanceScore(
                        query = query,
                        shortName = name,
                        address = formattedAddr,
                        placeClass = osmKey,
                        placeType = osmValue,
                        importance = 0.5,
                        distanceMeters = dist
                    )

                    val category = determineCategory(osmKey, osmValue, null)

                    outResults.add(
                        ScoredPlaceResult(
                            result = PlaceResult(
                                displayName = "$name, $formattedAddr",
                                shortName = name,
                                address = formattedAddr,
                                latitude = lat,
                                longitude = lon,
                                distanceMeters = dist,
                                placeType = category
                            ),
                            score = score,
                            placeClass = osmKey
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photon error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
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

    private fun extractShortName(
        obj: JSONObject,
        address: JSONObject?,
        extraTags: JSONObject?,
        placeClass: String,
        placeType: String
    ): String {
        // 1. Direct explicit name on the OpenStreetMap element
        val directName = obj.optString("name").trim()
        if (directName.isNotEmpty()) {
            return clarifyPlaceName(directName, placeClass, placeType, extraTags)
        }

        // 2. Specific landmark / POI tags in address
        if (address != null) {
            val featureKeys = listOf(
                "railway", "station", "train_station", "subway", "tram", "aeroway", "airport",
                "amenity", "tourism", "historic", "leisure", "shop", "office",
                "building", "landmark"
            )
            for (key in featureKeys) {
                val v = address.optString(key).trim()
                if (v.isNotEmpty() && !v.equals("yes", ignoreCase = true)) {
                    return clarifyPlaceName(v, placeClass, placeType, extraTags)
                }
            }
        }

        // 3. First token of display_name (e.g. "Pune Railway Station, Raja Bahadur Mill Marg...")
        val full = obj.optString("display_name", "").trim()
        if (full.isNotEmpty()) {
            val firstToken = full.split(",").firstOrNull()?.trim() ?: ""
            if (firstToken.isNotEmpty()) {
                return clarifyPlaceName(firstToken, placeClass, placeType, extraTags)
            }
        }

        // 4. Locality / Road fallback
        if (address != null) {
            val road = address.optString("road").trim()
            if (road.isNotEmpty()) return road
            val suburb = address.optString("suburb").trim()
            if (suburb.isNotEmpty()) return suburb
            val city = address.optString("city").trim()
            if (city.isNotEmpty()) return city
            val town = address.optString("town").trim()
            if (town.isNotEmpty()) return town
            val village = address.optString("village").trim()
            if (village.isNotEmpty()) return village
        }

        return "Selected Location"
    }

    private fun clarifyPlaceName(
        baseName: String,
        placeClass: String,
        placeType: String,
        extraTags: JSONObject?
    ): String {
        val lower = baseName.lowercase()
        val isSubway = extraTags?.optString("subway") == "yes" || placeType == "subway" || extraTags?.optString("station") == "subway"

        return when {
            isSubway && !lower.contains("metro") -> "$baseName (Metro)"
            placeClass == "highway" && placeType == "bus_stop" && !lower.contains("bus") -> "$baseName (Bus Stop)"
            else -> baseName
        }
    }

    private fun extractFormattedAddress(
        address: JSONObject?,
        defaultDisplay: String,
        shortName: String
    ): String {
        if (address == null) return defaultDisplay
        val parts = mutableListOf<String>()

        val road = address.optString("road").trim()
        val suburb = address.optString("suburb").trim().ifEmpty { address.optString("neighbourhood").trim() }
        val city = address.optString("city").trim().ifEmpty {
            address.optString("town").trim().ifEmpty {
                address.optString("village").trim()
            }
        }
        val state = address.optString("state").trim()
        val postcode = address.optString("postcode").trim()

        // Add parts that do not duplicate shortName
        if (road.isNotEmpty() && !shortName.contains(road, ignoreCase = true)) parts.add(road)
        if (suburb.isNotEmpty() && !shortName.contains(suburb, ignoreCase = true)) parts.add(suburb)
        if (city.isNotEmpty() && !shortName.contains(city, ignoreCase = true)) parts.add(city)
        if (state.isNotEmpty() && !shortName.contains(state, ignoreCase = true)) parts.add(state)
        if (postcode.isNotEmpty()) parts.add(postcode)

        return if (parts.isNotEmpty()) parts.joinToString(", ") else defaultDisplay
    }

    private fun calculateRelevanceScore(
        query: String,
        shortName: String,
        address: String,
        placeClass: String,
        placeType: String,
        importance: Double,
        distanceMeters: Float?
    ): Double {
        var score = 0.0
        val qLower = query.lowercase().trim()
        val nameLower = shortName.lowercase().trim()
        val addrLower = address.lowercase().trim()
        val fullSearchable = "$nameLower $addrLower"
        val qWords = qLower.split(Regex("\\s+")).filter { it.length > 1 }

        val queryWantsRoad = listOf("highway", "road", "expressway", "bypass", "marg", "lane", "street", "hwy", "flyover").any { qLower.contains(it) }

        // 1. Exact & Prefix Name Match Bonus
        if (nameLower == qLower) {
            score += 1500.0
        } else if (nameLower.startsWith(qLower)) {
            score += 800.0
        } else if (nameLower.contains(qLower)) {
            score += 550.0
        }

        // 2. Query Word Overlap (across both place name and full address)
        var matchedInName = 0
        var matchedInFull = 0
        for (w in qWords) {
            if (nameLower.contains(w)) {
                matchedInName++
            }
            if (fullSearchable.contains(w)) {
                matchedInFull++
            }
        }
        score += matchedInName * 180.0
        score += (matchedInFull - matchedInName).coerceAtLeast(0) * 80.0

        if (qWords.isNotEmpty() && matchedInFull == qWords.size) {
            score += 400.0 // All keywords matched
        }

        // 3. Category & POI Importance (Google Maps Hierarchy)
        when (placeClass) {
            "boundary", "place" -> {
                // States, Cities, Towns, Suburbs (e.g. Goa, Pune, Mumbai)
                score += when (placeType) {
                    "state", "province" -> 900.0
                    "city" -> 850.0
                    "town" -> 500.0
                    "suburb" -> 350.0
                    "administrative" -> 800.0
                    else -> 200.0
                }
            }
            "railway", "aeroway" -> score += if (placeType == "station" || placeType == "airport") 500.0 else 150.0
            "amenity" -> score += when (placeType) {
                "hospital", "clinic" -> 400.0
                "university", "college", "school" -> 350.0
                "fuel", "charging_station" -> 300.0
                "restaurant", "cafe", "fast_food" -> 220.0
                "bank", "atm", "pharmacy" -> 200.0
                else -> 120.0
            }
            "tourism", "historic", "leisure" -> score += 380.0 // Monuments, forts, museums, parks
            "shop" -> score += if (placeType == "mall" || placeType == "department_store" || placeType == "supermarket") 350.0 else 150.0
            "highway" -> {
                // If user didn't ask for a road, severely demote road/highway segments
                if (!queryWantsRoad) {
                    score -= 600.0
                } else {
                    score += if (placeType == "bus_stop") 60.0 else 100.0
                }
            }
        }

        // 4. Global OpenStreetMap Importance (0.0 to 1.0)
        score += importance * 450.0

        // 5. Proximity Bonus (Gentle boost so local places surface, but never blocks national destinations)
        if (distanceMeters != null) {
            val distKm = distanceMeters / 1000.0
            score += maxOf(0.0, 150.0 - distKm * 1.5)
        }

        return score
    }

    private fun normalizePlaceName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\b(hgway|hwy)\\b"), "highway")
            .replace(Regex("\\b(rd|rd.)\\b"), "road")
            .replace(Regex("\\b(st|st.)\\b"), "street")
            .replace(Regex("\\b(exp|expwy)\\b"), "expressway")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun determineCategory(placeClass: String, placeType: String, extraTags: JSONObject?): String {
        val isSubway = extraTags?.optString("subway") == "yes" || placeType == "subway"
        return when {
            isSubway || placeClass == "railway" || placeType == "station" -> "transit"
            placeClass == "aeroway" || placeType == "airport" -> "airport"
            placeType == "bus_stop" -> "bus"
            placeType == "hospital" || placeType == "clinic" -> "hospital"
            placeType == "fuel" || placeType == "charging_station" -> "fuel"
            placeClass == "tourism" || placeClass == "historic" -> "landmark"
            placeClass == "shop" || placeType == "mall" -> "shopping"
            placeType == "restaurant" || placeType == "cafe" || placeType == "fast_food" -> "food"
            else -> "place"
        }
    }

    private data class ScoredPlaceResult(
        val result: PlaceResult,
        val score: Double,
        val placeClass: String? = null
    )
}
