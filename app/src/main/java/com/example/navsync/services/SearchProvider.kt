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
        } else {
            Log.w(TAG, "OFFLINE_SEARCH_OUTSIDE_BOUNDS query=$query (Not found in downloaded offline dataset)")
            return@withContext SearchResultState.OutsideMapBounds(
                query = query,
                message = "\"$query\" is not included in your downloaded offline map. Connect to the internet or download this area to search and route there."
            )
        }
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
            onlineSearchProvider.searchPlaces(query, userLat, userLon)
        } else {
            offlineSearchProvider.searchPlaces(query, userLat, userLon)
        }
    }
}
