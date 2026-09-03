package com.example.navsync.data.map

/**
 * Bhuvan Configuration Abstraction.
 * Encapsulates official ISRO / NRSC Bhuvan endpoints, layer names, and runtime tokens.
 */
object BhuvanConfig {
    const val WMS_BASE_URL = "https://bhuvan-vec1.nrsc.gov.in/bhuvan/wms"
    const val WMTS_BASE_URL = "https://bhuvan-vec1.nrsc.gov.in/bhuvan/gwc/service/wmts"
    const val DEFAULT_LAYER_NAME = "bhuvan_vector"
    const val DEFAULT_FORMAT = "image/png"
    const val DEFAULT_SRS = "EPSG:3857"
    const val ATTRIBUTION = "ISRO Bhuvan"

    // Runtime token storage (optional, loaded from runtime config / secure storage)
    private var apiToken: String = ""

    fun setApiToken(token: String) {
        apiToken = token
    }

    fun getApiToken(): String = apiToken

    fun hasApiToken(): Boolean = apiToken.isNotEmpty()
}
