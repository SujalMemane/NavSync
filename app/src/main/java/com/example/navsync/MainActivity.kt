package com.example.navsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.navsync.ui.SensorViewModel
import com.example.navsync.ui.home.HomeScreen
import com.example.navsync.ui.home.HomeViewModel
import com.example.navsync.ui.offline.DownloadMapScreen
import com.example.navsync.ui.offline.OfflineMapsScreen
import com.example.navsync.ui.offline.OfflineTestScreen
import com.example.navsync.ui.settings.MapDiagnosticsScreen
import com.example.navsync.ui.settings.NavigationDiagnosticsScreen
import com.example.navsync.ui.settings.SensorDiagnosticsScreen
import com.example.navsync.ui.settings.SettingsScreen
import com.example.navsync.ui.theme.NavSyncTheme
import com.example.navsync.ui.trips.TripsScreen
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {

    private val sensorViewModel: SensorViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    private var hasLocationPermission by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        hasLocationPermission = fineGranted || coarseGranted
        Log.d("NAVSYNC_GNSS", "permissionResult fineGranted=$fineGranted coarseGranted=$coarseGranted")
        if (hasLocationPermission) {
            sensorViewModel.startSensors()
            homeViewModel.startSensors()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure osmdroid tile user agent
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        checkAndRequestPermissions()
        if (hasLocationPermission) {
            homeViewModel.startSensors()
        }

        setContent {
            NavSyncTheme {
                var currentRoute by remember { mutableStateOf("dashboard") }

                when (currentRoute) {
                    "dashboard" -> {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigateTab = { route -> currentRoute = route }
                        )
                    }
                    "trips" -> {
                        TripsScreen(onNavigateTab = { route -> currentRoute = route })
                    }
                    "settings" -> {
                        SettingsScreen(
                            onNavigateTab = { route -> currentRoute = route },
                            onNavigateToSensorDiagnostics = { currentRoute = "sensor_diagnostics" },
                            onNavigateToNavDiagnostics = { currentRoute = "navigation_diagnostics" },
                            onNavigateToMapDiagnostics = { currentRoute = "map_diagnostics" },
                            onNavigateToOfflineMaps = { currentRoute = "offline_maps" },
                            onNavigateToOfflineTest = { currentRoute = "offline_test" }
                        )
                    }
                    "offline_maps" -> {
                        OfflineMapsScreen(
                            homeViewModel = homeViewModel,
                            offlineRepository = homeViewModel.offlineMapRepository,
                            onNavigateToDownload = { currentRoute = "download_map" },
                            onBack = { currentRoute = "settings" },
                            onNavigateTab = { route -> currentRoute = route }
                        )
                    }
                    "download_map" -> {
                        DownloadMapScreen(
                            homeViewModel = homeViewModel,
                            offlineRepository = homeViewModel.offlineMapRepository,
                            onBack = { currentRoute = "offline_maps" }
                        )
                    }
                    "offline_test" -> {
                        OfflineTestScreen(
                            navigationModeManager = homeViewModel.navigationModeManager,
                            offlineRepository = homeViewModel.offlineMapRepository,
                            onBack = { currentRoute = "settings" }
                        )
                    }
                    "sensor_diagnostics" -> {
                        SensorDiagnosticsScreen(
                            viewModel = sensorViewModel,
                            hasLocationPermission = hasLocationPermission,
                            onRequestPermission = { checkAndRequestPermissions() },
                            onBack = { currentRoute = "settings" }
                        )
                    }
                    "navigation_diagnostics" -> {
                        NavigationDiagnosticsScreen(
                            homeViewModel = homeViewModel,
                            onBack = { currentRoute = "settings" }
                        )
                    }
                    "map_diagnostics" -> {
                        MapDiagnosticsScreen(
                            homeViewModel = homeViewModel,
                            onBack = { currentRoute = "settings" }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorViewModel.startSensors()
        homeViewModel.startSensors()
    }

    override fun onPause() {
        super.onPause()
        sensorViewModel.stopSensors()
    }

    private fun checkAndRequestPermissions() {
        val finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        hasLocationPermission = (finePermission == PackageManager.PERMISSION_GRANTED) ||
                (coarsePermission == PackageManager.PERMISSION_GRANTED)

        if (!hasLocationPermission) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}