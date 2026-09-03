package com.example.navsync.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.navsync.ui.SensorDebugScreen
import com.example.navsync.ui.SensorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDiagnosticsScreen(
    viewModel: SensorViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensor Diagnostics", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            SensorDebugScreen(
                viewModel = viewModel,
                hasLocationPermission = hasLocationPermission,
                onRequestPermission = onRequestPermission
            )
        }
    }
}
