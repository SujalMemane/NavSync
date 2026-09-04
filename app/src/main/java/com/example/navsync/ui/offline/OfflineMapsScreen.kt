package com.example.navsync.ui.offline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.data.db.OfflineRegionRecord
import com.example.navsync.repository.OfflineMapRepository
import com.example.navsync.ui.home.HomeViewModel
import com.example.navsync.ui.home.NavSyncBottomBar
import com.example.navsync.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    homeViewModel: HomeViewModel,
    offlineRepository: OfflineMapRepository,
    onNavigateToDownload: () -> Unit,
    onBack: () -> Unit,
    onNavigateTab: (String) -> Unit
) {
    val downloadedRegions by offlineRepository.downloadedRegions.collectAsState()
    val scope = rememberCoroutineScope()

    val totalStorageBytes = downloadedRegions.sumOf { it.sizeBytes }
    val totalStorageMb = String.format(Locale.US, "%.1f MB", totalStorageBytes / (1024.0 * 1024.0))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Maps", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
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
                .padding(16.dp)
        ) {
            // Header Description Card
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(DarkGreenBg)
                                .border(1.dp, BorderGreenSubtle, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("OFFLINE MAPS", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Download maps to navigate without internet", color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onNavigateToDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = TextDark),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = TextDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download New Area", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Storage Summary Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("DOWNLOADED MAPS", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Storage used: $totalStorageMb", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (downloadedRegions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurface)
                        .border(1.dp, BorderDark, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(DarkGreenBg)
                                .border(1.dp, BorderGreenSubtle, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("No offline maps", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Download an area before using offline navigation.", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(downloadedRegions) { region ->
                        DownloadedRegionCard(
                            region = region,
                            onViewMap = {
                                homeViewModel.focusOnRegionBounds(region.minLat, region.minLon, region.maxLat, region.maxLon)
                                onNavigateTab("dashboard")
                            },
                            onDelete = {
                                scope.launch {
                                    offlineRepository.deleteRegion(region.regionId)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadedRegionCard(
    region: OfflineRegionRecord,
    onViewMap: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    val dateStr = dateFormat.format(Date(region.downloadTimeMs))
    val sizeMb = String.format(Locale.US, "%.1f MB", region.sizeBytes / (1024.0 * 1024.0))

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(region.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = NeonGreen, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Downloaded • $sizeMb • $dateStr",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onViewMap,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGreenSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DarkGreenBg,
                        contentColor = NeonGreen
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View Map", color = NeonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Map", tint = AccentRed)
                }
            }
        }
    }
}
