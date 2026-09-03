package com.example.navsync.ui.offline

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.ui.home.HomeViewModel
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

    var regionNameInput by remember { mutableStateOf("Pune Selected Area") }

    // State for live selected bounding box
    var centerLat by remember { mutableStateOf(if (uiState.hasValidFix) uiState.latitude else 18.5204) }
    var centerLon by remember { mutableStateOf(if (uiState.hasValidFix) uiState.longitude else 73.8567) }
    var zoomLevel by remember { mutableStateOf(12.0) }

    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF00B0FF)
    val accentNavy = Color(0xFF2563EB)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)

    // Calculate approximate area dimensions & file size based on zoom & viewport bounding box
    val approxWidthKm = (40000.0 / Math.pow(2.0, zoomLevel)) * 3.5
    val approxHeightKm = approxWidthKm * 0.75
    val estimatedAreaKm2 = approxWidthKm * approxHeightKm
    val estimatedSizeMb = Math.round(estimatedAreaKm2 * 0.35 + 12.0).coerceIn(8, 250)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Map Area", color = textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = darkBg)
            )
        },
        containerColor = darkBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. LIVE OSMDROID MAP PREVIEW
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        val source = homeViewModel.mapRepository.getTileSourceForStyle(selectedStyle)
                        setTileSource(source)

                        controller.setZoom(12.0)
                        val initialPoint = GeoPoint(centerLat, centerLon)
                        controller.setCenter(initialPoint)

                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                centerLat = mapCenter.latitude
                                centerLon = mapCenter.longitude
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                zoomLevel = zoomLevelDouble
                                centerLat = mapCenter.latitude
                                centerLon = mapCenter.longitude
                                return false
                            }
                        })
                    }
                },
                update = { mapView ->
                    val targetSource = homeViewModel.mapRepository.getTileSourceForStyle(selectedStyle)
                    if (mapView.tileProvider.tileSource != targetSource) {
                        mapView.setTileSource(targetSource)
                    }
                }
            )

            // 2. GOOGLE MAPS STYLE SELECTION RECTANGLE OVERLAY (Centered)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .fillMaxHeight(0.55f)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x2200B0FF))
                    .border(2.5.dp, accentBlue, RoundedCornerShape(16.dp))
            ) {
                // Instruction pill inside selection rectangle
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardBg.copy(alpha = 0.9f))
                        .border(1.dp, borderNavy, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        "Pan & zoom map to adjust download area",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 3. TOP REGION NAME INPUT CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(14.dp)
                    .fillMaxWidth()
                    .border(1.dp, borderNavy, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = accentBlue, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = regionNameInput,
                        onValueChange = { regionNameInput = it },
                        singleLine = true,
                        placeholder = { Text("Region Name", color = textMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentBlue,
                            unfocusedBorderColor = borderNavy,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4. BOTTOM DOWNLOAD CONTROL CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .border(1.dp, borderNavy, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
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
                            Text("ESTIMATED AREA", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                String.format(Locale.US, "%.1f km × %.1f km", approxWidthKm, approxHeightKm),
                                color = textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("DOWNLOAD SIZE", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "$estimatedSizeMb MB",
                                color = accentBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isDownloading) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(downloadStatusText, color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("$downloadProgress%", color = accentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                color = accentBlue,
                                trackColor = borderNavy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    val radiusKm = (approxWidthKm / 2.0).coerceIn(2.0, 30.0)
                                    offlineRepository.downloadMapArea(
                                        name = regionNameInput.ifEmpty { "Selected Offline Area" },
                                        centerLat = centerLat,
                                        centerLon = centerLon,
                                        radiusKm = radiusKm
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentNavy),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DOWNLOAD THIS AREA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
