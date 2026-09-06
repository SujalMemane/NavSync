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
import com.example.navsync.services.DeadReckoningMlEngine
import com.example.navsync.services.DeadReckoningPositionEstimator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val satStrong: Int = 0,
    val satModerate: Int = 0,
    val satWeak: Int = 0,
    val batteryPercent: Int = 100,
    val gnssQuality: GnssSignalQuality = GnssSignalQuality.LOST,
    val gnssStatus: GnssStatusState = GnssStatusState.WAITING_FIX,
    val isTracking: Boolean = true,
    val isMapFollowing: Boolean = true,
    val isDeadReckoningActive: Boolean = false,
    val trackPoints: List<LocationPoint> = emptyList()
) {
    val hasPosition: Boolean
        get() = latitude != 0.0 && longitude != 0.0 && latitude.isFinite() && longitude.isFinite()

    val hasValidFix: Boolean
        get() = hasPosition && (gnssStatus == GnssStatusState.GNSS_ACTIVE || gnssStatus == GnssStatusState.GNSS_DEGRADED || gnssStatus == GnssStatusState.DEAD_RECKONING || isDeadReckoningActive)
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val sensorDataRepository = SensorDataRepository(application)
    val locationRepository = LocationRepository(sensorDataRepository)
    val navigationRepository = NavigationRepository(application, sensorDataRepository, locationRepository)
    val mapRepository = com.example.navsync.repository.MapRepository(application)
    val offlineMapRepository = OfflineMapRepository(application)
    val offlineSearchRepository = OfflineSearchRepository(application)
    val navigationModeManager = NavigationModeManager(application)

    // ML Dead Reckoning (IO-VNBD ONNX Model + Kinematic Position Estimator)
    val deadReckoningMlEngine = DeadReckoningMlEngine(application)
    val deadReckoningEstimator = DeadReckoningPositionEstimator()

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

    private var wasDeadReckoningOrGpsLost: Boolean = false

    fun focusOnRegionBounds(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        val bbox = org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon)
        _focusedBoundingBox.value = bbox
        val matched = offlineMapRepository.downloadedRegions.value.find {
            it.minLat == minLat && it.minLon == minLon && it.maxLat == maxLat && it.maxLon == maxLon
        } ?: offlineMapRepository.downloadedRegions.value.firstOrNull { it.status == "DOWNLOADED" }
        if (matched != null) {
            _activeOfflineRegion.value = matched
            _isLocationCoveredOffline.value = true
        }
        _uiState.update { it.copy(isMapFollowing = false) }
        Log.d("NAVSYNC_UI", "focusOnRegionBounds minLat=$minLat minLon=$minLon maxLat=$maxLat maxLon=$maxLon matched=${matched?.name}")
    }

    fun clearFocusedBoundingBox() {
        _focusedBoundingBox.value = null
        _uiState.update { it.copy(isMapFollowing = true) }
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

    // Landmarks and POIs Layer (Google Maps experience)
    private val _landmarks = MutableStateFlow<List<com.example.navsync.data.db.DbOfflinePlace>>(emptyList())
    val landmarks: StateFlow<List<com.example.navsync.data.db.DbOfflinePlace>> = _landmarks.asStateFlow()

    private val _selectedLandmark = MutableStateFlow<com.example.navsync.data.db.DbOfflinePlace?>(null)
    val selectedLandmark: StateFlow<com.example.navsync.data.db.DbOfflinePlace?> = _selectedLandmark.asStateFlow()

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    init {
        startSensors()
        loadLandmarks("ALL")

        // 1. Stream real-time phone IMU to IO-VNBD ML Dead Reckoning Engine
        viewModelScope.launch {
            var lastAx = 0f
            var lastAy = 0f
            var lastAz = 9.81f
            var lastGx = 0f
            var lastGy = 0f
            var lastGz = 0f

            launch {
                sensorDataRepository.sensorManagerRepo.accelerometerData.collect { acc ->
                    if (acc != null) {
                        lastAx = acc.x
                        lastAy = acc.y
                        lastAz = acc.z
                        deadReckoningMlEngine.addSensorData(lastAx, lastAy, lastAz, lastGx, lastGy, lastGz, acc.timestampNanos)
                    }
                }
            }

            launch {
                sensorDataRepository.sensorManagerRepo.gyroscopeData.collect { gyro ->
                    if (gyro != null) {
                        lastGx = gyro.x
                        lastGy = gyro.y
                        lastGz = gyro.z
                        deadReckoningMlEngine.addSensorData(lastAx, lastAy, lastAz, lastGx, lastGy, lastGz, gyro.timestampNanos)
                    }
                }
            }
        }

        // 2. Synchronize active navigation route with Dead Reckoning Position Estimator
        viewModelScope.launch {
            navigationEngine.engineState.collect { engineState ->
                deadReckoningEstimator.activeRoute = engineState.activeRoute
                val isNavigating = (engineState.mode == NavigationMode.NAVIGATING || engineState.mode == NavigationMode.REROUTING)
                navigationModeManager.setNavigating(isNavigating)
            }
        }

        // 3. Real-Time GNSS Satellite Telemetry Stream (Updates instantly on every satellite callback)
        viewModelScope.launch {
            sensorDataRepository.gnssData.collect { gnss ->
                val isGpsHardwareEnabled = sensorDataRepository.sensorManagerRepo.isGpsProviderEnabled()
                val isGpsActive = isGpsHardwareEnabled && gnss != null && gnss.status != GnssStatusState.LOCATION_DISABLED && gnss.status != GnssStatusState.NO_PERMISSION
                _uiState.update { current ->
                    if (isGpsActive) {
                        current.copy(
                            satelliteCount = gnss.satelliteCount,
                            usedInFix = gnss.usedInFix,
                            satStrong = gnss.strongCount,
                            satModerate = gnss.moderateCount,
                            satWeak = gnss.weakCount
                        )
                    } else {
                        current.copy(
                            satelliteCount = 0,
                            usedInFix = 0,
                            satStrong = 0,
                            satModerate = 0,
                            satWeak = 0
                        )
                    }
                }
            }
        }

        // 4. Navigation State & Position Processing
        viewModelScope.launch {
            navigationRepository.navigationState.collect { navState ->
                val gnss = navState.gnssData
                val nowMs = System.currentTimeMillis()
                val isNavigating = (navigationEngine.engineState.value.mode == NavigationMode.NAVIGATING || 
                                    navigationEngine.engineState.value.mode == NavigationMode.REROUTING)
                val isGpsHardwareEnabled = sensorDataRepository.sensorManagerRepo.isGpsProviderEnabled()

                val rawStatus = gnss?.status ?: GnssStatusState.WAITING_FIX
                val status = when {
                    !isGpsHardwareEnabled -> GnssStatusState.LOCATION_DISABLED
                    rawStatus == GnssStatusState.NO_PERMISSION -> GnssStatusState.NO_PERMISSION
                    else -> {
                        val ageMs = if (gnss != null && gnss.wallClockMillis > 0L) nowMs - gnss.wallClockMillis else Long.MAX_VALUE
                        if (isNavigating) {
                            // During active navigation: if no fix for > 5000ms, consider signal lost (tunnel / jamming blackout)
                            if (ageMs > 5_000L && (rawStatus == GnssStatusState.GNSS_ACTIVE || rawStatus == GnssStatusState.GNSS_DEGRADED)) {
                                GnssStatusState.GNSS_LOST
                            } else {
                                rawStatus
                            }
                        } else {
                            // Browsing map: allow up to 20 seconds before marking lost to prevent idle flapping
                            if (ageMs > 20_000L && (rawStatus == GnssStatusState.GNSS_ACTIVE || rawStatus == GnssStatusState.GNSS_DEGRADED)) {
                                GnssStatusState.GNSS_LOST
                            } else {
                                rawStatus
                            }
                        }
                    }
                }

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
                    ?: offlineMapRepository.downloadedRegions.value.firstOrNull { it.status == "DOWNLOADED" }
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

                // Sync Dead Reckoning Estimator reference point while GPS is valid
                if (isFixValid && gnss != null) {
                    deadReckoningEstimator.syncWithGnss(lat, lon, alt, heading, currentNavPos.speedMps)
                }

                // Check if Dead Reckoning Mode is active (after 5s blackout)
                val isDeadReckoning = (navigationModeManager.navigationMode.value == NavMode.DEAD_RECKONING)

                // Track if GPS was lost/disabled or dead reckoning was active, and emit popup when fix is restored
                if (isDeadReckoning || status == GnssStatusState.LOCATION_DISABLED || status == GnssStatusState.GNSS_LOST) {
                    wasDeadReckoningOrGpsLost = true
                } else if (isFixValid && wasDeadReckoningOrGpsLost) {
                    wasDeadReckoningOrGpsLost = false
                    deadReckoningMlEngine.reset()
                    navigationModeManager.notifyGnssRestored()
                    Log.d("NAVSYNC_GNSS", "GPS fix restored after loss/dead reckoning. Emitted GNSS_RESTORED popup.")
                }

                // Predict displacement from IMU through ONNX ML model (runs inference & logs to NAVSYNC_ML)
                val mlDisplacement = deadReckoningMlEngine.predictDisplacement()

                val effectivePos = if (isDeadReckoning) {
                    deadReckoningEstimator.step(mlDisplacement, heading)
                } else {
                    currentNavPos
                }

                val effectiveStatus = if (isDeadReckoning) GnssStatusState.DEAD_RECKONING else status

                navigationEngine.updatePosition(effectivePos, effectiveStatus)

                val isGpsHardwareActive = isGpsHardwareEnabled && status != GnssStatusState.LOCATION_DISABLED && status != GnssStatusState.NO_PERMISSION

                // Prevent speedometer jitter: clamp any speed under 2.0 km/h or stationary state directly to 0.0 km/h
                val finalSpeedKmh = if (effectivePos.isStationary || effectivePos.displaySpeedKmh < 2.0f) 0.0f else effectivePos.displaySpeedKmh

                val newState = _uiState.value.copy(
                    latitude = effectivePos.latitude,
                    longitude = effectivePos.longitude,
                    altitude = effectivePos.altitude,
                    accuracy = effectivePos.accuracy,
                    speedKmh = finalSpeedKmh,
                    rawSpeedMps = if (finalSpeedKmh == 0.0f) 0.0f else effectivePos.rawSpeedMps,
                    derivedSpeedMps = if (finalSpeedKmh == 0.0f) 0.0f else effectivePos.filteredSpeedMps,
                    isStationary = effectivePos.isStationary || finalSpeedKmh == 0.0f,
                    displacementMeters = effectivePos.displacementMeters,
                    headingDegrees = heading,
                    cardinalDirection = cardinal,
                    distanceKm = navState.distanceKm,
                    durationStr = navState.durationFormatted,
                    satelliteCount = if (isGpsHardwareActive && gnss != null) gnss.satelliteCount else 0,
                    usedInFix = if (isGpsHardwareActive && gnss != null) gnss.usedInFix else 0,
                    satStrong = if (isGpsHardwareActive && gnss != null) gnss.strongCount else 0,
                    satModerate = if (isGpsHardwareActive && gnss != null) gnss.moderateCount else 0,
                    satWeak = if (isGpsHardwareActive && gnss != null) gnss.weakCount else 0,
                    batteryPercent = navState.batteryPercent,
                    gnssQuality = if (isDeadReckoning) GnssSignalQuality.FAIR else if (!isFixValid) GnssSignalQuality.LOST else navState.gnssQuality,
                    gnssStatus = effectiveStatus,
                    isDeadReckoningActive = isDeadReckoning,
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

            val searchLat = if (_uiState.value.hasValidFix) _uiState.value.latitude else {
                val reg = _activeOfflineRegion.value ?: offlineMapRepository.downloadedRegions.value.firstOrNull()
                if (reg != null) (reg.minLat + reg.maxLat) / 2.0 else 18.5204
            }
            val searchLon = if (_uiState.value.hasValidFix) _uiState.value.longitude else {
                val reg = _activeOfflineRegion.value ?: offlineMapRepository.downloadedRegions.value.firstOrNull()
                if (reg != null) (reg.minLon + reg.maxLon) / 2.0 else 73.8567
            }

            val state = searchProviderManager.searchPlaces(query, searchLat, searchLon)
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

        val isOffline = (navigationModeManager.connectivityState.value == ConnectivityState.OFFLINE)
        val userCovered = if (_uiState.value.hasValidFix) {
            offlineMapRepository.getBestOfflineRegionForLocation(_uiState.value.latitude, _uiState.value.longitude) != null
        } else false

        val currentLat: Double
        val currentLon: Double
        if (_uiState.value.hasValidFix && (!isOffline || userCovered)) {
            currentLat = _uiState.value.latitude
            currentLon = _uiState.value.longitude
        } else {
            val reg = offlineMapRepository.getBestOfflineRegionForLocation(place.latitude, place.longitude)
                ?: _activeOfflineRegion.value
                ?: offlineMapRepository.downloadedRegions.value.firstOrNull()

            if (reg != null) {
                val regCenterLat = (reg.minLat + reg.maxLat) / 2.0
                val regCenterLon = (reg.minLon + reg.maxLon) / 2.0
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(regCenterLat, regCenterLon, place.latitude, place.longitude, dist)
                if (dist[0] < 400f) {
                    currentLat = reg.minLat + (reg.maxLat - reg.minLat) * 0.20
                    currentLon = reg.minLon + (reg.maxLon - reg.minLon) * 0.20
                } else {
                    currentLat = regCenterLat
                    currentLon = regCenterLon
                }
            } else {
                currentLat = 18.5204
                currentLon = 73.8567
            }
        }

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
        val activeRoute = navigationEngine.engineState.value.activeRoute
        val isOffline = (navigationModeManager.connectivityState.value == ConnectivityState.OFFLINE)
        val userCovered = if (_uiState.value.hasValidFix) {
            offlineMapRepository.getBestOfflineRegionForLocation(_uiState.value.latitude, _uiState.value.longitude) != null
        } else false

        if (activeRoute != null && (!_uiState.value.hasValidFix || (isOffline && !userCovered))) {
            // When starting navigation without a valid GPS fix or when testing offline outside the map,
            // align dead reckoning estimator directly to the route starting point!
            deadReckoningEstimator.syncWithGnss(
                lat = activeRoute.origin.latitude,
                lon = activeRoute.origin.longitude,
                alt = 0.0,
                bearing = activeRoute.steps.firstOrNull()?.let { 0f } ?: 0f,
                speedMps = 0f
            )
            _uiState.update { current ->
                current.copy(
                    latitude = activeRoute.origin.latitude,
                    longitude = activeRoute.origin.longitude,
                    isDeadReckoningActive = true
                )
            }
        }
        navigationEngine.startNavigation()
        setMapFollowing(true)
    }

    fun cancelNavigation() {
        Log.d("NAVSYNC_UI", "cancelNavigation invoked mode=${navigationEngine.engineState.value.mode}")
        val engineState = navigationEngine.engineState.value
        val activeRoute = engineState.activeRoute
        val wasNavigating = (engineState.mode == NavigationMode.NAVIGATING ||
                engineState.mode == NavigationMode.REROUTING ||
                engineState.mode == NavigationMode.ARRIVED)
        val destName = engineState.destinationPlace?.displayName ?: "Destination"
        val distKm = (activeRoute?.distanceMeters ?: 0.0) / 1000.0
        val isArrived = (engineState.mode == NavigationMode.ARRIVED)
        val sessionStart = activeSessionStartTimeMs

        // 1. Immediately reset navigation engine on main thread
        navigationEngine.stopNavigation()

        // 2. Immediately reset search and view model state
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchResultState.value = SearchResultState.NoResults
        _focusedBoundingBox.value = null
        _isSearching.value = false

        // 3. Reset map following
        setMapFollowing(true)

        // 4. Save trip session asynchronously in background without blocking UI
        if (wasNavigating) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val record = TripSessionRecord(
                        sessionId = "session_${System.currentTimeMillis()}",
                        startTimeMs = if (sessionStart > 0) sessionStart else System.currentTimeMillis() - 300_000L,
                        endTimeMs = System.currentTimeMillis(),
                        originName = "Current Location",
                        destinationName = destName,
                        distanceKm = distKm,
                        durationSeconds = ((System.currentTimeMillis() - sessionStart) / 1000L).coerceAtLeast(1L),
                        status = if (isArrived) "ARRIVED" else "CANCELLED"
                    )
                    dbHelper.saveTripSession(record)
                    Log.d("NAVSYNC_UI", "Trip session saved successfully on cancel")
                } catch (e: Exception) {
                    Log.e("NAVSYNC_UI", "Error saving trip session on cancel: ${e.message}", e)
                }
            }
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

    fun loadLandmarks(category: String = _selectedCategory.value) {
        viewModelScope.launch {
            val places = if (category.equals("ALL", ignoreCase = true)) {
                offlineMapRepository.getAllLandmarks()
            } else {
                offlineMapRepository.getLandmarksByCategory(category)
            }
            _landmarks.value = places
        }
    }

    fun setLandmarkCategory(category: String) {
        _selectedCategory.value = category
        loadLandmarks(category)
    }

    fun selectLandmark(landmark: com.example.navsync.data.db.DbOfflinePlace?) {
        _selectedLandmark.value = landmark
    }

    fun routeToLandmark(landmark: com.example.navsync.data.db.DbOfflinePlace) {
        _selectedLandmark.value = null
        val placeResult = PlaceResult(
            displayName = landmark.name,
            shortName = landmark.name,
            address = landmark.address,
            latitude = landmark.latitude,
            longitude = landmark.longitude
        )
        selectPlace(placeResult)
    }

    private fun getCardinalDirection(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        val index = Math.round((bearing % 360) / 45.0).toInt()
        return directions[index]
    }

    override fun onCleared() {
        super.onCleared()
        deadReckoningMlEngine.release()
    }
}
