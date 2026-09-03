package com.example.navsync.services

import android.util.Log

object OfflineDataPolicy {

    private const val TAG = "NAVSYNC_OFFLINE_VIOLATION"

    fun assertNetworkAllowed(requestTag: String, navigationModeManager: NavigationModeManager) {
        if (navigationModeManager.connectivityState.value == ConnectivityState.OFFLINE) {
            val violationMsg = "OFFLINE VIOLATION DETECTED! Network request attempted while ConnectivityState = OFFLINE (tag=$requestTag)"
            Log.e(TAG, violationMsg, Exception("Network Guard Stack Trace"))
        }
    }
}
