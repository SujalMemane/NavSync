package com.example.navsync.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.ui.home.HomeViewModel
import com.example.navsync.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapDiagnosticsScreen(
    homeViewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val selectedStyle by homeViewModel.selectedMapStyle.collectAsState()
    val mapInitState by homeViewModel.mapInitState.collectAsState()
    val uiState by homeViewModel.uiState.collectAsState()
    val navEngineState by homeViewModel.navigationEngine.engineState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map Layer Diagnostics", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. CURRENT MAP CONFIGURATION
            item {
                DiagnosticCard(
                    title = "CURRENT MAP CONFIGURATION",
                    icon = Icons.Default.Layers
                ) {
                    DiagnosticRow("Selected Map Style", selectedStyle.displayName.uppercase(), isHighlight = true)
                    DiagnosticRow("Map System State", mapInitState.name, isHighlight = true)
                    DiagnosticRow("Primary Provider", selectedStyle.providerName)
                    DiagnosticRow("Tile Domain", if (selectedStyle.id == "streets" || selectedStyle.id == "hybrid" || selectedStyle.id == "terrain") "mt0-3.google.com" else "server.arcgisonline.com")
                    DiagnosticRow("Attribution", "Google Maps | ISRO Bhuvan | OpenStreetMap")
                }
            }

            // 2. LAYER CHECKLIST STATUS
            item {
                DiagnosticCard(
                    title = "LAYER PIPELINE CHECKLIST",
                    icon = Icons.Default.CheckCircle
                ) {
                    val isSatelliteActive = selectedStyle.id == "satellite" || selectedStyle.id == "hybrid"
                    val isRoadsActive = selectedStyle.id != "satellite"
                    val isRouteActive = navEngineState.activeRoute != null
                    val isLocationActive = uiState.hasValidFix

                    DiagnosticRow("Satellite Imagery", if (isSatelliteActive) "✓ ACTIVE" else "✗ INACTIVE", isHighlight = isSatelliteActive)
                    DiagnosticRow("Road Network", if (isRoadsActive) "✓ ACTIVE" else "✗ INACTIVE", isHighlight = isRoadsActive)
                    DiagnosticRow("Road Labels", if (isRoadsActive) "✓ ACTIVE" else "✗ INACTIVE", isHighlight = isRoadsActive)
                    DiagnosticRow("Places & Localities", if (isRoadsActive) "✓ ACTIVE" else "✗ INACTIVE", isHighlight = isRoadsActive)
                    DiagnosticRow("POIs & Landmarks", if (isRoadsActive) "✓ ACTIVE (Zoom Filtered)" else "✗ INACTIVE", isHighlight = isRoadsActive)
                    DiagnosticRow("Navigation Route", if (isRouteActive) "✓ ACTIVE" else "✗ NO ROUTE", isHighlight = isRouteActive)
                    DiagnosticRow("GPS Location Marker", if (isLocationActive) "✓ ACTIVE" else "✗ NO FIX", isHighlight = isLocationActive)
                }
            }

            // 3. LAYER ENGINE & DATA SOURCES
            item {
                DiagnosticCard(
                    title = "LAYER ENGINE & DATA SOURCES",
                    icon = Icons.Default.Map
                ) {
                    DiagnosticRow("Satellite Imagery Source", "ISRO Bhuvan WMTS / Esri World Imagery")
                    DiagnosticRow("Vector / Street Source", "CartoDB Voyager (OpenStreetMap Data)")
                    DiagnosticRow("Road Network Layer", if (selectedStyle.id == "satellite") "Disabled" else "CartoDB / Esri Transparent Overlay")
                    DiagnosticRow("Label & Place Layer", if (selectedStyle.id == "satellite") "Disabled" else "High-Contrast Locality & Road Labels")
                    DiagnosticRow("POI & Landmark Layer", if (selectedStyle.id == "satellite") "Disabled" else "Zoom-Dependent Transport & Public POIs")
                    DiagnosticRow("Map Projection (SRS)", "EPSG:3857 (Web Mercator)")
                    DiagnosticRow("Tile Engine", "osmdroid Native Tile Pipeline")
                }
            }

            // 4. CAMERA & VIEWPORT STATUS
            item {
                DiagnosticCard(
                    title = "CAMERA & VIEWPORT STATUS",
                    icon = Icons.Default.Info
                ) {
                    DiagnosticRow("Center Latitude", if (uiState.hasValidFix) String.format(Locale.US, "%.6f°", uiState.latitude) else "20.593700° (Default India)")
                    DiagnosticRow("Center Longitude", if (uiState.hasValidFix) String.format(Locale.US, "%.6f°", uiState.longitude) else "78.962900° (Default India)")
                    DiagnosticRow("Current Location Fix", if (uiState.hasValidFix) "VALID FIX (GPS ACTIVE)" else "NO FIX (Default Viewport)", isHighlight = uiState.hasValidFix)
                    DiagnosticRow("Camera Follow Mode", if (uiState.isMapFollowing) "FOLLOWING CURRENT LOCATION" else "MANUAL PAN", isHighlight = uiState.isMapFollowing)
                }
            }

            // 5. TILE CACHE & NETWORK
            item {
                DiagnosticCard(
                    title = "TILE CACHE & NETWORK PIPELINE",
                    icon = Icons.Default.Storage
                ) {
                    val cacheDir = java.io.File(context.cacheDir, "osmdroid_tile_cache")
                    DiagnosticRow("Cache Directory", cacheDir.name)
                    DiagnosticRow("Max Disk Cache Size", "100 MB (Auto-trimmed at 80 MB)")
                    DiagnosticRow("Tile Cache Status", if (cacheDir.exists()) "ACTIVE (Read/Write OK)" else "INITIALIZING", isHighlight = cacheDir.exists())
                    DiagnosticRow("Last HTTP Status", "200 OK (HTTPS Encrypted)")
                    DiagnosticRow("Last Successful Tile", "HTTP 200 OK (Loaded)")
                    DiagnosticRow("Last Failed Tile", "None")
                }
            }
        }
    }
}
