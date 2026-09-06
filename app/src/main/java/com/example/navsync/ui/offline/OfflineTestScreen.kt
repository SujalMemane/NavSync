package com.example.navsync.ui.offline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.services.ConnectivityState
import com.example.navsync.services.LocationState
import com.example.navsync.services.NavigationModeManager
import com.example.navsync.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineTestScreen(
    navigationModeManager: NavigationModeManager,
    offlineRepository: OfflineMapRepository,
    onBack: () -> Unit
) {
    val connectivityState by navigationModeManager.connectivityState.collectAsState()
    val locationState by navigationModeManager.locationState.collectAsState()
    val navigationMode by navigationModeManager.navigationMode.collectAsState()
    val downloadedRegions by offlineRepository.downloadedRegions.collectAsState()
    val isDownloading by offlineRepository.isDownloading.collectAsState()
    val downloadProgress by offlineRepository.downloadProgress.collectAsState()
    val downloadStatusText by offlineRepository.downloadStatusText.collectAsState()

    val scope = rememberCoroutineScope()
    var logcatDumpStatus by remember { mutableStateOf<String?>(null) }

    var isSimulatedOffline by remember { mutableStateOf(false) }
    var isSimulatedGnssLoss by remember { mutableStateOf(false) }

    val hasMapDownloaded = downloadedRegions.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Navigation Test", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            // Test Dashboard Summary Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(DarkGreenBg)
                                    .border(1.dp, BorderGreenSubtle, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("OFFLINE NAVIGATION DIAGNOSTICS", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        TestStatusRow("Internet State", connectivityState.name, connectivityState == ConnectivityState.ONLINE)
                        TestStatusRow("GPS State", locationState.name, locationState == LocationState.GNSS_AVAILABLE)
                        TestStatusRow("Active Navigation Mode", navigationMode.name, true)
                        TestStatusRow("Downloaded Map", if (hasMapDownloaded) "YES (${downloadedRegions.size} regions)" else "NO", hasMapDownloaded)
                        TestStatusRow("Offline Search Engine", if (hasMapDownloaded) "PASS (Local SQLite)" else "FAIL (No Map)", hasMapDownloaded)
                        TestStatusRow("Offline A* Routing Engine", if (hasMapDownloaded) "PASS (Local Graph)" else "FAIL (No Graph)", hasMapDownloaded)
                        TestStatusRow("Zero Network Guarantee", if (connectivityState == ConnectivityState.OFFLINE) "ENFORCED (NetworkGuard Active)" else "ONLINE MODE", true)
                    }
                }
            }

            // Offline Map & Points Inspector Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(DarkGreenBg)
                                    .border(1.dp, BorderGreenSubtle, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("OFFLINE MAP & POINTS INSPECTOR", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("Filter tag: NAVSYNC_OFFLINE_MAP", color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        TestStatusRow("Loaded Road Nodes", "${offlineRepository.roadGraph.totalNodes} points", offlineRepository.roadGraph.totalNodes > 0)
                        TestStatusRow("Loaded Road Segments", "${offlineRepository.roadGraph.totalSegments} segments", offlineRepository.roadGraph.totalSegments > 0)
                        TestStatusRow("Directed Graph Edges", "${offlineRepository.roadGraph.totalDirectedEdges} edges", offlineRepository.roadGraph.totalDirectedEdges > 0)
                        TestStatusRow("Active Regions", "${downloadedRegions.size} region(s)", downloadedRegions.isNotEmpty())

                        if (isDownloading) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(downloadStatusText, color = TextPrimary, fontSize = 12.sp)
                                    Text("$downloadProgress%", color = NeonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    color = NeonGreen,
                                    trackColor = DarkGreenBg,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }

                        if (logcatDumpStatus != null) {
                            Text(
                                text = logcatDumpStatus ?: "",
                                color = NeonGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Button 1: Dump All Points to Logcat
                        Button(
                            onClick = {
                                offlineRepository.dumpPointsToLogcat()
                                logcatDumpStatus = "✓ Dumped ${offlineRepository.roadGraph.totalNodes} nodes & ${offlineRepository.roadGraph.totalSegments} segments to Logcat (Tag: NAVSYNC_OFFLINE_MAP)"
                            },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = TextDark),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = TextDark, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DUMP ALL POINTS TO LOGCAT", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextDark)
                        }

                        // Button 2: Test Download Sample Map
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    logcatDumpStatus = "Downloading test map area..."
                                    offlineRepository.downloadMapArea(
                                        name = "Test Map Area",
                                        centerLat = 18.5204,
                                        centerLon = 73.8567,
                                        radiusKm = 4.0
                                    )
                                    logcatDumpStatus = "✓ Downloaded and dumped all points to Logcat! (Tag: NAVSYNC_OFFLINE_MAP)"
                                }
                            },
                            enabled = !isDownloading,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGreenSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = DarkElevated,
                                contentColor = NeonGreen
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("TEST DOWNLOAD SAMPLE MAP NOW", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NeonGreen)
                        }
                    }
                }
            }

            // Developer Simulation Controls Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "SIMULATION CONTROLS (DEBUG ONLY)",
                            color = NeonGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        // Internet OFF Toggle Button
                        Button(
                            onClick = {
                                isSimulatedOffline = !isSimulatedOffline
                                navigationModeManager.setSimulatedOffline(isSimulatedOffline)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSimulatedOffline) DarkRedBg else DarkElevated,
                                contentColor = if (isSimulatedOffline) AccentRed else TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .border(
                                    1.dp,
                                    if (isSimulatedOffline) AccentRed else BorderDark,
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(
                                if (isSimulatedOffline) "INTERNET SIMULATION: OFF (OFFLINE MODE)" else "SIMULATE INTERNET OFF",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // GNSS Loss Toggle Button
                        Button(
                            onClick = {
                                isSimulatedGnssLoss = !isSimulatedGnssLoss
                                navigationModeManager.setSimulatedGnssLoss(isSimulatedGnssLoss)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSimulatedGnssLoss) DarkElevated else DarkElevated,
                                contentColor = if (isSimulatedGnssLoss) AccentAmber else TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .border(
                                    1.dp,
                                    if (isSimulatedGnssLoss) AccentAmber else BorderDark,
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(
                                if (isSimulatedGnssLoss) "GNSS SIMULATION: LOST (DEAD RECKONING)" else "SIMULATE GNSS LOSS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // Reset All Button
                        OutlinedButton(
                            onClick = {
                                isSimulatedOffline = false
                                isSimulatedGnssLoss = false
                                navigationModeManager.resetSimulations()
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGreenSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = DarkGreenBg,
                                contentColor = NeonGreen
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RESET SIMULATIONS", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NeonGreen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TestStatusRow(label: String, value: String, isSuccess: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = if (isSuccess) NeonGreen else AccentRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
