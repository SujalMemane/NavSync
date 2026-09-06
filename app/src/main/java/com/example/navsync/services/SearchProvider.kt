package com.example.navsync.services

import android.util.Log
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.repository.OfflineSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class SearchResultState {
    data class Success(val results: List<PlaceResult>) : SearchResultState()
    data class OutsideMapBounds(val query: String, val message: String) : SearchResultState()
    object NoResults : SearchResultState()
}

interface SearchProvider {
    val providerName: String
    suspend fun searchPlaces(query: String, userLat: Double? = null, userLon: Double? = null): SearchResultState
}

class OnlineSearchProvider(private val geocodingProvider: GeocodingProvider) : SearchProvider {

    override val providerName: String = "Online Geocoding Provider (OpenStreetMap Nominatim)"

    override suspend fun searchPlaces(query: String, userLat: Double?, userLon: Double?): SearchResultState {
        val results = geocodingProvider.searchPlaces(query, userLat, userLon)
        return if (results.isNotEmpty()) {
            SearchResultState.Success(results)
        } else {
            SearchResultState.NoResults
        }
    }
}

class OfflineSearchProvider(
    private val offlineSearchRepository: OfflineSearchRepository,
    private val offlineMapRepository: OfflineMapRepository
) : SearchProvider {

    override val providerName: String = "Offline Local Search Provider"

    companion object {
        private const val TAG = "NAVSYNC_OFFLINE_SEARCH"
    }

    override suspend fun searchPlaces(query: String, userLat: Double?, userLon: Double?): SearchResultState = withContext(Dispatchers.IO) {
        val results = offlineSearchRepository.searchOfflinePlaces(query, userLat, userLon)

        if (results.isNotEmpty()) {
            Log.d(TAG, "OFFLINE_SEARCH_SUCCESS query=$query matches=${results.size}")
            return@withContext SearchResultState.Success(results)
        }

        val downloadedRegions = offlineMapRepository.downloadedRegions.value.filter { it.status == "DOWNLOADED" }
        Log.w(TAG, "OFFLINE_SEARCH_NO_MATCH query=$query downloadedRegionsCount=${downloadedRegions.size}")

        val message = if (downloadedRegions.isEmpty()) {
            "No offline maps downloaded. Download your area to search, route, and navigate offline."
        } else {
            "No offline places or roads matching \"$query\" found in downloaded maps. Download this area to search and navigate offline."
        }

        return@withContext SearchResultState.OutsideMapBounds(
            query = query,
            message = message
        )
    }
}

class SearchProviderManager(
    val onlineSearchProvider: OnlineSearchProvider,
    val offlineSearchProvider: OfflineSearchProvider,
    val navigationModeManager: NavigationModeManager
) : SearchProvider {

    override val providerName: String
        get() = if (navigationModeManager.connectivityState.value == ConnectivityState.ONLINE) {
            onlineSearchProvider.providerName
        } else {
            offlineSearchProvider.providerName
        }

    override suspend fun searchPlaces(query: String, userLat: Double?, userLon: Double?): SearchResultState {
        return if (navigationModeManager.connectivityState.value == ConnectivityState.ONLINE) {
            val onlineResult = try {
                onlineSearchProvider.searchPlaces(query, userLat, userLon)
            } catch (e: Exception) {
                SearchResultState.NoResults
            }
            if (onlineResult is SearchResultState.Success && onlineResult.results.isNotEmpty()) {
                onlineResult
            } else {
                offlineSearchProvider.searchPlaces(query, userLat, userLon)
            }
        } else {
            offlineSearchProvider.searchPlaces(query, userLat, userLon)
        }
    }
}
