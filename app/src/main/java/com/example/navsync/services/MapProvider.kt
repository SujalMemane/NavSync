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
 * Google Maps Roadmap Tile Source (lyrs=m).
 * Displays full Google Maps road networks, street names, highway shields,
 * landmark names, business POIs, building footprints, parks, and transit.
 */
class GoogleRoadmapTileSource : OnlineTileSourceBase(
    "GoogleRoadmap",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=m&x=",
        "https://mt1.google.com/vt/lyrs=m&x=",
        "https://mt2.google.com/vt/lyrs=m&x=",
        "https://mt3.google.com/vt/lyrs=m&x="
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}${x}&y=${y}&z=${zoom}"
    }
}

/**
 * Google Maps Hybrid Satellite Tile Source (lyrs=y).
 * Combines high-resolution satellite imagery with Google Maps road network,
 * street names, highway shields, and landmark names.
 */
class GoogleHybridTileSource : OnlineTileSourceBase(
    "GoogleHybrid",
    0,
    20,
    256,
    ".jpg",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=y&x=",
        "https://mt1.google.com/vt/lyrs=y&x=",
        "https://mt2.google.com/vt/lyrs=y&x=",
        "https://mt3.google.com/vt/lyrs=y&x="
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}${x}&y=${y}&z=${zoom}"
    }
}

/**
 * Google Maps Transparent Roads & Labels Overlay (lyrs=h).
 * Provides transparent road outlines, street names, highway labels, and landmark names
 * that can be rendered on top of base satellite imagery (e.g. ISRO Bhuvan or Esri).
 */
class GoogleLabelsOverlayTileSource : OnlineTileSourceBase(
    "GoogleLabelsOverlay",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=h&x=",
        "https://mt1.google.com/vt/lyrs=h&x=",
        "https://mt2.google.com/vt/lyrs=h&x=",
        "https://mt3.google.com/vt/lyrs=h&x="
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}${x}&y=${y}&z=${zoom}"
    }
}

/**
 * Google Maps Terrain Tile Source (lyrs=p).
 * Displays physical terrain, elevation relief, roads, and landmark labels.
 */
class GoogleTerrainTileSource : OnlineTileSourceBase(
    "GoogleTerrain",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=p&x=",
        "https://mt1.google.com/vt/lyrs=p&x=",
        "https://mt2.google.com/vt/lyrs=p&x=",
        "https://mt3.google.com/vt/lyrs=p&x="
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}${x}&y=${y}&z=${zoom}"
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
 * Streets Map Provider providing full Google Maps Roadmap rendering.
 * Displays real road hierarchy, street names, highways, POIs, landmarks, and intersections.
 */
class StreetsMapProvider : MapProvider {
    override val providerName: String = "Google Maps Roadmap (Roads, Streets & Landmarks)"
    override val attribution: String = "© Google Maps"
    private val googleRoadmapTileSource = GoogleRoadmapTileSource()

    override fun getTileSource(): ITileSource = googleRoadmapTileSource
}

/**
 * Hybrid Map Overlay Provider using Google Transparent Roads and Landmark Labels.
 */
class HybridOverlayTileSource : OnlineTileSourceBase(
    "GoogleHybridRoadsAndLabels",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=h&x=",
        "https://mt1.google.com/vt/lyrs=h&x=",
        "https://mt2.google.com/vt/lyrs=h&x=",
        "https://mt3.google.com/vt/lyrs=h&x="
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}${x}&y=${y}&z=${zoom}"
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
 * Hybrid Map Provider combining satellite imagery with Google Maps road & landmark overlays.
 * Uses GoogleHybridTileSource (lyrs=y) as a single integrated raster tile source,
 * avoiding dual-layer reloading and ensuring rock-solid static rendering.
 */
class HybridMapProvider : MapProvider {
    override val providerName: String = "Google Hybrid (Satellite + Roads & Landmark Labels)"
    override val attribution: String = "© Google Maps | ISRO Bhuvan"
    private val googleHybridTileSource = GoogleHybridTileSource()

    override fun getTileSource(): ITileSource = googleHybridTileSource
    fun getOverlayTileSource(): ITileSource? = null
}

/**
 * Terrain Map Provider using Google Terrain with contours, roads, and landmark labels.
 */
class TerrainMapProvider : MapProvider {
    override val providerName: String = "Google Terrain (Elevation Contours & Roads)"
    override val attribution: String = "© Google Maps"
    private val googleTerrainTileSource = GoogleTerrainTileSource()
    override fun getTileSource(): ITileSource = googleTerrainTileSource
}
