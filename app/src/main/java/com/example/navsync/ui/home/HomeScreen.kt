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
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import com.example.navsync.repository.MapInitState
import com.example.navsync.repository.MapStyle
import com.example.navsync.sensor.GnssStatusState
import com.example.navsync.services.BhuvanMapProvider
import com.example.navsync.services.ManeuverType
import com.example.navsync.services.MapProvider
import com.example.navsync.services.NavigationMode
import com.example.navsync.services.PlaceResult
import com.example.navsync.services.SatelliteMapProvider
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

    // Dark Color Palette
    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A).copy(alpha = 0.95f)
    val containerBg = Color(0xFF1E293B)
    val accentBlue = Color(0xFF00B0FF)
    val accentNavy = Color(0xFF2563EB)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)
    val borderNavy = Color(0xFF334155)

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Scaffold(
        bottomBar = {
            NavSyncBottomBar(currentRoute = "dashboard", onNavigateTab = onNavigateTab)
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

                        if (uiState.hasValidFix) {
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

                    // 2. Handle Hybrid Tile Overlay & Layer Order
                    val overlaySource = viewModel.mapRepository.getOverlayTileSourceForStyle(selectedStyle)
                    map.overlays.removeAll { it is TilesOverlay || it is Marker || it is Polygon || it is Polyline }
                    if (overlaySource != null) {
                        val overlay = TilesOverlay(MapTileProviderBasic(context, overlaySource), context).apply {
                            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                        }
                        map.overlays.add(overlay)
                    }

                    if (uiState.hasValidFix) {
                        val currentPoint = GeoPoint(uiState.latitude, uiState.longitude)

                        if (uiState.isMapFollowing) {
                            map.controller.animateTo(currentPoint)
                        }

                        // Draw Route Polylines
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

                        // Draw active primary route (Vibrant Blue)
                        if (activeRoute != null && activeRoute.geometry.isNotEmpty()) {
                            val activePolyline = Polyline(map).apply {
                                setPoints(activeRoute.geometry.map { GeoPoint(it.latitude, it.longitude) })
                                outlinePaint.color = android.graphics.Color.rgb(0, 176, 255)
                                outlinePaint.strokeWidth = 14f
                            }
                            map.overlays.add(activePolyline)

                            // Destination Pin Marker
                            val destPoint = GeoPoint(activeRoute.destination.latitude, activeRoute.destination.longitude)
                            val destMarker = Marker(map).apply {
                                position = destPoint
                                icon = context.getDrawable(android.R.drawable.ic_menu_compass)
                                title = navEngineState.destinationPlace?.displayName ?: "Destination"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            map.overlays.add(destMarker)
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

                        // Focused Bounding Box Overlay (View Downloaded Region)
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
                                fillPaint.color = android.graphics.Color.argb(35, 0, 176, 255)
                                outlinePaint.color = android.graphics.Color.argb(220, 0, 176, 255)
                                outlinePaint.strokeWidth = 5.0f
                            }
                            map.overlays.add(bboxPoly)
                            map.zoomToBoundingBox(focusedBbox, true, 80)
                        }

                        map.invalidate()
                    }
                }
            )

            val connectivityState by viewModel.connectivityState.collectAsState()
            val navigationModeState by viewModel.navigationMode.collectAsState()
            val activeRegion by viewModel.activeOfflineRegion.collectAsState()
            val isLocationCovered by viewModel.isLocationCoveredOffline.collectAsState()
            val searchState by viewModel.searchResultState.collectAsState()

            var activePopupType by remember { mutableStateOf<com.example.navsync.services.OfflinePopupType?>(null) }

            LaunchedEffect(Unit) {
                viewModel.popupEvent.collect { popupType ->
                    activePopupType = popupType
                }
            }

            // CENTER SCREEN TRANSITION POPUP
            com.example.navsync.ui.offline.OfflineModePopup(
                popupType = activePopupType,
                onDismiss = { activePopupType = null }
            )

            // MAP LOADING & CONNECTIVITY SUBTLE BADGE (Top End)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (connectivityState == com.example.navsync.services.ConnectivityState.OFFLINE) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (activeRegion != null) Color(0xFF0284C7) else Color(0xFFDC2626))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (activeRegion != null) "OFFLINE MAP READY" else "NO OFFLINE MAP",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (mapInitState == MapInitState.MAP_INITIALIZING || mapInitState == MapInitState.MAP_LOADING) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(cardBg)
                            .border(1.dp, borderNavy, RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), color = accentBlue, strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Loading map…", color = textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // DEAD RECKONING POPUP NOTIFICATION (Triggered on GNSS Loss)
            if (navigationModeState == com.example.navsync.services.NavMode.DEAD_RECKONING) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7C2D12)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 70.dp, start = 20.dp, end = 20.dp)
                        .fillMaxWidth()
                        .border(1.5.dp, Color(0xFFF97316), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("GNSS SIGNAL LOST", color = Color(0xFFFDBA74), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Switching to TilePrint Dead Reckoning", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Using: IMU + Offline Map", color = Color(0xFFFED7AA), fontSize = 11.sp)
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
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, borderNavy, RoundedCornerShape(24.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = accentBlue)
                            Spacer(modifier = Modifier.width(10.dp))
                            TextField(
                                value = searchQuery,
                                onValueChange = { viewModel.onSearchQueryChanged(it) },
                                placeholder = { Text("Where to?", color = textMuted, fontSize = 15.sp) },
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
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = textMuted)
                                }
                            }
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = accentBlue, strokeWidth = 2.dp)
                            }
                        }
                    }

                    // Autocomplete Search Results Overlay Sheet
                    if (searchResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .border(1.dp, borderNavy, RoundedCornerShape(16.dp))
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
                                    HorizontalDivider(color = borderNavy.copy(alpha = 0.6f))
                                }
                            }
                        }
                    } else if (searchState is com.example.navsync.services.SearchResultState.OutsideMapBounds) {
                        val outsideState = searchState as com.example.navsync.services.SearchResultState.OutsideMapBounds
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, Color(0xFFF97316), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFFFDBA74), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("LOCATION OUTSIDE OFFLINE MAP", color = Color(0xFFFDBA74), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(outsideState.message, color = Color.White, fontSize = 12.sp)
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
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .border(1.dp, borderNavy, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(accentNavy),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getManeuverIcon(nextStep?.maneuverType ?: ManeuverType.STRAIGHT),
                                contentDescription = "Turn Maneuver",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatDistanceMeters(distTurn),
                                color = accentBlue,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = nextStep?.instruction ?: "Proceed on route",
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (nextStep?.roadName?.isNotEmpty() == true && nextStep.roadName != "unnamed road") {
                                Text(
                                    text = nextStep.roadName,
                                    color = textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // 4. FLOATING GPS / NAVIGATION STATUS PILL (Top-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = if (navEngineState.mode == NavigationMode.NAVIGATING) 100.dp else 70.dp, start = 14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardBg)
                    .border(1.dp, borderNavy, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val (statusText, pillColor) = when {
                        navEngineState.isRerouting -> Pair("REROUTING...", Color(0xFFF59E0B))
                        uiState.gnssStatus == GnssStatusState.GNSS_ACTIVE -> Pair("GPS ACTIVE", Color(0xFF10B981))
                        uiState.gnssStatus == GnssStatusState.GNSS_DEGRADED -> Pair("GPS DEGRADED", Color(0xFFF59E0B))
                        else -> Pair("GPS LOST", Color(0xFFEF4444))
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(pillColor)
                    )

                    Text(
                        text = statusText,
                        color = textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. BOTTOM ROUTE PREVIEW CARD (When Route is Calculated)
            if (navEngineState.mode == NavigationMode.ROUTE_PREVIEW || navEngineState.mode == NavigationMode.ROUTE_LOADING) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .border(1.dp, borderNavy, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = navEngineState.destinationPlace?.shortName ?: "Selected Destination",
                            color = textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = navEngineState.destinationPlace?.address ?: "",
                            color = textMuted,
                            fontSize = 12.sp,
                            maxLines = 1
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        if (navEngineState.mode == NavigationMode.ROUTE_LOADING) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = accentBlue)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Calculating best route...", color = textMuted, fontSize = 14.sp)
                            }
                        } else {
                            val route = navEngineState.activeRoute
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${route?.formattedEtaString} • ${String.format(Locale.US, "%.1f km", (route?.distanceMeters ?: 0.0)/1000.0)}",
                                        color = accentBlue,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = route?.summary ?: "Fastest Route",
                                        color = textMuted,
                                        fontSize = 12.sp
                                    )
                                }

                                Button(
                                    onClick = { viewModel.startNavigation() },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentNavy),
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = "Start", tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("START", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 6. BOTTOM ACTIVE NAVIGATION BAR (ETA, SPEED, REMAINING DISTANCE, CANCEL)
            if (navEngineState.mode == NavigationMode.NAVIGATING || navEngineState.mode == NavigationMode.REROUTING || navEngineState.mode == NavigationMode.ARRIVED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .border(1.dp, borderNavy, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = navEngineState.etaFormatted,
                                color = Color(0xFF10B981),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.1f km", navEngineState.remainingDistanceMeters / 1000.0)} • ${Math.round(navEngineState.remainingDurationSeconds / 60.0)} min",
                                color = textMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Exit / Stop Navigation Button
                        IconButton(
                            onClick = { viewModel.cancelNavigation() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Exit Navigation", tint = Color.White)
                        }
                    }
                }
            }

            // 7. BOTTOM-LEFT SPEEDOMETER CIRCLE
            SpeedometerCircle(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = if (navEngineState.mode != NavigationMode.IDLE) 90.dp else 16.dp),
                speedKmh = uiState.speedKmh,
                speedColor = accentBlue,
                cardBg = cardBg,
                borderColor = borderNavy
            )

            // 8. BOTTOM-RIGHT CONTROL STACK (Layers FAB + Recenter FAB + Live Compass Dial)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (navEngineState.mode != NavigationMode.IDLE) 90.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Map Layers / Style Selector FAB
                FloatingActionButton(
                    onClick = { showMapStyleSheet = true },
                    containerColor = containerBg,
                    contentColor = textPrimary,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = "Map Style",
                        tint = accentBlue
                    )
                }

                // Recenter FAB
                FloatingActionButton(
                    onClick = {
                        if (uiState.hasValidFix) {
                            viewModel.recenterMap()
                            mapViewRef?.let { map ->
                                map.controller.animateTo(GeoPoint(uiState.latitude, uiState.longitude), 17.0, 500L)
                            }
                        } else {
                            Toast.makeText(context, "Location unavailable", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = if (uiState.isMapFollowing) accentNavy else containerBg,
                    contentColor = textPrimary,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Recenter Map",
                        tint = if (uiState.isMapFollowing) Color.White else textMuted
                    )
                }

                // Rotating Compass Dial
                CompassDialCircle(
                    headingDegrees = uiState.headingDegrees,
                    cardinalDirection = uiState.cardinalDirection,
                    cardBg = cardBg,
                    borderColor = borderNavy
                )
            }

            // Map Style Bottom Sheet Modal
            if (showMapStyleSheet) {
                MapStyleBottomSheet(
                    selectedStyle = selectedStyle,
                    onSelectStyle = { style -> viewModel.setMapStyle(style) },
                    onDismiss = { showMapStyleSheet = false }
                )
            }
        }
    }
}


