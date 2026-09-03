package com.example.navsync.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.data.db.NavSyncDbHelper
import com.example.navsync.data.db.TripSessionRecord
import com.example.navsync.ui.home.NavSyncBottomBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(onNavigateTab: (String) -> Unit) {
    val context = LocalContext.current
    val dbHelper = remember { NavSyncDbHelper(context) }
    var tripsList by remember { mutableStateOf<List<TripSessionRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        tripsList = dbHelper.getAllTripSessions()
    }

    val darkBg = Color(0xFF030712)
    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF38BDF8)
    val textMuted = Color(0xFF94A3B8)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip History", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = darkBg)
            )
        },
        bottomBar = {
            NavSyncBottomBar(currentRoute = "trips", onNavigateTab = onNavigateTab)
        },
        containerColor = darkBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "RECORDED NAVIGATION SESSIONS",
                    color = textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            if (tripsList.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                            .border(1.dp, borderNavy, RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, tint = textMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No trips yet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Start a navigation session from Dashboard to record real GNSS trip statistics.",
                                color = textMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(tripsList) { trip ->
                    TripCard(trip = trip, cardBg = cardBg, borderColor = borderNavy, accentColor = accentBlue, textMuted = textMuted)
                }
            }
        }
    }
}

@Composable
fun TripCard(
    trip: TripSessionRecord,
    cardBg: Color,
    borderColor: Color,
    accentColor: Color,
    textMuted: Color
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault()) }
    val dateStr = dateFormat.format(Date(trip.startTimeMs))

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(trip.destinationName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(trip.status, color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("From: ${trip.originName}", color = textMuted, fontSize = 12.sp)
            Text(dateStr, color = textMuted, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = borderColor)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("DISTANCE", color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "%.2f km", trip.distanceKm),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column {
                    Text("DURATION", color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    val mins = trip.durationSeconds / 60
                    val secs = trip.durationSeconds % 60
                    Text(
                        "${mins}m ${secs}s",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column {
                    Text("DATA ENGINE", color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("GNSS Real", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
