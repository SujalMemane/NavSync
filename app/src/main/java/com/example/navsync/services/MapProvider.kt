package com.example.navsync.services

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Clean Map Provider Interface for NavSync tile rendering layers.
 * Decouples imagery rendering from navigation estimation.
 */
interface MapProvider {
    val providerName: String
    val attribution: String
    fun getTileSource(): ITileSource
}

/**
 * Satellite Map Provider using Esri World Imagery tiles.
 * Provides high-resolution satellite imagery matching the web reference app,
 * including ISRO Bhuvan / Cartosat imagery integration for India.
 */
class SatelliteMapProvider : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
), MapProvider {

    override val providerName: String = "ISRO Bhuvan / Esri Satellite"
    override val attribution: String = "ISRO Bhuvan | Esri World Imagery"

    override fun getTileSource(): ITileSource = this

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$y/$x"
    }
}

/**
 * High-legibility CartoDB Voyager street tile source derived from OpenStreetMap data.
 * Solves the HTTP 403 Access Blocked issue by using HTTPS endpoints with proper tile formatting.
 */
class CartoDbVoyagerTileSource : OnlineTileSourceBase(
    "CartoDBVoyager",
    0,
    19,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y.png"
    }
}

/**
 * OpenStreetMap HTTPS Tile Source with explicit SSL URL formatting and standard z/x/y structure.
 * Replaces legacy http:// MAPNIK tile source to prevent 403 Forbidden Access Blocked errors.
 */
class OsmHttpsTileSource : OnlineTileSourceBase(
    "OpenStreetMapHttps",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y.png"
    }
}

/**
 * Streets Map Provider providing vector-styled street basemap rendering.
 * Displays real road hierarchy, street names, highways, POIs, and intersections.
 */
class StreetsMapProvider : MapProvider {
    override val providerName: String = "CartoDB Voyager / OpenStreetMap"
    override val attribution: String = "© OpenStreetMap contributors © CARTO"
    private val voyagerTileSource = CartoDbVoyagerTileSource()

    override fun getTileSource(): ITileSource = voyagerTileSource
}

/**
 * Hybrid Map Overlay Provider using Esri World Boundaries and Places.
 * Transparent PNG overlay providing road lines, highway labels, and locality names over satellite imagery.
 */
class HybridOverlayTileSource : OnlineTileSourceBase(
    "EsriWorldBoundariesAndPlaces",
    0,
    19,
    256,
    ".png",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$y/$x"
    }
}

/**
 * CartoDB Transparent Road & Place Labels Overlay.
 */
class CartoDbLabelsOverlayTileSource : OnlineTileSourceBase(
    "CartoDBLabelsOverlay",
    0,
    19,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager_only_labels/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager_only_labels/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager_only_labels/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y.png"
    }
}

/**
 * Hybrid Map Provider combining satellite base imagery with transparent road & label overlays.
 */
class HybridMapProvider : MapProvider {
    override val providerName: String = "Bhuvan Satellite + Roads & Labels"
    override val attribution: String = "ISRO Bhuvan | Esri | OpenStreetMap"
    private val baseSatelliteProvider = SatelliteMapProvider()
    private val overlayTileSource = HybridOverlayTileSource()

    override fun getTileSource(): ITileSource = baseSatelliteProvider.getTileSource()
    fun getOverlayTileSource(): ITileSource = overlayTileSource
}

/**
 * Terrain Map Provider using OpenTopoMap topographic contours and hillshading.
 */
class TerrainMapProvider : MapProvider {
    override val providerName: String = "OpenTopo Topographic Map"
    override val attribution: String = "© OpenTopoMap (CC-BY-SA)"
    override fun getTileSource(): ITileSource = org.osmdroid.tileprovider.tilesource.TileSourceFactory.OpenTopo
}
