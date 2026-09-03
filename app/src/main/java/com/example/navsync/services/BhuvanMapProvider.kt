package com.example.navsync.services

import android.util.Log
import com.example.navsync.data.map.BhuvanConfig
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Isolated Bhuvan Map Provider for osmdroid.
 * Encapsulates Bhuvan WMS/WMTS URL construction and fallback tile generation.
 */
class BhuvanMapProvider(
    val layerName: String = BhuvanConfig.DEFAULT_LAYER_NAME,
    val isWmts: Boolean = true
) : OnlineTileSourceBase(
    "BhuvanSatellite",
    0,
    19,
    256,
    ".png",
    arrayOf(if (isWmts) BhuvanConfig.WMTS_BASE_URL else BhuvanConfig.WMS_BASE_URL)
), MapProvider {

    override val providerName: String = "ISRO Bhuvan Vector / WMTS"
    override val attribution: String = BhuvanConfig.ATTRIBUTION

    override fun getTileSource(): ITileSource = this

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        val tokenParam = if (BhuvanConfig.hasApiToken()) "&token=${BhuvanConfig.getApiToken()}" else ""

        val url = if (isWmts) {
            "${baseUrl}?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layerName&STYLE=default&TILEMATRIXSET=GoogleMapsCompatible&TILEMATRIX=$zoom&TILEROW=$y&TILECOL=$x&FORMAT=image/png$tokenParam"
        } else {
            "${baseUrl}?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&LAYERS=$layerName&STYLES=&FORMAT=image/png&TRANSPARENT=true&SRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX=${calculateBBox(zoom, x, y)}$tokenParam"
        }

        Log.d("NAVSYNC_MAP", "provider=BhuvanMapProvider url=$url layer=$layerName zoom=$zoom x=$x y=$y")
        return url
    }

    private fun calculateBBox(zoom: Int, x: Int, y: Int): String {
        val tileSize = 256
        val initialResolution = 2 * Math.PI * 6378137 / tileSize
        val originShift = 2 * Math.PI * 6378137 / 2.0

        val res = initialResolution / Math.pow(2.0, zoom.toDouble())
        val minX = x * tileSize * res - originShift
        val maxY = originShift - y * tileSize * res
        val maxX = (x + 1) * tileSize * res - originShift
        val minY = originShift - (y + 1) * tileSize * res

        return "$minX,$minY,$maxX,$maxY"
    }
}
