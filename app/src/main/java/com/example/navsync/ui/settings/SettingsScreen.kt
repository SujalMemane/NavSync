package com.example.navsync.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.ui.home.NavSyncBottomBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateTab: (String) -> Unit,
    onNavigateToSensorDiagnostics: () -> Unit,
    onNavigateToNavDiagnostics: () -> Unit,
    onNavigateToMapDiagnostics: () -> Unit = {},
    onNavigateToOfflineMaps: () -> Unit = {},
    onNavigateToOfflineTest: () -> Unit = {}
) {
    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Diagnostics", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = darkBg)
            )
        },
        bottomBar = {
            NavSyncBottomBar(currentRoute = "settings", onNavigateTab = onNavigateTab)
        },
        containerColor = darkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("OFFLINE NAVIGATION & MAPS", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsRow(
                        title = "Offline Maps",
                        subtitle = "Download maps for internet-free routing & search",
                        icon = Icons.Default.Download,
                        onClick = onNavigateToOfflineMaps
                    )
                    HorizontalDivider(color = Color(0xFF334155))
                    SettingsRow(
                        title = "Offline Navigation Test",
                        subtitle = "Simulate Internet OFF & GNSS Loss states",
                        icon = Icons.Default.BugReport,
                        onClick = onNavigateToOfflineTest
                    )
                }
            }

            Text("DIAGNOSTICS & SYSTEM", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsRow(
                        title = "Navigation Diagnostics",
                        subtitle = "Real-time state machine, route, maneuver & off-route metrics",
                        icon = Icons.Default.MyLocation,
                        onClick = onNavigateToNavDiagnostics
                    )
                    HorizontalDivider(color = Color(0xFF334155))
                    SettingsRow(
                        title = "Sensor Diagnostics",
                        subtitle = "View 100-200 Hz raw IMU streams & CSV controls",
                        icon = Icons.Default.DeveloperMode,
                        onClick = onNavigateToSensorDiagnostics
                    )
                    HorizontalDivider(color = Color(0xFF334155))
                    SettingsRow(
                        title = "Map Layer Settings & Diagnostics",
                        subtitle = "ISRO Bhuvan WMTS, tile cache & map style metrics",
                        icon = Icons.Default.Map,
                        onClick = onNavigateToMapDiagnostics
                    )
                    HorizontalDivider(color = Color(0xFF334155))
                    SettingsRow(
                        title = "About NavSync",
                        subtitle = "Version 1.0 • TilePrint VIO Ready",
                        icon = Icons.Default.Info,
                        onClick = {}
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = title, tint = Color(0xFF38BDF8))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open", tint = Color(0xFF64748B))
    }
}
