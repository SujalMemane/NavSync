package com.example.navsync.ui.offline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.navsync.services.OfflinePopupType
import com.example.navsync.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun OfflineModePopup(
    popupType: OfflinePopupType?,
    onDismiss: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(popupType) {
        if (popupType != null) {
            isVisible = true
            delay(2400L) // Display for 2.4s
            isVisible = false
            delay(250L) // Fade-out animation
            onDismiss()
        } else {
            isVisible = false
        }
    }

    if (popupType != null || isVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            val (iconTint, title, subtitle) = when (popupType) {
                OfflinePopupType.DEAD_RECKONING_STARTED -> Triple(
                    AccentAmber,
                    "GPS Signal Lost",
                    "Dead Reckoning Active • Sensors + ML"
                )
                OfflinePopupType.GNSS_RESTORED -> Triple(
                    TextPrimary,
                    "GPS Restored",
                    "Back to Normal Mode • Satellite Fixed"
                )
                OfflinePopupType.OFFLINE_TRANSITION -> Triple(
                    TextSecondary,
                    "Offline Mode Active",
                    "Switched to Local Offline Vector Maps"
                )
                OfflinePopupType.ONLINE_RESTORED -> Triple(
                    TextPrimary,
                    "Connection Restored",
                    "Online Live Sync Active"
                )
                null -> Triple(
                    TextSecondary,
                    "",
                    ""
                )
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn() + scaleIn(initialScale = 0.90f),
                exit = fadeOut() + scaleOut(targetScale = 0.90f)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth(0.85f)
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(DarkSurface)
                                .border(1.dp, BorderDark, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            when (popupType) {
                                OfflinePopupType.DEAD_RECKONING_STARTED -> {
                                    Icon(Icons.Default.Navigation, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                                }
                                OfflinePopupType.GNSS_RESTORED -> {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                                }
                                OfflinePopupType.OFFLINE_TRANSITION -> {
                                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                                }
                                else -> {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = title,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = subtitle,
                            color = TextSecondary,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
