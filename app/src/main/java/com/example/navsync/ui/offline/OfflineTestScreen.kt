package com.example.navsync.ui.offline

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.services.ConnectivityState
import com.example.navsync.services.LocationState
import com.example.navsync.services.NavigationModeManager

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

    var isSimulatedOffline by remember { mutableStateOf(false) }
    var isSimulatedGnssLoss by remember { mutableStateOf(false) }

    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF00B0FF)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)

    val hasMapDownloaded = downloadedRegions.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Navigation Test", color = textPrimary, fontWeight = FontWeight.Bold) },
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
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderNavy, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = accentBlue, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("OFFLINE NAVIGATION DIAGNOSTICS", color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

            // Developer Simulation Controls Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderNavy, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("SIMULATION CONTROLS (DEBUG ONLY)", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        // Internet OFF Toggle Button
                        Button(
                            onClick = {
                                isSimulatedOffline = !isSimulatedOffline
                                navigationModeManager.setSimulatedOffline(isSimulatedOffline)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSimulatedOffline) Color(0xFFEF4444) else Color(0xFF1E293B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                if (isSimulatedOffline) "INTERNET SIMULATION: OFF (OFFLINE MODE)" else "SIMULATE INTERNET OFF",
                                color = Color.White,
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
                                containerColor = if (isSimulatedGnssLoss) Color(0xFFF59E0B) else Color(0xFF1E293B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                if (isSimulatedGnssLoss) "GNSS SIMULATION: LOST (DEAD RECKONING)" else "SIMULATE GNSS LOSS",
                                color = Color.White,
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = textPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RESET SIMULATIONS", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        Text(
            value,
            color = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
