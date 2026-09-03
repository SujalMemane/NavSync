package com.example.navsync.ui.offline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.services.OfflinePopupType
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
            delay(2200L) // Display for 2.2s
            isVisible = false
            delay(300L) // Allow fade-out animation
            onDismiss()
        }
    }

    val cardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF00B0FF)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.85f)
                    .border(1.5.dp, if (popupType == OfflinePopupType.OFFLINE_TRANSITION) accentBlue else Color(0xFF10B981), RoundedCornerShape(20.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (popupType == OfflinePopupType.OFFLINE_TRANSITION) Color(0xFF1E293B) else Color(0xFF064E3B)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (popupType == OfflinePopupType.OFFLINE_TRANSITION) {
                            Icon(Icons.Default.WifiOff, contentDescription = null, tint = accentBlue, modifier = Modifier.size(28.dp))
                        } else {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (popupType == OfflinePopupType.OFFLINE_TRANSITION) "No Internet" else "Internet Restored",
                        color = textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (popupType == OfflinePopupType.OFFLINE_TRANSITION) "Switching to Offline Mode" else "Back Online",
                        color = textMuted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
