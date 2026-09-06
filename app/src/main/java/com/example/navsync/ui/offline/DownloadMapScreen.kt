package com.example.navsync.ui.offline

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.ui.home.HomeViewModel
import com.example.navsync.ui.theme.*
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadMapScreen(
    homeViewModel: HomeViewModel,
    offlineRepository: OfflineMapRepository,
    onBack: () -> Unit
) {
    val uiState by homeViewModel.uiState.collectAsState()
    val selectedStyle by homeViewModel.selectedMapStyle.collectAsState()
    val isDownloading by offlineRepository.isDownloading.collectAsState()
    val downloadProgress by offlineRepository.downloadProgress.collectAsState()
    val downloadStatusText by offlineRepository.downloadStatusText.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var regionNameInput by remember { mutableStateOf("Offline Map Area") }
    var hasUserManuallyEditedName by remember { mutableStateOf(false) }
    var isDetectingLocationName by remember { mutableStateOf(false) }

    // MapView reference for direct zooming and viewport center extraction
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    LaunchedEffect(Unit) {
        offlineRepository.resetDownloadState()
    }

    // State for initial location and live zoom level
    val initialLat = remember { if (uiState.hasPosition) uiState.latitude else 18.5204 }
    val initialLon = remember { if (uiState.hasPosition) uiState.longitude else 73.8567 }
    var zoomLevel by remember { mutableStateOf(13.0) }
    var mapCenterCoord by remember { mutableStateOf(GeoPoint(initialLat, initialLon)) }

    // Automatically detect and update region name as the user pans the viewfinder
    LaunchedEffect(mapCenterCoord) {
        if (hasUserManuallyEditedName) return@LaunchedEffect
        delay(700L)
        try {
            isDetectingLocationName = true
            val address = homeViewModel.geocodingProvider.reverseGeocode(mapCenterCoord.latitude, mapCenterCoord.longitude)
            if (!hasUserManuallyEditedName && address.isNotBlank()) {
                val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val detectedLocality = parts.firstOrNull { part ->
                    part.length in 3..25 && !part.all { char -> char.isDigit() }
                } ?: parts.firstOrNull() ?: "Selected Area"

                regionNameInput = "$detectedLocality Offline Map"
            }
        } catch (e: Exception) {
            Log.e("NAVSYNC_DOWNLOAD", "Auto reverse-geocode error: ${e.message}")
        } finally {
            isDetectingLocationName = false
        }
    }

    // Calculate approximate area dimensions & file size based on zoom & viewport bounding box
    val approxWidthKm = (40000.0 / Math.pow(2.0, zoomLevel)) * 3.5
    val approxHeightKm = approxWidthKm * 0.75
    val estimatedAreaKm2 = approxWidthKm * approxHeightKm
    val estimatedSizeMb = Math.round(estimatedAreaKm2 * 0.35 + 12.0).coerceIn(8, 250)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Map Area", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. LIVE OSMDROID MAP VIEW (Full gesture pass-through)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        minZoomLevel = 3.0
                        maxZoomLevel = 20.0
                        val source = homeViewModel.mapRepository.getTileSourceForStyle(selectedStyle)
                        setTileSource(source)

                        controller.setZoom(13.0)
                        val initialPoint = GeoPoint(initialLat, initialLon)
                        controller.setCenter(initialPoint)

                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                mapCenterCoord = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                zoomLevel = zoomLevelDouble
                                mapCenterCoord = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                                return false
                            }
                        })
                        mapViewRef = this
                    }
                },
                update = { mapView ->
                    val targetSource = homeViewModel.mapRepository.getTileSourceForStyle(selectedStyle)
                    if (mapView.tileProvider.tileSource != targetSource) {
                        mapView.setTileSource(targetSource)
                    }
                }
            )

            // 2. TOUCH-TRANSPARENT VIEWFINDER HUD OVERLAY (Zero touch blocking)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxWidth = size.width * 0.84f
                val boxHeight = size.height * 0.52f
                val left = (size.width - boxWidth) / 2f
                val top = (size.height - boxHeight) / 2f - 20.dp.toPx()
                val right = left + boxWidth
                val bottom = top + boxHeight
                val cornerRadiusPx = 16.dp.toPx()

                // A. Dimmed letterbox shading outside the viewfinder window
                val scrimColor = Color(0x7005080E)
                // Top scrim
                drawRect(scrimColor, Offset(0f, 0f), Size(size.width, top))
                // Bottom scrim
                drawRect(scrimColor, Offset(0f, bottom), Size(size.width, size.height - bottom))
                // Left scrim
                drawRect(scrimColor, Offset(0f, top), Size(left, boxHeight))
                // Right scrim
                drawRect(scrimColor, Offset(right, top), Size(size.width - right, boxHeight))

                // B. Subtle tint inside the viewfinder
                drawRoundRect(
                    color = Color(0x1800E676),
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )

                // C. Neon Green Outline
                drawRoundRect(
                    color = NeonGreen,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // D. Tactical Corner Bracket Accents
                val bracketLen = 24.dp.toPx()
                val bracketStroke = 4.dp.toPx()
                val bracketColor = NeonGreen

                // Top-Left corner
                drawLine(bracketColor, Offset(left, top + bracketLen), Offset(left, top), bracketStroke)
                drawLine(bracketColor, Offset(left, top), Offset(left + bracketLen, top), bracketStroke)
                // Top-Right corner
                drawLine(bracketColor, Offset(right - bracketLen, top), Offset(right, top), bracketStroke)
                drawLine(bracketColor, Offset(right, top), Offset(right, top + bracketLen), bracketStroke)
                // Bottom-Left corner
                drawLine(bracketColor, Offset(left, bottom - bracketLen), Offset(left, bottom), bracketStroke)
                drawLine(bracketColor, Offset(left, bottom), Offset(left + bracketLen, bottom), bracketStroke)
                // Bottom-Right corner
                drawLine(bracketColor, Offset(right - bracketLen, bottom), Offset(right, bottom), bracketStroke)
                drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - bracketLen), bracketStroke)
            }

            // 3. INSTRUCTION PILL (Floating neatly above the viewfinder)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkElevated.copy(alpha = 0.95f))
                    .border(1.dp, BorderGreenSubtle, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Pan & pinch map to adjust download area",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 4. FLOATING MAP CONTROLS (+ Zoom, - Zoom, GPS Recenter)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Zoom In
                IconButton(
                    onClick = {
                        mapViewRef?.controller?.zoomIn()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DarkSurface.copy(alpha = 0.94f))
                        .border(1.dp, BorderDark, CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = NeonGreen, modifier = Modifier.size(22.dp))
                }

                // Zoom Out
                IconButton(
                    onClick = {
                        mapViewRef?.controller?.zoomOut()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DarkSurface.copy(alpha = 0.94f))
                        .border(1.dp, BorderDark, CircleShape)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = NeonGreen, modifier = Modifier.size(22.dp))
                }

                // Recenter GPS
                IconButton(
                    onClick = {
                        val targetLat = if (uiState.hasPosition) uiState.latitude else 18.5204
                        val targetLon = if (uiState.hasPosition) uiState.longitude else 73.8567
                        mapViewRef?.controller?.animateTo(GeoPoint(targetLat, targetLon))
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DarkSurface.copy(alpha = 0.94f))
                        .border(1.dp, BorderGreenSubtle, CircleShape)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Center Location", tint = NeonGreen, modifier = Modifier.size(20.dp))
                }
            }

            // 5. TOP REGION NAME INPUT CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(14.dp)
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = regionNameInput,
                        onValueChange = {
                            regionNameInput = it
                            hasUserManuallyEditedName = true
                        },
                        singleLine = true,
                        placeholder = { Text(if (isDetectingLocationName) "Detecting location..." else "Region Name", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 6. BOTTOM DOWNLOAD CONTROL CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ESTIMATED AREA", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = String.format(Locale.US, "%.1f km × %.1f km", approxWidthKm, approxHeightKm),
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("DOWNLOAD SIZE", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "$estimatedSizeMb MB",
                                color = NeonGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    var downloadFinished by remember { mutableStateOf(false) }

                    if (isDownloading) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(downloadStatusText, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("$downloadProgress%", color = NeonGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                color = NeonGreen,
                                trackColor = DarkGreenBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                    } else if (downloadFinished) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = DarkGreenBg),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, BorderGreenSubtle, RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Area Downloaded Successfully!", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("All nodes, road segments & POIs dumped to Logcat", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Logcat Tag: NAVSYNC_OFFLINE_MAP", color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }

                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = TextDark),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("VIEW IN OFFLINE MAPS", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val name = regionNameInput.trim().ifEmpty { "Selected Offline Area" }
                                        val map = mapViewRef
                                        val proj = map?.projection

                                        if (map != null && proj != null && map.width > 0 && map.height > 0) {
                                            // Compute the EXACT geographic coordinates matching the on-screen green viewfinder frame
                                            val boxWidth = map.width * 0.84f
                                            val boxHeight = map.height * 0.52f
                                            val left = (map.width - boxWidth) / 2f
                                            val density = context.resources.displayMetrics.density
                                            val top = (map.height - boxHeight) / 2f - 20f * density
                                            val right = left + boxWidth
                                            val bottom = top + boxHeight

                                            val nwPoint = proj.fromPixels(left.toInt(), top.toInt())
                                            val sePoint = proj.fromPixels(right.toInt(), bottom.toInt())

                                            val maxLat = maxOf(nwPoint.latitude, sePoint.latitude)
                                            val minLat = minOf(nwPoint.latitude, sePoint.latitude)
                                            val maxLon = maxOf(nwPoint.longitude, sePoint.longitude)
                                            val minLon = minOf(nwPoint.longitude, sePoint.longitude)

                                            offlineRepository.downloadMapArea(
                                                name = name,
                                                minLat = minLat,
                                                minLon = minLon,
                                                maxLat = maxLat,
                                                maxLon = maxLon
                                            )
                                        } else {
                                            val centerCandidate = map?.mapCenter
                                            val centerLat = if (centerCandidate != null && centerCandidate.latitude != 0.0) centerCandidate.latitude else initialLat
                                            val centerLon = if (centerCandidate != null && centerCandidate.longitude != 0.0) centerCandidate.longitude else initialLon
                                            val currentZoom = map?.zoomLevelDouble?.takeIf { it > 0.0 } ?: zoomLevel
                                            val radiusKm = ((40000.0 / Math.pow(2.0, currentZoom)) * 1.6).coerceIn(2.0, 45.0)

                                            offlineRepository.downloadMapArea(
                                                name = name,
                                                centerLat = centerLat,
                                                centerLon = centerLon,
                                                radiusKm = radiusKm
                                            )
                                        }
                                        downloadFinished = true
                                    } catch (e: Exception) {
                                        android.util.Log.e("DownloadMapScreen", "Download error: ${e.message}", e)
                                    }
                                }
                            },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = TextDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = TextDark)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DOWNLOAD THIS AREA", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