@Composable
fun SearchResultRow(place: PlaceResult, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Place, contentDescription = null, tint = Color(0xFF00B0FF), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(place.shortName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(place.address, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
        }
        if (place.distanceMeters != null) {
            val km = place.distanceMeters / 1000.0
            Text(
                text = String.format(Locale.US, "%.1f km", km),
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SpeedometerCircle(
    modifier: Modifier = Modifier,
    speedKmh: Float,
    speedColor: Color,
    cardBg: Color,
    borderColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = CircleShape,
        modifier = modifier
            .size(64.dp)
            .border(1.5.dp, borderColor, CircleShape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format(Locale.US, "%.1f", speedKmh),
                color = speedColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "km/h",
                color = Color(0xFF94A3B8),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CompassDialCircle(
    headingDegrees: Float,
    cardinalDirection: String,
    cardBg: Color,
    borderColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = CircleShape,
        modifier = Modifier
            .size(52.dp)
            .border(1.5.dp, borderColor, CircleShape)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (headingDegrees >= 0f) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Compass Needle",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier
                        .size(26.dp)
                        .rotate(-headingDegrees)
                )
                Text(
                    text = cardinalDirection,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = "Compass",
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
fun NavSyncBottomBar(currentRoute: String, onNavigateTab: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFF030712)) {
        NavigationBarItem(
            selected = currentRoute == "dashboard",
            onClick = { onNavigateTab("dashboard") },
            icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
            label = { Text("Dashboard") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                unselectedIconColor = Color(0xFF64748B),
                selectedTextColor = Color.White,
                unselectedTextColor = Color(0xFF64748B),
                indicatorColor = Color(0xFF2563EB)
            )
        )
        NavigationBarItem(
            selected = currentRoute == "trips",
            onClick = { onNavigateTab("trips") },
            icon = { Icon(Icons.Default.Place, contentDescription = "Trips") },
            label = { Text("Trips") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                unselectedIconColor = Color(0xFF64748B),
                selectedTextColor = Color.White,
                unselectedTextColor = Color(0xFF64748B),
                indicatorColor = Color(0xFF2563EB)
            )
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { onNavigateTab("settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                unselectedIconColor = Color(0xFF64748B),
                selectedTextColor = Color.White,
                unselectedTextColor = Color(0xFF64748B),
                indicatorColor = Color(0xFF2563EB)
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

    paint.color = android.graphics.Color.argb(40, 0, 0, 0)
    canvas.drawCircle(center, center, sizePx * 0.28f, paint)

    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, sizePx * 0.24f, paint)

    paint.color = android.graphics.Color.rgb(0, 176, 255)
    canvas.drawCircle(center, center, sizePx * 0.17f, paint)

    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, sizePx * 0.06f, paint)

    return BitmapDrawable(context.resources, bitmap)
}
