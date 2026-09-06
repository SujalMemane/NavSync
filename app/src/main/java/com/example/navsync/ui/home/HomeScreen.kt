package com.example.navsync.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.viewinterop.AndroidView
import com.example.navsync.repository.MapInitState
import com.example.navsync.repository.MapStyle
import com.example.navsync.sensor.GnssStatusState
import com.example.navsync.services.BhuvanMapProvider
import com.example.navsync.services.ManeuverType
import com.example.navsync.services.MapProvider
import com.example.navsync.services.NavigationMode
import com.example.navsync.services.PlaceResult
import com.example.navsync.services.SatelliteMapProvider
import com.example.navsync.ui.theme.*
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateTab: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val navEngineState by viewModel.navigationEngine.engineState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val mapInitState by viewModel.mapInitState.collectAsState()
    val selectedStyle by viewModel.selectedMapStyle.collectAsState()

    var showMapStyleSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Unified Dark Black & Green Palette
    val darkBg = DarkBg
    val cardBg = DarkSurface.copy(alpha = 0.95f)
    val containerBg = DarkElevated
    val accentGreen = NeonGreen
    val textPrimary = TextPrimary
    val textMuted = TextSecondary
    val borderDark = BorderDark
    val borderGreen = BorderGreenSubtle

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val isNavOrPreview = navEngineState.mode == NavigationMode.ROUTE_PREVIEW ||
            navEngineState.mode == NavigationMode.ROUTE_LOADING ||
            navEngineState.mode == NavigationMode.NAVIGATING ||
            navEngineState.mode == NavigationMode.REROUTING ||
            navEngineState.mode == NavigationMode.ARRIVED

    Scaffold(
        bottomBar = {
            if (!isNavOrPreview) {
                NavSyncBottomBar(currentRoute = "dashboard", onNavigateTab = onNavigateTab)
            }
        },
        containerColor = darkBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. MAP VIEW CONTAINER (Dominate 80-90% visual priority)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        isTilesScaledToDpi = true
                        setVerticalMapRepetitionEnabled(false)
                        setHorizontalMapRepetitionEnabled(false)
                        minZoomLevel = 3.0
                        setScrollableAreaLimitDouble(org.osmdroid.util.BoundingBox(85.0, 180.0, -85.0, -180.0))

                        val initialTileSource = viewModel.mapRepository.getTileSourceForStyle(selectedStyle)
                        setTileSource(initialTileSource)

                        if (uiState.hasPosition) {
                            controller.setZoom(17.0)
                            controller.setCenter(GeoPoint(uiState.latitude, uiState.longitude))
                        } else {
                            controller.setZoom(5.0)
                            controller.setCenter(GeoPoint(20.5937, 78.9629)) // Sensible default viewport (India)
                        }

                        setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_MOVE) {
                                viewModel.setMapFollowing(false)
                            }
                            false
                        }
                        mapViewRef = this
                    }
                },
                update = { map ->
                    // 1. Update Base Tile Source
                    val targetTileSource = viewModel.mapRepository.getTileSourceForStyle(selectedStyle)
                    if (map.tileProvider.tileSource != targetTileSource) {
                        map.setTileSource(targetTileSource)
                        Log.d("NAVSYNC_MAP", "mapView updated tileSource=${targetTileSource.name()}")
                    }

                    // Clear only dynamic location/route overlays (not base map tiles)
                    map.overlays.removeAll { it is Marker || it is Polygon || it is Polyline }

                    // 1. Draw Focused Bounding Box Overlay (View Downloaded Region)
                    val focusedBbox = viewModel.focusedBoundingBox.value
                    if (focusedBbox != null) {
                        val bboxPoly = Polygon(map).apply {
                            points = listOf(
                                GeoPoint(focusedBbox.latNorth, focusedBbox.lonWest),
                                GeoPoint(focusedBbox.latNorth, focusedBbox.lonEast),
                                GeoPoint(focusedBbox.latSouth, focusedBbox.lonEast),
                                GeoPoint(focusedBbox.latSouth, focusedBbox.lonWest),
                                GeoPoint(focusedBbox.latNorth, focusedBbox.lonWest)
                            )
                            fillPaint.color = android.graphics.Color.argb(45, 0, 230, 118)
                            outlinePaint.color = android.graphics.Color.argb(240, 0, 230, 118)
                            outlinePaint.strokeWidth = 6.0f
                        }
                        map.overlays.add(bboxPoly)
                    }

                    // 2. Draw Route Polylines
                    val activeRoute = navEngineState.activeRoute
                    val alternativeRoutes = navEngineState.alternativeRoutes

                    // Draw alternative routes first (Gray)
                    if (navEngineState.mode == NavigationMode.ROUTE_PREVIEW) {
                        alternativeRoutes.forEachIndexed { idx, altRoute ->
                            if (idx != navEngineState.selectedAlternativeIndex) {
                                val altPolyline = Polyline(map).apply {
                                    setPoints(altRoute.geometry.map { GeoPoint(it.latitude, it.longitude) })
                                    outlinePaint.color = android.graphics.Color.argb(180, 100, 116, 139)
                                    outlinePaint.strokeWidth = 10f
                                }
                                map.overlays.add(altPolyline)
                            }
                        }
                    }

                    // Draw active primary route with Google Maps 3D Dual-Layer styling
                    if (activeRoute != null && activeRoute.geometry.isNotEmpty()) {
                        val routeGeoPoints = activeRoute.geometry.map { GeoPoint(it.latitude, it.longitude) }

                        // Outer casing / shadow polyline (Deep Navy border for crisp contrast)
                        val casingPolyline = Polyline(map).apply {
                            setPoints(routeGeoPoints)
                            outlinePaint.color = android.graphics.Color.argb(220, 15, 23, 42)
                            outlinePaint.strokeWidth = 20f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                        }
                        map.overlays.add(casingPolyline)

                        // Inner core polyline (Vibrant Electric Blue)
                        val corePolyline = Polyline(map).apply {
                            setPoints(routeGeoPoints)
                            outlinePaint.color = android.graphics.Color.rgb(0, 176, 255)
                            outlinePaint.strokeWidth = 13f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                        }
                        map.overlays.add(corePolyline)

                        // Origin Marker (Start of Route)
                        if (navEngineState.mode == NavigationMode.ROUTE_PREVIEW) {
                            val startPoint = routeGeoPoints.firstOrNull()
                            if (startPoint != null) {
                                val originMarker = Marker(map).apply {
                                    position = startPoint
                                    icon = createOriginPinDrawable(context)
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                    infoWindow = null
                                }
                                map.overlays.add(originMarker)
                            }
                        }

                        // Destination Pin Marker (Google Maps style Red Pin)
                        val destPoint = GeoPoint(activeRoute.destination.latitude, activeRoute.destination.longitude)
                        val destMarker = Marker(map).apply {
                            position = destPoint
                            icon = createDestinationPinDrawable(context)
                            title = navEngineState.destinationPlace?.displayName ?: "Destination"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            infoWindow = null
                        }
                        map.overlays.add(destMarker)
                    }

                    // 3. Draw User Live Location Marker & Accuracy Circle if position is known
                    if (uiState.hasPosition) {
                        val currentPoint = GeoPoint(uiState.latitude, uiState.longitude)

                        if (uiState.isMapFollowing && focusedBbox == null) {
                            map.controller.setCenter(currentPoint)
                        }

                        // Accuracy Circle
                        if (uiState.accuracy > 0f) {
                            val accuracyCircle = Polygon(map).apply {
                                points = Polygon.pointsAsCircle(currentPoint, uiState.accuracy.toDouble())
                                fillPaint.color = android.graphics.Color.argb(25, 0, 176, 255)
                                outlinePaint.color = android.graphics.Color.argb(140, 56, 189, 248)
                                outlinePaint.strokeWidth = 2.0f
                            }
                            map.overlays.add(accuracyCircle)
                        }

                        // Live Location Marker
                        val locationMarker = Marker(map).apply {
                            position = currentPoint
                            icon = createGoogleMapsLocationDrawable(context, uiState.headingDegrees)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            infoWindow = null
                            setOnMarkerClickListener { _, _ -> true }
                        }
                        map.overlays.add(locationMarker)
                    }

                    map.invalidate()
                }
            )

            val connectivityState by viewModel.connectivityState.collectAsState()
            val navigationModeState by viewModel.navigationMode.collectAsState()
            val activeRegion by viewModel.activeOfflineRegion.collectAsState()
            val downloadedRegions by viewModel.offlineMapRepository.downloadedRegions.collectAsState()
            val isLocationCovered by viewModel.isLocationCoveredOffline.collectAsState()
            val searchState by viewModel.searchResultState.collectAsState()
            val focusedBbox by viewModel.focusedBoundingBox.collectAsState()

            var activePopupType by remember { mutableStateOf<com.example.navsync.services.OfflinePopupType?>(null) }

            LaunchedEffect(Unit) {
                viewModel.popupEvent.collect { popupType ->
                    activePopupType = null
                    kotlinx.coroutines.delay(40L)
                    activePopupType = popupType
                }
            }

            // Auto-frame route when a destination is selected and route is calculated
            var lastFramedRouteKey by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(navEngineState.mode, navEngineState.activeRoute) {
                val route = navEngineState.activeRoute
                if (navEngineState.mode == NavigationMode.ROUTE_PREVIEW && route != null && route.geometry.isNotEmpty()) {
                    val routeKey = "${route.destination.latitude}_${route.destination.longitude}_${route.distanceMeters}"
                    if (lastFramedRouteKey != routeKey) {
                        lastFramedRouteKey = routeKey
                        val points = route.geometry.map { GeoPoint(it.latitude, it.longitude) }.toMutableList()
                        if (uiState.hasPosition) {
                            points.add(GeoPoint(uiState.latitude, uiState.longitude))
                        }
                        if (points.isNotEmpty()) {
                            val bBox = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                            mapViewRef?.zoomToBoundingBox(bBox, true, 140)
                        }
                    }
                } else if (navEngineState.mode == NavigationMode.NAVIGATING) {
                    if (uiState.hasPosition) {
                        mapViewRef?.controller?.animateTo(GeoPoint(uiState.latitude, uiState.longitude), 18.0, 600L)
                    }
                } else if (navEngineState.mode == NavigationMode.IDLE) {
                    lastFramedRouteKey = null
                }
            }

            // Safe Auto-frame for Focused Bounding Box (View Downloaded Region)
            var lastFramedBboxKey by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(focusedBbox, mapViewRef) {
                val bbox = focusedBbox
                val map = mapViewRef
                if (bbox != null && map != null) {
                    val bboxKey = "${bbox.latNorth}_${bbox.lonEast}_${bbox.latSouth}_${bbox.lonWest}"
                    if (lastFramedBboxKey != bboxKey) {
                        lastFramedBboxKey = bboxKey
                        viewModel.setMapFollowing(false)
                        // Ensure MapView has valid layout dimensions before invoking Osmdroid projection animators
                        var attempts = 0
                        while ((map.width <= 0 || map.height <= 0) && attempts < 12) {
                            kotlinx.coroutines.delay(80L)
                            attempts++
                        }
                        try {
                            map.zoomToBoundingBox(bbox, true, 90)
                        } catch (e: Exception) {
                            Log.e("NAVSYNC_MAP", "Failed to zoomToBoundingBox: ${e.message}")
                            try {
                                val centerLat = (bbox.latNorth + bbox.latSouth) / 2.0
                                val centerLon = (bbox.lonEast + bbox.lonWest) / 2.0
                                map.controller.setCenter(GeoPoint(centerLat, centerLon))
                                map.controller.setZoom(13.5)
                            } catch (ignored: Exception) {}
                        }
                    }
                } else if (bbox == null) {
                    lastFramedBboxKey = null
                }
            }

            // MAP LOADING & CONNECTIVITY SUBTLE BADGE (Top Start below search bar)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        top = if (navEngineState.mode == NavigationMode.IDLE || navEngineState.mode == NavigationMode.SEARCHING) 78.dp else 16.dp,
                        start = 16.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isOffline = connectivityState == com.example.navsync.services.ConnectivityState.OFFLINE
                val hasOfflineData = (activeRegion != null || downloadedRegions.isNotEmpty())
                val regionName = activeRegion?.name ?: downloadedRegions.firstOrNull()?.name

                if (isOffline || hasOfflineData) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (hasOfflineData) DarkGreenBg else DarkRedBg)
                            .border(1.dp, if (hasOfflineData) BorderGreenSubtle else AccentRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { onNavigateTab("offline_maps") }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (hasOfflineData) NeonGreen else AccentRed))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (hasOfflineData) {
                                    if (regionName != null) "OFFLINE: $regionName" else "OFFLINE READY"
                                } else "NO OFFLINE MAP",
                                color = if (hasOfflineData) NeonGreen else AccentRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                if (mapInitState == MapInitState.MAP_INITIALIZING || mapInitState == MapInitState.MAP_LOADING) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardBg)
                            .border(1.dp, borderDark, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(11.dp), color = NeonGreen, strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Loading tiles…", color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }



            // FLOATING OFFLINE AREA PREVIEW BANNER
            if (focusedBbox != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (navEngineState.mode == NavigationMode.IDLE || navEngineState.mode == NavigationMode.SEARCHING) 76.dp else 14.dp, start = 14.dp, end = 14.dp)
                        .fillMaxWidth()
                        .border(1.dp, borderGreen, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(DarkGreenBg)
                                    .border(1.dp, borderGreen, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "PREVIEWING OFFLINE REGION",
                                    color = NeonGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Blue boundary shows available offline area",
                                    color = textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.clearFocusedBoundingBox()
                                viewModel.setMapFollowing(true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = containerBg, contentColor = textPrimary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Close", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. SEARCH BAR ("WHERE TO?") & AUTOCOMPLETE RESULTS (Top Layer)
            if (navEngineState.mode == NavigationMode.IDLE || navEngineState.mode == NavigationMode.SEARCHING) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, borderDark, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = NeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            TextField(
                                value = searchQuery,
                                onValueChange = { viewModel.onSearchQueryChanged(it) },
                                placeholder = { Text("Where to?", color = textMuted, fontSize = 14.5.sp) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                modifier = Modifier.weight(1f)
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = textMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonGreen, strokeWidth = 2.dp)
                            }
                        }
                    }

                    // Destination Search Shimmer Loading Animation
                    if (isSearching && searchResults.isEmpty() && searchQuery.isNotBlank()) {
                        SearchShimmerPlaceholder(
                            cardBg = cardBg,
                            borderDark = borderDark,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Autocomplete Search Results Overlay Sheet
                    if (searchResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .border(1.dp, borderDark, RoundedCornerShape(12.dp))
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(searchResults) { place ->
                                    SearchResultRow(
                                        place = place,
                                        onSelect = {
                                             focusManager.clearFocus()
                                            viewModel.selectPlace(place)
                                        }
                                    )
                                    HorizontalDivider(color = borderDark.copy(alpha = 0.6f))
                                }
                            }
                        }
                    } else if (searchState is com.example.navsync.services.SearchResultState.OutsideMapBounds) {
                        val outsideState = searchState as com.example.navsync.services.SearchResultState.OutsideMapBounds
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkAmberBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, AccentAmber.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Map, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("LOCATION OUTSIDE OFFLINE MAP", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(outsideState.message, color = TextPrimary, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { onNavigateTab("offline_maps") },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = TextDark),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = TextDark, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("OPEN OFFLINE MAPS", color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 3. TOP NAVIGATION INSTRUCTION CARD (Active Navigation Mode)
            if (navEngineState.mode == NavigationMode.NAVIGATING || navEngineState.mode == NavigationMode.REROUTING) {
                val nextStep = navEngineState.nextStep
                val distTurn = navEngineState.distanceToNextTurnMeters

                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .border(1.dp, borderGreen, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(DarkGreenBg)
                                .border(1.dp, borderGreen, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getManeuverIcon(nextStep?.maneuverType ?: ManeuverType.STRAIGHT),
                                contentDescription = "Turn Maneuver",
                                tint = NeonGreen,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatDistanceMeters(distTurn),
                                color = NeonGreen,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = nextStep?.instruction ?: "Proceed on route",
                                color = textPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (nextStep?.roadName?.isNotEmpty() == true && nextStep.roadName != "unnamed road") {
                                Text(
                                    text = nextStep.roadName,
                                    color = textMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                            if (uiState.isDeadReckoningActive || navigationModeState == com.example.navsync.services.NavMode.DEAD_RECKONING) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(DarkAmberBg)
                                        .border(0.75.dp, AccentAmber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(AccentAmber)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "DEAD RECKONING (ML)",
                                        color = AccentAmber,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { 
                                Log.d("NAVSYNC_UI", "Exit clicked from Top Instruction Card")
                                viewModel.cancelNavigation() 
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(containerBg)
                                .border(0.75.dp, borderDark, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close, 
                                contentDescription = "Exit Navigation", 
                                tint = textMuted, 
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }



            // 5. BOTTOM ROUTE PREVIEW SHEET (Google Maps style)
            if (navEngineState.mode == NavigationMode.ROUTE_PREVIEW || navEngineState.mode == NavigationMode.ROUTE_LOADING) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .border(1.dp, borderDark, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        // Header Row: Destination Pin & Name + Dismiss 'X' Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(DarkBlueBg)
                                        .border(1.dp, BorderBlueSubtle, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = null,
                                        tint = ElectricBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = navEngineState.destinationPlace?.shortName ?: "Selected Destination",
                                        color = textPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    if (!navEngineState.destinationPlace?.address.isNullOrEmpty()) {
                                        Text(
                                            text = navEngineState.destinationPlace?.address ?: "",
                                            color = textMuted,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Dismiss / Cancel Preview Button
                            IconButton(
                                onClick = { viewModel.cancelNavigation() },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(containerBg)
                                    .border(0.75.dp, borderDark, CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Preview", tint = textMuted, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (navEngineState.mode == NavigationMode.ROUTE_LOADING) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NeonGreen, strokeWidth = 2.dp)
                                    Text("Calculating best route...", color = textMuted, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.55f)
                                        .height(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    Color(0xFF1E293B).copy(alpha = 0.5f),
                                                    Color(0xFF475569).copy(alpha = 0.85f),
                                                    Color(0xFF1E293B).copy(alpha = 0.5f)
                                                )
                                            )
                                        )
                                )
                            }
                        } else {
                            val route = navEngineState.activeRoute

                            // Route Metrics: ETA, Distance, and Traffic Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = route?.formattedEtaString ?: "--",
                                        color = NeonGreen,
                                        fontSize = 25.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "(${String.format(Locale.US, "%.1f km", (route?.distanceMeters ?: 0.0) / 1000.0)})",
                                        color = textMuted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DarkGreenBg,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderGreenSubtle)
                                ) {
                                    Text(
                                        text = "FASTEST ROUTE",
                                        color = NeonGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (!route?.summary.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Via ${route?.summary}",
                                    color = textMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Large Prominent START Navigation Button (Neon Green with Pitch Dark Text)
                            Button(
                                onClick = { viewModel.startNavigation() },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = "Start Navigation", tint = TextDark, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "START NAVIGATION",
                                    color = TextDark,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // 6. BOTTOM ACTIVE NAVIGATION BAR (ETA, SPEED, REMAINING DISTANCE, CANCEL)
            if (navEngineState.mode == NavigationMode.NAVIGATING || navEngineState.mode == NavigationMode.REROUTING || navEngineState.mode == NavigationMode.ARRIVED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .border(1.dp, borderDark, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = navEngineState.etaFormatted,
                                    color = NeonGreen,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ETA",
                                    color = textMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                            Text(
                                text = "${String.format(Locale.US, "%.1f km", navEngineState.remainingDistanceMeters / 1000.0)} • ${Math.round(navEngineState.remainingDurationSeconds / 60.0)} min left",
                                color = textMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Modern Outlined Exit Navigation Button
                        Button(
                            onClick = { 
                                Log.d("NAVSYNC_UI", "EXIT clicked in HUD")
                                viewModel.cancelNavigation() 
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkRedBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.border(1.dp, AccentRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Exit", tint = AccentRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "EXIT",
                                color = AccentRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // 7. BOTTOM-LEFT DUAL INSTRUMENT GAUGES (SPEEDOMETER + COMPASS HEADING) & GPS STATUS
            if (navEngineState.mode != NavigationMode.ROUTE_PREVIEW && navEngineState.mode != NavigationMode.ROUTE_LOADING) {
                val speedBottomPadding = if (navEngineState.mode == NavigationMode.NAVIGATING || navEngineState.mode == NavigationMode.REROUTING || navEngineState.mode == NavigationMode.ARRIVED) 104.dp else 16.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, bottom = speedBottomPadding),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpeedometerCircle(
                            speedKmh = uiState.speedKmh
                        )

                        CompassHeadingMeterCircle(
                            headingDegrees = uiState.headingDegrees,
                            cardinalDirection = uiState.cardinalDirection
                        )
                    }

                    GpsSignalTower(
                        status = uiState.gnssStatus,
                        quality = uiState.gnssQuality
                    )
                }
            }

            // 8. BOTTOM-RIGHT RECENTER FAB
            val fabBottomPadding = when (navEngineState.mode) {
                NavigationMode.ROUTE_PREVIEW, NavigationMode.ROUTE_LOADING -> 230.dp
                NavigationMode.NAVIGATING, NavigationMode.REROUTING, NavigationMode.ARRIVED -> 104.dp
                else -> 16.dp
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = fabBottomPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Map Style / Layers Button (Placed directly above the location center button on the right side)
                if (navEngineState.mode != NavigationMode.NAVIGATING && navEngineState.mode != NavigationMode.REROUTING && searchResults.isEmpty()) {
                    FloatingActionButton(
                        onClick = { showMapStyleSheet = true },
                        containerColor = cardBg,
                        contentColor = textPrimary,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(46.dp)
                            .border(1.dp, borderDark, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "Map Style",
                            tint = textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Recenter FAB (Blue Location Recenter Button)
                FloatingActionButton(
                    onClick = {
                        if (uiState.hasPosition) {
                            viewModel.recenterMap()
                            mapViewRef?.let { map ->
                                map.controller.animateTo(GeoPoint(uiState.latitude, uiState.longitude), 17.0, 500L)
                            }
                        } else {
                            // Toast.makeText(context, "Location unavailable", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = if (uiState.isMapFollowing) DarkBlueBg else cardBg,
                    contentColor = if (uiState.isMapFollowing) ElectricBlue else ElectricBlue.copy(alpha = 0.65f),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(52.dp)
                        .border(1.dp, if (uiState.isMapFollowing) BorderBlueSubtle else borderDark, CircleShape)
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Recenter Map",
                        tint = if (uiState.isMapFollowing) ElectricBlue else ElectricBlue.copy(alpha = 0.65f)
                    )
                }
            }

            // Map Style Bottom Sheet Modal
            if (showMapStyleSheet) {
                MapStyleBottomSheet(
                    selectedStyle = selectedStyle,
                    onSelectStyle = { style -> viewModel.setMapStyle(style) },
                    onDismiss = { showMapStyleSheet = false }
                )
            }

            // 9. CENTER SCREEN TRANSITION POPUP (Layered on top of all HUD/overlays)
            com.example.navsync.ui.offline.OfflineModePopup(
                popupType = activePopupType,
                onDismiss = { activePopupType = null }
            )


        }
    }
}


@Composable
fun SearchResultRow(place: PlaceResult, onSelect: () -> Unit) {
    val (icon, iconTint, iconBg) = when (place.placeType) {
        "transit" -> Triple(Icons.Default.Train, Color(0xFF38BDF8), Color(0xFF0369A1).copy(alpha = 0.25f))
        "airport" -> Triple(Icons.Default.Flight, Color(0xFF38BDF8), Color(0xFF0284C7).copy(alpha = 0.25f))
        "bus" -> Triple(Icons.Default.DirectionsBus, Color(0xFFF59E0B), Color(0xFFB45309).copy(alpha = 0.25f))
        "hospital" -> Triple(Icons.Default.LocalHospital, Color(0xFFEF4444), Color(0xFF991B1B).copy(alpha = 0.25f))
        "food" -> Triple(Icons.Default.Restaurant, Color(0xFF10B981), Color(0xFF065F46).copy(alpha = 0.25f))
        "fuel" -> Triple(Icons.Default.LocalGasStation, Color(0xFFF59E0B), Color(0xFF78350F).copy(alpha = 0.25f))
        "shopping" -> Triple(Icons.Default.ShoppingBag, Color(0xFFEC4899), Color(0xFF831843).copy(alpha = 0.25f))
        "landmark" -> Triple(Icons.Default.AccountBalance, Color(0xFFA855F7), Color(0xFF581C87).copy(alpha = 0.25f))
        else -> Triple(Icons.Default.Place, ElectricBlue, DarkBlueBg)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.shortName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1
            )
            if (place.address.isNotEmpty()) {
                Text(
                    text = place.address,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
        if (place.distanceMeters != null) {
            val km = place.distanceMeters / 1000.0
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.US, "%.1f km", km),
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SpeedometerCircle(
    speedKmh: Float,
    modifier: Modifier = Modifier
) {
    val displaySpeed = if (speedKmh < 2.0f) 0.0f else speedKmh
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = CircleShape,
        modifier = modifier
            .size(64.dp)
            .border(1.dp, BorderGreenSubtle, CircleShape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format(Locale.US, "%.1f", displaySpeed),
                color = NeonGreen,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "km/h",
                color = TextMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun CompassHeadingMeterCircle(
    headingDegrees: Float,
    cardinalDirection: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = CircleShape,
        modifier = modifier
            .size(64.dp)
            .border(1.dp, BorderGreenSubtle, CircleShape)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Authentic classic compass dial with 3D diamond needle rotating to True North
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.minDimension / 2f

                // Outer dial bezel ring
                drawCircle(
                    color = Color(0xFF1E293B),
                    radius = radius - 3.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )

                // Perimeter degree & cardinal tick marks
                for (angle in 0 until 360 step 30) {
                    val rad = Math.toRadians(angle.toDouble()).toFloat()
                    val isCardinal = angle % 90 == 0
                    val tickLen = if (isCardinal) 4.5.dp.toPx() else 2.5.dp.toPx()
                    val tickColor = if (angle == 0) Color(0xFFFF5252) else if (isCardinal) Color(0xFF94A3B8) else Color(0xFF475569)
                    val tickWidth = if (isCardinal) 1.5.dp.toPx() else 1.dp.toPx()

                    val startX = cx + (radius - 4.dp.toPx()) * Math.sin(rad.toDouble()).toFloat()
                    val startY = cy - (radius - 4.dp.toPx()) * Math.cos(rad.toDouble()).toFloat()
                    val endX = cx + (radius - 4.dp.toPx() - tickLen) * Math.sin(rad.toDouble()).toFloat()
                    val endY = cy - (radius - 4.dp.toPx() - tickLen) * Math.cos(rad.toDouble()).toFloat()

                    drawLine(
                        color = tickColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = tickWidth
                    )
                }

                // Rotating classic diamond needle pointing to True North
                // In a physical compass, when the device turns by heading, North rotates to -heading
                val needleAngle = if (headingDegrees >= 0f) -headingDegrees else 0f
                rotate(degrees = needleAngle, pivot = Offset(cx, cy)) {
                    val needleLen = radius * 0.58f
                    val needleHalfWidth = 3.6.dp.toPx()

                    // 1. North Half (RED) - split with 3D highlight & shadow
                    val northLeft = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy - needleLen)
                        lineTo(cx - needleHalfWidth, cy)
                        lineTo(cx, cy)
                        close()
                    }
                    drawPath(northLeft, color = Color(0xFFFF334B))

                    val northRight = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy - needleLen)
                        lineTo(cx + needleHalfWidth, cy)
                        lineTo(cx, cy)
                        close()
                    }
                    drawPath(northRight, color = Color(0xFFC62828))

                    // 2. South Half (SILVER/WHITE) - split with 3D highlight & shadow
                    val southLeft = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy + needleLen)
                        lineTo(cx - needleHalfWidth, cy)
                        lineTo(cx, cy)
                        close()
                    }
                    drawPath(southLeft, color = Color(0xFFFFFFFF))

                    val southRight = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy + needleLen)
                        lineTo(cx + needleHalfWidth, cy)
                        lineTo(cx, cy)
                        close()
                    }
                    drawPath(southRight, color = Color(0xFF90A4AE))

                    // Center metallic pivot
                    drawCircle(
                        color = Color(0xFF0F172A),
                        radius = 3.5.dp.toPx()
                    )
                    drawCircle(
                        color = Color(0xFFE2E8F0),
                        radius = 2.dp.toPx()
                    )
                }
            }

            // Top North indicator & bottom precision digital readout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "N",
                    color = Color(0xFFFF5252),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
                if (headingDegrees >= 0f) {
                    Text(
                        text = String.format(Locale.US, "%.1f°", headingDegrees),
                        color = NeonGreen,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(DarkSurface.copy(alpha = 0.88f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 3.dp, vertical = 0.5.dp)
                    )
                } else {
                    Text(
                        text = "--.-°",
                        color = TextMuted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun NavSyncBottomBar(currentRoute: String, onNavigateTab: (String) -> Unit) {
    NavigationBar(
        containerColor = DarkBg,
        tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, BorderDark)
    ) {
        NavigationBarItem(
            selected = currentRoute == "home" || currentRoute == "dashboard",
            onClick = { onNavigateTab("home") },
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home", fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = NeonGreen,
                unselectedIconColor = TextMuted,
                selectedTextColor = NeonGreen,
                unselectedTextColor = TextMuted,
                indicatorColor = DarkGreenBg
            )
        )
        NavigationBarItem(
            selected = currentRoute == "offline_maps" || currentRoute == "download_map" || currentRoute == "offline_test",
            onClick = { onNavigateTab("offline_maps") },
            icon = { Icon(Icons.Default.Download, contentDescription = "Offline") },
            label = { Text("Offline", fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = NeonGreen,
                unselectedIconColor = TextMuted,
                selectedTextColor = NeonGreen,
                unselectedTextColor = TextMuted,
                indicatorColor = DarkGreenBg
            )
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { onNavigateTab("settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings", fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = NeonGreen,
                unselectedIconColor = TextMuted,
                selectedTextColor = NeonGreen,
                unselectedTextColor = TextMuted,
                indicatorColor = DarkGreenBg
            )
        )
    }
}

private fun getManeuverIcon(type: ManeuverType): ImageVector {
    return when (type) {
        ManeuverType.TURN_RIGHT, ManeuverType.TURN_SLIGHT_RIGHT, ManeuverType.TURN_SHARP_RIGHT -> Icons.Default.TurnRight
        ManeuverType.TURN_LEFT, ManeuverType.TURN_SLIGHT_LEFT, ManeuverType.TURN_SHARP_LEFT -> Icons.Default.TurnLeft
        ManeuverType.UTURN -> Icons.Default.TurnLeft
        ManeuverType.STRAIGHT -> Icons.Default.North
        ManeuverType.ARRIVE -> Icons.Default.Place
        ManeuverType.ROUNDABOUT -> Icons.Default.Sync
        else -> Icons.Default.Navigation
    }
}

private fun formatDistanceMeters(distMeters: Double): String {
    return if (distMeters >= 1000.0) {
        String.format(Locale.US, "%.1f km", distMeters / 1000.0)
    } else {
        "${Math.round(distMeters)} m"
    }
}

private fun createGoogleMapsLocationDrawable(context: Context, heading: Float): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (44 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val center = sizePx / 2f

    if (heading >= 0f) {
        // Electric Blue heading cone
        paint.color = android.graphics.Color.argb(70, 0, 176, 255)
        val path = Path().apply {
            moveTo(center, center)
            arcTo(
                center - sizePx * 0.46f,
                center - sizePx * 0.46f,
                center + sizePx * 0.46f,
                center + sizePx * 0.46f,
                heading - 120f,
                60f,
                false
            )
            lineTo(center, center)
            close()
        }
        canvas.drawPath(path, paint)
    }

    // Outer dark rim
    paint.color = android.graphics.Color.argb(50, 0, 0, 0)
    canvas.drawCircle(center, center, sizePx * 0.28f, paint)

    // White rim
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, sizePx * 0.24f, paint)

    // Electric Blue inner core (Blue location indicator)
    paint.color = android.graphics.Color.rgb(0, 176, 255)
    canvas.drawCircle(center, center, sizePx * 0.17f, paint)

    // Center white dot
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, sizePx * 0.06f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun GpsSignalTower(
    status: GnssStatusState,
    quality: com.example.navsync.services.GnssSignalQuality,
    modifier: Modifier = Modifier
) {
    val (filledBars, signalColor, statusText) = when {
        status == GnssStatusState.DEAD_RECKONING ->
            Triple(3, AccentAmber, "DEAD RECK.")
        status == GnssStatusState.LOCATION_DISABLED || status == GnssStatusState.NO_PERMISSION ->
            Triple(0, AccentRed, "GPS OFF")
        status == GnssStatusState.GNSS_LOST || quality == com.example.navsync.services.GnssSignalQuality.LOST ->
            Triple(1, AccentRed, "LOST")
        status == GnssStatusState.GNSS_DEGRADED || quality == com.example.navsync.services.GnssSignalQuality.POOR ->
            Triple(2, AccentAmber, "POOR")
        quality == com.example.navsync.services.GnssSignalQuality.FAIR ->
            Triple(3, AccentAmber, "FAIR")
        else ->
            Triple(4, NeonGreen, "GOOD")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Strength indicator bars
            for (i in 1..4) {
                val barHeight = (4 + i * 2.5f).dp
                val isFilled = i <= filledBars
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (isFilled) signalColor else BorderDark)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = statusText,
                color = signalColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }
    }
}

private fun createDestinationPinDrawable(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val pinWidth = (32 * density).toInt()
    val pinHeight = (42 * density).toInt()
    val bitmap = Bitmap.createBitmap(pinWidth, pinHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val radius = pinWidth * 0.44f
    val centerX = pinWidth / 2f
    val centerY = radius + 2 * density

    // Drop shadow
    paint.color = android.graphics.Color.argb(60, 0, 0, 0)
    canvas.drawCircle(centerX, pinHeight - 3 * density, 6 * density, paint)

    // Electric Blue Pin shape (#00B0FF)
    val path = Path().apply {
        moveTo(centerX, pinHeight - 3 * density)
        quadTo(centerX - radius * 0.85f, centerY + radius * 0.6f, centerX - radius, centerY)
        arcTo(centerX - radius, centerY - radius, centerX + radius, centerY + radius, 180f, 180f, false)
        quadTo(centerX + radius * 0.85f, centerY + radius * 0.6f, centerX, pinHeight - 3 * density)
        close()
    }
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.rgb(0, 176, 255)
    canvas.drawPath(path, paint)

    // White outline
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2 * density
    paint.color = android.graphics.Color.WHITE
    canvas.drawPath(path, paint)

    // Inner white circle
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(centerX, centerY, radius * 0.45f, paint)

    // Deep Blue core
    paint.color = android.graphics.Color.rgb(10, 34, 57)
    canvas.drawCircle(centerX, centerY, radius * 0.28f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createOriginPinDrawable(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (22 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = sizePx / 2f

    // Shadow
    paint.color = android.graphics.Color.argb(50, 0, 0, 0)
    canvas.drawCircle(center, center + 1 * density, sizePx * 0.44f, paint)

    // White outer ring
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, sizePx * 0.40f, paint)

    // Electric Blue center dot
    paint.color = android.graphics.Color.rgb(0, 176, 255)
    canvas.drawCircle(center, center, sizePx * 0.24f, paint)

    return BitmapDrawable(context.resources, bitmap)
}



@Composable
fun SearchShimmerPlaceholder(
    cardBg: Color,
    borderDark: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF1E293B).copy(alpha = 0.5f),
            Color(0xFF475569).copy(alpha = 0.85f),
            Color(0xFF1E293B).copy(alpha = 0.5f)
        ),
        start = Offset(translateAnim, translateAnim),
        end = Offset(translateAnim + 300f, translateAnim + 300f)
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderDark, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(3) { index ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (index == 0) 0.70f else if (index == 1) 0.52f else 0.64f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.38f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                    }
                }
                if (index < 2) {
                    HorizontalDivider(color = borderDark.copy(alpha = 0.5f))
                }
            }
        }
    }
}
