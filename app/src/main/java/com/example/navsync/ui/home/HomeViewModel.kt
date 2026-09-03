package com.example.navsync.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.navsync.data.SensorDataRepository
import com.example.navsync.data.db.NavSyncDbHelper
import com.example.navsync.data.db.OfflineRegionRecord
import com.example.navsync.data.db.TripSessionRecord
import com.example.navsync.repository.LocationPoint
import com.example.navsync.repository.LocationRepository
import com.example.navsync.repository.NavigationRepository
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.repository.OfflineSearchRepository
import com.example.navsync.sensor.GnssStatusState
import com.example.navsync.services.ConnectivityState
import com.example.navsync.services.GeocodingProvider
import com.example.navsync.services.GnssPositionProvider
import com.example.navsync.services.GnssSignalQuality
import com.example.navsync.services.LocationProvider
import com.example.navsync.services.NavEngineState
import com.example.navsync.services.NavigationEngine
import com.example.navsync.services.NavigationMode
import com.example.navsync.services.NavMode
import com.example.navsync.services.NavigationModeManager
import com.example.navsync.services.NavigationPosition
import com.example.navsync.services.OfflinePopupType
import com.example.navsync.services.OnlineGeocodingProvider
import com.example.navsync.services.OnlineLocationProvider
import com.example.navsync.services.OnlineRoutingProvider
import com.example.navsync.services.OfflineRoutingProvider
import com.example.navsync.services.OnlineSearchProvider
import com.example.navsync.services.OfflineSearchProvider
import com.example.navsync.services.PlaceResult
import com.example.navsync.services.PositionProvider
import com.example.navsync.services.PositionSource
import com.example.navsync.services.RoutingProvider
import com.example.navsync.services.RoutingProviderManager
import com.example.navsync.services.SearchProvider
import com.example.navsync.services.SearchProviderManager
import com.example.navsync.services.SearchResultState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speedKmh: Float = 0f,
    val rawSpeedMps: Float = 0f,
    val derivedSpeedMps: Float = 0f,
    val isStationary: Boolean = true,
    val displacementMeters: Float = 0f,
    val headingDegrees: Float = -1f,
    val cardinalDirection: String = "",
    val distanceKm: Double = 0.0,
    val durationStr: String = "00:00:00",
    val satelliteCount: Int = 0,
    val usedInFix: Int = 0,
    val batteryPercent: Int = 100,
    val gnssQuality: GnssSignalQuality = GnssSignalQuality.LOST,
    val gnssStatus: GnssStatusState = GnssStatusState.WAITING_FIX,
    val isTracking: Boolean = true,
    val isMapFollowing: Boolean = true,
    val trackPoints: List<LocationPoint> = emptyList()
) {
    val hasValidFix: Boolean
        get() = latitude != 0.0 && longitude != 0.0 && (gnssStatus == GnssStatusState.GNSS_ACTIVE || gnssStatus == GnssStatusState.GNSS_DEGRADED)
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val sensorDataRepository = SensorDataRepository(application)
    val locationRepository = LocationRepository(sensorDataRepository)
    val navigationRepository = NavigationRepository(application, sensorDataRepository, locationRepository)
    val mapRepository = com.example.navsync.repository.MapRepository(application)
    val offlineMapRepository = OfflineMapRepository(application)
    val offlineSearchRepository = OfflineSearchRepository(application)
    val navigationModeManager = NavigationModeManager(application)

    // Domain Abstractions & Services
    val locationProvider: OnlineLocationProvider = OnlineLocationProvider(sensorDataRepository.sensorManagerRepo)
    val positionProvider: GnssPositionProvider = GnssPositionProvider(locationProvider)

    val geocodingProvider: GeocodingProvider = OnlineGeocodingProvider()
    val onlineSearchProvider = OnlineSearchProvider(geocodingProvider)
    val offlineSearchProvider = OfflineSearchProvider(offlineSearchRepository, offlineMapRepository)
    val searchProviderManager = SearchProviderManager(onlineSearchProvider, offlineSearchProvider, navigationModeManager)

    val onlineRoutingProvider = OnlineRoutingProvider()
    val offlineRoutingProvider = OfflineRoutingProvider(offlineMapRepository)
    val routingProvider: RoutingProvider = RoutingProviderManager(onlineRoutingProvider, offlineRoutingProvider, navigationModeManager)
    val navigationEngine: NavigationEngine = NavigationEngine(routingProvider, geocodingProvider)
    val dbHelper = NavSyncDbHelper(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val mapInitState: StateFlow<com.example.navsync.repository.MapInitState> = mapRepository.initState
    val selectedMapStyle: StateFlow<com.example.navsync.repository.MapStyle> = mapRepository.selectedStyle

    val connectivityState: StateFlow<ConnectivityState> = navigationModeManager.connectivityState
    val navigationMode: StateFlow<NavMode> = navigationModeManager.navigationMode
    val popupEvent: SharedFlow<OfflinePopupType> = navigationModeManager.popupEvent

    private val _focusedBoundingBox = MutableStateFlow<org.osmdroid.util.BoundingBox?>(null)
    val focusedBoundingBox: StateFlow<org.osmdroid.util.BoundingBox?> = _focusedBoundingBox.asStateFlow()

    private val _activeOfflineRegion = MutableStateFlow<OfflineRegionRecord?>(null)
    val activeOfflineRegion: StateFlow<OfflineRegionRecord?> = _activeOfflineRegion.asStateFlow()

    private val _isLocationCoveredOffline = MutableStateFlow(true)
    val isLocationCoveredOffline: StateFlow<Boolean> = _isLocationCoveredOffline.asStateFlow()

    fun focusOnRegionBounds(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        val bbox = org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon)
        _focusedBoundingBox.value = bbox
        Log.d("NAVSYNC_UI", "focusOnRegionBounds minLat=$minLat minLon=$minLon maxLat=$maxLat maxLon=$maxLon")
    }

    fun clearFocusedBoundingBox() {
        _focusedBoundingBox.value = null
    }

    fun setMapStyle(style: com.example.navsync.repository.MapStyle) {
        mapRepository.setMapStyle(style)
        Log.d("NAVSYNC_UI", "STYLE_CHANGED style=${style.id}")
    }

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PlaceResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceResult>> = _searchResults.asStateFlow()

    private val _searchResultState = MutableStateFlow<SearchResultState>(SearchResultState.NoResults)
    val searchResultState: StateFlow<SearchResultState> = _searchResultState.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchDebounceJob: Job? = null
    private var activeSessionStartTimeMs: Long = 0L

    init {
        startSensors()

        viewModelScope.launch {
            navigationRepository.navigationState.collect { navState ->
                val gnss = navState.gnssData
                val nowMs = System.currentTimeMillis()

                val isStale = if (gnss != null && gnss.wallClockMillis > 0L) {
                    val ageMs = nowMs - gnss.wallClockMillis
                    ageMs > 10_000L
                } else false

                val rawStatus = gnss?.status ?: GnssStatusState.WAITING_FIX
                val status = if (isStale && (rawStatus == GnssStatusState.GNSS_ACTIVE || rawStatus == GnssStatusState.GNSS_DEGRADED)) {
                    GnssStatusState.GNSS_LOST
                } else rawStatus

                val isFixValid = (status == GnssStatusState.GNSS_ACTIVE || status == GnssStatusState.GNSS_DEGRADED)

                // Update GNSS status in NavigationModeManager
                navigationModeManager.updateGnssStatus(status)

                // Feed LocationProvider for SpeedEstimator calculation
                locationProvider.updateFromGnssData(gnss)
                val pos = locationProvider.currentPosition.value
                positionProvider.updateFromNavPosition(pos)

                val heading = when {
                    navState.compassHeading >= 0f -> navState.compassHeading
                    isFixValid && gnss != null && gnss.bearing >= 0f -> gnss.bearing
                    else -> -1f
                }
                val cardinal = if (heading >= 0f) getCardinalDirection(heading) else ""

                val lat = if (isFixValid && gnss != null) gnss.latitude else 18.5204
                val lon = if (isFixValid && gnss != null) gnss.longitude else 73.8567
                val alt = if (isFixValid && gnss != null) gnss.altitude else 0.0
                val acc = if (isFixValid && gnss != null) gnss.horizontalAccuracy else 0f

                // Update Offline Region Coverage
                val region = offlineMapRepository.getBestOfflineRegionForLocation(lat, lon)
                _activeOfflineRegion.value = region
                _isLocationCoveredOffline.value = (region != null)

                val currentNavPos = pos ?: NavigationPosition(
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    accuracy = acc,
                    speedMps = gnss?.speed ?: 0f,
                    bearing = heading,
                    timestampNanos = gnss?.timestampNanos ?: 0L,
                    wallClockMillis = gnss?.wallClockMillis ?: nowMs,
                    provider = gnss?.provider ?: "gps",
                    source = PositionSource.GNSS
                )

                navigationEngine.updatePosition(currentNavPos, status)

                val newState = _uiState.value.copy(
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    accuracy = acc,
                    speedKmh = currentNavPos.displaySpeedKmh,
                    rawSpeedMps = currentNavPos.rawSpeedMps,
                    derivedSpeedMps = currentNavPos.filteredSpeedMps,
                    isStationary = currentNavPos.isStationary,
                    displacementMeters = currentNavPos.displacementMeters,
                    headingDegrees = heading,
                    cardinalDirection = cardinal,
                    distanceKm = navState.distanceKm,
                    durationStr = navState.durationFormatted,
                    satelliteCount = gnss?.satelliteCount ?: 0,
                    usedInFix = gnss?.usedInFix ?: 0,
                    batteryPercent = navState.batteryPercent,
                    gnssQuality = if (isStale) GnssSignalQuality.LOST else navState.gnssQuality,
                    gnssStatus = status,
                    isTracking = navState.isTracking,
                    trackPoints = navState.trackPoints
                )

                _uiState.value = newState
            }
        }
    }

    fun startSensors() {
        sensorDataRepository.startSensors()
        locationProvider.startLocationUpdates()
        positionProvider.startUpdates()
        Log.d("NAVSYNC_GNSS", "homeViewModel.startSensors called")
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchDebounceJob?.cancel()

        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            _searchResultState.value = SearchResultState.NoResults
            _isSearching.value = false
            return
        }

        searchDebounceJob = viewModelScope.launch {
            delay(300L) // 300ms debounce
            _isSearching.value = true

            val currentLat = if (_uiState.value.hasValidFix) _uiState.value.latitude else 18.5204
            val currentLon = if (_uiState.value.hasValidFix) _uiState.value.longitude else 73.8567

            val state = searchProviderManager.searchPlaces(query, currentLat, currentLon)
            _searchResultState.value = state

            if (state is SearchResultState.Success) {
                _searchResults.value = state.results
            } else {
                _searchResults.value = emptyList()
            }
            _isSearching.value = false
        }
    }

    fun selectPlace(place: PlaceResult) {
        _searchResults.value = emptyList()
        _searchResultState.value = SearchResultState.NoResults
        _searchQuery.value = place.shortName

        val currentLat = if (_uiState.value.hasValidFix) _uiState.value.latitude else 18.5204
        val currentLon = if (_uiState.value.hasValidFix) _uiState.value.longitude else 73.8567

        navigationEngine.requestRouteToDestination(
            destination = place,
            currentLocation = LocationPoint(currentLat, currentLon)
        )
    }

    fun selectAlternativeRoute(index: Int) {
        navigationEngine.selectAlternativeRoute(index)
    }

    fun startNavigation() {
        activeSessionStartTimeMs = System.currentTimeMillis()
        navigationEngine.startNavigation()
        setMapFollowing(true)
    }

    fun cancelNavigation() {
        viewModelScope.launch {
            val engineState = navigationEngine.engineState.value
            val activeRoute = engineState.activeRoute

            if (engineState.mode == NavigationMode.NAVIGATING || engineState.mode == NavigationMode.ARRIVED) {
                val record = TripSessionRecord(
                    sessionId = "session_${System.currentTimeMillis()}",
                    startTimeMs = if (activeSessionStartTimeMs > 0) activeSessionStartTimeMs else System.currentTimeMillis() - 300_000L,
                    endTimeMs = System.currentTimeMillis(),
                    originName = "Current Location",
                    destinationName = engineState.destinationPlace?.displayName ?: "Destination",
                    distanceKm = (activeRoute?.distanceMeters ?: 0.0) / 1000.0,
                    durationSeconds = ((System.currentTimeMillis() - activeSessionStartTimeMs) / 1000L).coerceAtLeast(1L),
                    status = if (engineState.mode == NavigationMode.ARRIVED) "ARRIVED" else "CANCELLED"
                )
                dbHelper.saveTripSession(record)
            }

            navigationEngine.stopNavigation()
            _searchQuery.value = ""
            _searchResults.value = emptyList()
            _searchResultState.value = SearchResultState.NoResults
        }
    }

    fun setMapFollowing(following: Boolean) {
        _uiState.value = _uiState.value.copy(isMapFollowing = following)
        navigationEngine.setMapFollowing(following)
        Log.d("NAVSYNC_UI", "setMapFollowing=$following")
    }

    fun recenterMap() {
        _uiState.value = _uiState.value.copy(isMapFollowing = true)
        navigationEngine.setMapFollowing(true)
        Log.d("NAVSYNC_UI", "recenterMap following=true")
    }

    private fun getCardinalDirection(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        val index = Math.round((bearing % 360) / 45.0).toInt()
        return directions[index]
    }
}
