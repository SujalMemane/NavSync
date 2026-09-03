package com.example.navsync.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.navsync.data.map.BhuvanConfig
import com.example.navsync.services.BhuvanMapProvider
import com.example.navsync.services.HybridMapProvider
import com.example.navsync.services.MapProvider
import com.example.navsync.services.SatelliteMapProvider
import com.example.navsync.services.StreetsMapProvider
import com.example.navsync.services.TerrainMapProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import java.io.File

enum class MapInitState {
    MAP_INITIALIZING,
    MAP_LOADING,
    MAP_READY,
    MAP_ERROR
}

enum class MapStyle(
    val id: String,
    val displayName: String,
    val providerName: String,
    val isAvailable: Boolean,
    val description: String
) {
    SATELLITE(
        id = "satellite",
        displayName = "Satellite",
        providerName = "ISRO Bhuvan / Esri Imagery",
        isAvailable = true,
        description = "High-resolution satellite imagery"
    ),
    STREETS(
        id = "streets",
        displayName = "Streets",
        providerName = "OpenStreetMap Base Map",
        isAvailable = true,
        description = "Detailed road networks, streets & landmarks"
    ),
    HYBRID(
        id = "hybrid",
        displayName = "Hybrid",
        providerName = "Satellite + Roads & Labels",
        isAvailable = true,
        description = "Satellite imagery with road overlay & place names"
    ),
    TERRAIN(
        id = "terrain",
        displayName = "Terrain",
        providerName = "OpenTopo Topographic Map",
        isAvailable = true,
        description = "Topographic features, elevation & terrain contours"
    )
}

class MapRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences("navsync_map_prefs", Context.MODE_PRIVATE)

    private val _initState = MutableStateFlow(MapInitState.MAP_INITIALIZING)
    val initState: StateFlow<MapInitState> = _initState.asStateFlow()

    private val _selectedStyle = MutableStateFlow(loadSavedMapStyle())
    val selectedStyle: StateFlow<MapStyle> = _selectedStyle.asStateFlow()

    private val bhuvanMapProvider = BhuvanMapProvider()
    private val satelliteMapProvider = SatelliteMapProvider()
    private val streetsMapProvider = StreetsMapProvider()
    private val hybridMapProvider = HybridMapProvider()
    private val terrainMapProvider = TerrainMapProvider()

    init {
        initializeMapSystem()
    }

    fun initializeMapSystem() {
        _initState.value = MapInitState.MAP_INITIALIZING
        Log.d("NAVSYNC_MAP", "MAP_INIT_START style=${_selectedStyle.value.id}")

        scope.launch {
            try {
                // Configure osmdroid tile caching & user agent
                val config = Configuration.getInstance()
                config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                config.userAgentValue = context.packageName

                // Configure disk cache limits (100 MB max)
                val cacheDir = File(context.cacheDir, "osmdroid_tile_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                config.osmdroidTileCache = cacheDir
                config.tileFileSystemCacheMaxBytes = 100L * 1024L * 1024L // 100MB
                config.tileFileSystemCacheTrimBytes = 80L * 1024L * 1024L  // Trim to 80MB

                _initState.value = MapInitState.MAP_READY
                Log.d("NAVSYNC_MAP", "MAP_INIT_COMPLETE cacheDir=${cacheDir.absolutePath} maxBytes=${config.tileFileSystemCacheMaxBytes}")
            } catch (e: Exception) {
                Log.e("NAVSYNC_MAP", "MAP_ERROR failed to initialize map system: ${e.message}", e)
                _initState.value = MapInitState.MAP_ERROR
            }
        }
    }

    fun setMapStyle(style: MapStyle) {
        if (_selectedStyle.value == style) return
        _selectedStyle.value = style
        saveMapStyle(style)
        Log.d("NAVSYNC_MAP", "MAP_STYLE_CHANGED newStyle=${style.id} provider=${style.providerName}")
    }

    fun getTileSourceForStyle(style: MapStyle = _selectedStyle.value): ITileSource {
        val tileSource = when (style) {
            MapStyle.SATELLITE -> satelliteMapProvider.getTileSource()
            MapStyle.STREETS -> streetsMapProvider.getTileSource()
            MapStyle.HYBRID -> hybridMapProvider.getTileSource()
            MapStyle.TERRAIN -> terrainMapProvider.getTileSource()
        }
        Log.d("NAVSYNC_MAP", "MAP_MODE mode=${style.id} provider=${style.providerName} tileSource=${tileSource.name()}")
        Log.d("NAVSYNC_MAP", "LAYER_ENABLED layer=base_${style.id} status=active")
        return tileSource
    }

    fun getOverlayTileSourceForStyle(style: MapStyle = _selectedStyle.value): ITileSource? {
        return if (style == MapStyle.HYBRID) {
            Log.d("NAVSYNC_MAP", "LAYER_ENABLED layer=hybrid_roads_labels provider=${hybridMapProvider.providerName}")
            hybridMapProvider.getOverlayTileSource()
        } else {
            Log.d("NAVSYNC_MAP", "LAYER_DISABLED layer=hybrid_roads_labels reason=style_not_hybrid")
            null
        }
    }

    fun notifyTileLoading() {
        if (_initState.value != MapInitState.MAP_LOADING && _initState.value == MapInitState.MAP_READY) {
            _initState.value = MapInitState.MAP_LOADING
            Log.d("NAVSYNC_MAP", "MAP_LOADING status=tiles_requested")
        }
    }

    fun notifyMapReady() {
        _initState.value = MapInitState.MAP_READY
        Log.d("NAVSYNC_MAP", "MAP_READY status=map_interactive")
    }

    fun notifyMapError(error: String) {
        _initState.value = MapInitState.MAP_ERROR
        Log.e("NAVSYNC_MAP", "MAP_ERROR msg=$error")
    }

    private fun loadSavedMapStyle(): MapStyle {
        val savedId = prefs.getString("selected_map_style", MapStyle.SATELLITE.id) ?: MapStyle.SATELLITE.id
        return MapStyle.entries.find { it.id == savedId } ?: MapStyle.SATELLITE
    }

    private fun saveMapStyle(style: MapStyle) {
        prefs.edit().putString("selected_map_style", style.id).apply()
    }
}
