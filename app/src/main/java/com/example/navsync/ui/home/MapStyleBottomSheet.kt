package com.example.navsync.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.R
import com.example.navsync.repository.MapStyle
import com.example.navsync.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStyleBottomSheet(
    selectedStyle: MapStyle,
    onSelectStyle: (MapStyle) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkElevated,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BorderDark)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DarkSurface)
                            .border(1.dp, BorderDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Map Style",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Map Style",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }

            Text(
                text = "Choose your preferred map visualization layer",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            // 2x2 Grid of Map Styles with Custom Photo Background Previews
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.HYBRID,
                        previewResId = R.drawable.map_preview_hybrid,
                        isSelected = selectedStyle == MapStyle.HYBRID,
                        onSelect = {
                            onSelectStyle(MapStyle.HYBRID)
                            onDismiss()
                        }
                    )

                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.STREETS,
                        previewResId = R.drawable.map_preview_streets,
                        isSelected = selectedStyle == MapStyle.STREETS,
                        onSelect = {
                            onSelectStyle(MapStyle.STREETS)
                            onDismiss()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.SATELLITE,
                        previewResId = R.drawable.map_preview_satellite,
                        isSelected = selectedStyle == MapStyle.SATELLITE,
                        onSelect = {
                            onSelectStyle(MapStyle.SATELLITE)
                            onDismiss()
                        }
                    )

                    MapStyleCard(
                        modifier = Modifier.weight(1f),
                        style = MapStyle.TERRAIN,
                        previewResId = R.drawable.map_preview_terrain,
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
    previewResId: Int,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) ElectricBlue else BorderDark
    val borderWidth = if (isSelected) 1.5.dp else 1.dp

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = modifier
            .fillMaxWidth()
            .height(125.dp)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Custom preview photo as full card background
            Image(
                painter = painterResource(id = previewResId),
                contentDescription = style.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 2. Gradient scrim for high readability over light/dark satellite textures
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.20f),
                                Color.Black.copy(alpha = 0.50f),
                                Color.Black.copy(alpha = 0.90f)
                            )
                        )
                    )
            )

            // 3. Selection badge on top-right
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ElectricBlue)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // 4. Style Title & Provider Name positioned neatly at bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = style.displayName,
                    color = if (isSelected) ElectricBlue else TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = style.providerName,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}
