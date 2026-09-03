package com.example.navsync.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.repository.MapStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStyleBottomSheet(
    selectedStyle: MapStyle,
    onSelectStyle: (MapStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val darkCardBg = Color(0xFF0F172A)
    val borderNavy = Color(0xFF334155)
    val accentBlue = Color(0xFF00B0FF)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = darkCardBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(borderNavy)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Map Style",
                        tint = accentBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Map style",
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = textMuted)
                }
            }

            Text(
                text = "Choose your preferred map visualization mode",
                color = textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 2x2 Grid of Map Styles
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.SATELLITE,
                        icon = Icons.Default.Satellite,
                        isSelected = selectedStyle == MapStyle.SATELLITE,
                        onSelect = {
                            onSelectStyle(MapStyle.SATELLITE)
                            onDismiss()
                        }
                    )

                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.STREETS,
                        icon = Icons.Default.Map,
                        isSelected = selectedStyle == MapStyle.STREETS,
                        onSelect = {
                            onSelectStyle(MapStyle.STREETS)
                            onDismiss()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.HYBRID,
                        icon = Icons.Default.Layers,
                        isSelected = selectedStyle == MapStyle.HYBRID,
                        onSelect = {
                            onSelectStyle(MapStyle.HYBRID)
                            onDismiss()
                        }
                    )

                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.TERRAIN,
                        icon = Icons.Default.Terrain,
                        isSelected = selectedStyle == MapStyle.TERRAIN,
                        onSelect = {
                            onSelectStyle(MapStyle.TERRAIN)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MapStyleCard(
    modifier: Modifier = Modifier,
    style: MapStyle,
    icon: ImageVector,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val cardBg = if (isSelected) Color(0xFF1E293B) else Color(0xFF0B132B)
    val borderColor = if (isSelected) Color(0xFF00B0FF) else Color(0xFF334155)
    val textPrimary = Color(0xFFFFFFFF)
    val textMuted = Color(0xFF94A3B8)
    val accentBlue = Color(0xFF00B0FF)

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onSelect)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) accentBlue.copy(alpha = 0.2f) else Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = style.displayName,
                        tint = if (isSelected) accentBlue else textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = style.displayName,
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = style.providerName,
                    color = textMuted,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = accentBlue,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.TopEnd)
                )
            }
        }
    }
}
