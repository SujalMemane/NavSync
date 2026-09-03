package com.example.navsync.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.ui.home.HomeViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationDiagnosticsScreen(
    homeViewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val navState by homeViewModel.navigationEngine.engineState.collectAsState()
    val uiState by homeViewModel.uiState.collectAsState()

    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF38BDF8)
    val textMuted = Color(0xFF94A3B8)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigation Diagnostics", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = darkBg)
            )
        },
        containerColor = darkBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. SPEED PIPELINE & STATIONARY DETECTOR (TASK 1 DIAGNOSTICS)
            item {
                DiagnosticCard(
                    title = "SPEED PIPELINE & STATIONARY FILTER",
                    icon = Icons.Default.Speed,
                    cardBg = cardBg,
                    borderColor = borderNavy,
                    accentColor = Color(0xFF10B981)
                ) {
                    val pos = navState.currentPosition
                    DiagnosticRow("Displayed UI Speed", String.format(Locale.US, "%.1f km/h", uiState.speedKmh))
                    DiagnosticRow("Stationary State", if (uiState.isStationary) "YES (Stationary Phone Clamp 0.0)" else "NO (Vehicle Moving)")
                    DiagnosticRow("Raw GNSS Speed", String.format(Locale.US, "%.3f m/s (%.1f km/h)", pos?.rawSpeedMps ?: uiState.rawSpeedMps, (pos?.rawSpeedMps ?: uiState.rawSpeedMps) * 3.6f))
                    DiagnosticRow("Derived Displacement Speed", String.format(Locale.US, "%.3f m/s", pos?.filteredSpeedMps ?: uiState.derivedSpeedMps))
                    DiagnosticRow("Recent Displacement", String.format(Locale.US, "%.2f m", pos?.displacementMeters ?: uiState.displacementMeters))
                    DiagnosticRow("Location Accuracy", String.format(Locale.US, "%.1f m", uiState.accuracy))
                }
            }

            // 2. LOCATION DIAGNOSTICS
            item {
                DiagnosticCard(
                    title = "LOCATION PIPELINE (REAL GNSS)",
                    icon = Icons.Default.MyLocation,
                    cardBg = cardBg,
                    borderColor = borderNavy,
                    accentColor = accentBlue
                ) {
                    val pos = navState.currentPosition
                    val ageMs = if (pos != null) System.currentTimeMillis() - pos.wallClockMillis else -1L

                    DiagnosticRow("Latitude", String.format(Locale.US, "%.6f°", pos?.latitude ?: uiState.latitude))
                    DiagnosticRow("Longitude", String.format(Locale.US, "%.6f°", pos?.longitude ?: uiState.longitude))
                    DiagnosticRow("Accuracy", String.format(Locale.US, "%.1f m", pos?.accuracy ?: uiState.accuracy))
                    DiagnosticRow("Bearing / Heading", if ((pos?.bearing ?: uiState.headingDegrees) >= 0) String.format(Locale.US, "%.1f°", pos?.bearing ?: uiState.headingDegrees) else "--")
                    DiagnosticRow("Provider", pos?.provider ?: "FusedLocationProviderClient")
                    DiagnosticRow("Location Age", if (ageMs >= 0) "${ageMs} ms" else "Stale / Unknown")
                    DiagnosticRow("GPS Status", navState.gpsStatus.name)
                }
            }

            // 3. ROUTE DIAGNOSTICS
            item {
                DiagnosticCard(
                    title = "ROUTE ENGINE (ONLINE ROUTING)",
                    icon = Icons.Default.Route,
                    cardBg = cardBg,
                    borderColor = borderNavy,
                    accentColor = accentBlue
                ) {
                    val activeRoute = navState.activeRoute
                    DiagnosticRow("Route Name", activeRoute?.name ?: "None")
                    DiagnosticRow("Origin", String.format(Locale.US, "%.5f, %.5f", activeRoute?.origin?.latitude ?: 0.0, activeRoute?.origin?.longitude ?: 0.0))
                    DiagnosticRow("Destination", navState.destinationPlace?.displayName ?: "Not selected")
                    DiagnosticRow("Total Distance", String.format(Locale.US, "%.2f km", (activeRoute?.distanceMeters ?: 0.0) / 1000.0))
                    DiagnosticRow("Total Duration", activeRoute?.formattedEtaString ?: "--")
                    DiagnosticRow("Polyline Points", "${activeRoute?.geometry?.size ?: 0}")
                    DiagnosticRow("Steps Count", "${activeRoute?.steps?.size ?: 0}")
                    DiagnosticRow("Alternatives Found", "${navState.alternativeRoutes.size}")
                }
            }

            // 4. NAVIGATION STATE MACHINE
            item {
                DiagnosticCard(
                    title = "NAVIGATION STATE MACHINE",
                    icon = Icons.Default.Navigation,
                    cardBg = cardBg,
                    borderColor = borderNavy,
                    accentColor = accentBlue
                ) {
                    val nextStep = navState.nextStep
                    DiagnosticRow("Engine Mode", navState.mode.name)
                    DiagnosticRow("Current Maneuver", nextStep?.instruction ?: "None")
                    DiagnosticRow("Next Road", nextStep?.roadName ?: "None")
                    DiagnosticRow("Distance to Maneuver", String.format(Locale.US, "%.0f m", navState.distanceToNextTurnMeters))
                    DiagnosticRow("Remaining Distance", String.format(Locale.US, "%.2f km", navState.remainingDistanceMeters / 1000.0))
                    DiagnosticRow("ETA", navState.etaFormatted)
                    DiagnosticRow("Route Progress", String.format(Locale.US, "%.1f %%", navState.progressPercent))
                    DiagnosticRow("Off-Route Detected", if (navState.isOffRoute) "YES (Triggered)" else "NO")
                    DiagnosticRow("Rerouting In-Flight", if (navState.isRerouting) "YES" else "NO")
                    DiagnosticRow("Follow Camera Mode", if (navState.isFollowingMap) "ACTIVE" else "MANUAL PAN")
                }
            }

            // 5. NETWORK & SERVICES
            item {
                DiagnosticCard(
                    title = "NETWORK & API PROVIDERS",
                    icon = Icons.Default.NetworkCheck,
                    cardBg = cardBg,
                    borderColor = borderNavy,
                    accentColor = accentBlue
                ) {
                    DiagnosticRow("Routing Provider", "OnlineRoutingProvider (OSRM / OpenStreetMap)")
                    DiagnosticRow("Geocoding Provider", "OnlineGeocodingProvider (Nominatim)")
                    DiagnosticRow("API Auth / Key", "Free / Open API (No secret hardcoded)")
                    DiagnosticRow("Future Phase 2 Mode", "Architecture ready for OfflineRoutingProvider")
                }
            }

            // 6. MAP RENDERING
            item {
                DiagnosticCard(
                    title = "MAP RENDERING LAYER",
                    icon = Icons.Default.Map,
                    cardBg = cardBg,
                    borderColor = borderNavy,
                    accentColor = accentBlue
                ) {
                    DiagnosticRow("Map Engine", "osmdroid MapView Native")
                    DiagnosticRow("Background Imagery", "ISRO Bhuvan / Esri Satellite TileSource")
                    DiagnosticRow("Route Layer", "Polyline Overlay (Vibrant Blue)")
                    DiagnosticRow("Location Marker", "Google Maps Style Dynamic Vector Arrow Dot")
                }
            }
        }
    }
}

@Composable
fun DiagnosticCard(
    title: String,
    icon: ImageVector,
    cardBg: Color,
    borderColor: Color,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.5.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = borderColor)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
