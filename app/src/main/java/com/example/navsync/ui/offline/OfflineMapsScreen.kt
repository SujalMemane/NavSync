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

    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF00B0FF)
    val accentNavy = Color(0xFF2563EB)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)

    val totalStorageBytes = downloadedRegions.sumOf { it.sizeBytes }
    val totalStorageMb = String.format(Locale.US, "%.1f MB", totalStorageBytes / (1024.0 * 1024.0))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Maps", color = textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = textPrimary)
                    }
                },
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
                .padding(16.dp)
        ) {
            // Header Description Card
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, borderNavy, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, contentDescription = null, tint = accentBlue, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("OFFLINE MAPS", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Download maps to navigate without internet", color = textMuted, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onNavigateToDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = accentNavy),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download New Area", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                Text("DOWNLOADED MAPS", color = textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Storage used: $totalStorageMb", color = accentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (downloadedRegions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .border(1.dp, borderNavy, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Map, contentDescription = null, tint = textMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No offline maps", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Download an area before using offline navigation.", color = textMuted, fontSize = 12.sp)
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
    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)
    val accentBlue = Color(0xFF00B0FF)

    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    val dateStr = dateFormat.format(Date(region.downloadTimeMs))
    val sizeMb = String.format(Locale.US, "%.1f MB", region.sizeBytes / (1024.0 * 1024.0))

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderNavy, RoundedCornerShape(14.dp))
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
                    Text(region.name, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Downloaded • $sizeMb • $dateStr",
                    color = textMuted,
                    fontSize = 12.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onViewMap,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, accentBlue),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = accentBlue, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View Map", color = accentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Map", tint = Color(0xFFEF4444))
                }
            }
        }
    }
}
