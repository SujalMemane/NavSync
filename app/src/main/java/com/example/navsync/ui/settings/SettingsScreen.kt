package com.example.navsync.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.ui.home.NavSyncBottomBar
import com.example.navsync.ui.theme.*

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Diagnostics", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        bottomBar = {
            NavSyncBottomBar(currentRoute = "settings", onNavigateTab = onNavigateTab)
        },
        containerColor = DarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "OFFLINE NAVIGATION & MAPS",
                color = NeonGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            ) {
                Column {
                    SettingsRow(
                        title = "Offline Maps",
                        subtitle = "Download maps for internet-free routing & search",
                        icon = Icons.Default.Download,
                        onClick = onNavigateToOfflineMaps
                    )
                    HorizontalDivider(color = BorderDark)
                    SettingsRow(
                        title = "Offline Navigation Test",
                        subtitle = "Simulate Internet OFF & GNSS Loss states",
                        icon = Icons.Default.BugReport,
                        onClick = onNavigateToOfflineTest
                    )
                }
            }

            Text(
                text = "DIAGNOSTICS & SYSTEM",
                color = NeonGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            ) {
                Column {
                    SettingsRow(
                        title = "Navigation Diagnostics",
                        subtitle = "Real-time state machine, route, maneuver & off-route metrics",
                        icon = Icons.Default.MyLocation,
                        onClick = onNavigateToNavDiagnostics
                    )
                    HorizontalDivider(color = BorderDark)
                    SettingsRow(
                        title = "Sensor Diagnostics",
                        subtitle = "View 100-200 Hz raw IMU streams & CSV controls",
                        icon = Icons.Default.DeveloperMode,
                        onClick = onNavigateToSensorDiagnostics
                    )
                    HorizontalDivider(color = BorderDark)
                    SettingsRow(
                        title = "Map Layer Settings & Diagnostics",
                        subtitle = "ISRO Bhuvan WMTS, tile cache & map style metrics",
                        icon = Icons.Default.Map,
                        onClick = onNavigateToMapDiagnostics
                    )
                    HorizontalDivider(color = BorderDark)
                    SettingsRow(
                        title = "About NavSync",
                        subtitle = "Version 1.0 • TilePrint VIO & Dead Reckoning Ready",
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
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(DarkGreenBg)
                    .border(1.dp, BorderGreenSubtle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = NeonGreen, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open", tint = TextMuted)
    }
}
