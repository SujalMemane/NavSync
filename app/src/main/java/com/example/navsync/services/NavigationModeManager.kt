package com.example.navsync.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.navsync.sensor.GnssStatusState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectivityState {
    ONLINE,
    OFFLINE
}

enum class LocationState {
    GNSS_AVAILABLE,
    GNSS_DEGRADED,
    GNSS_UNAVAILABLE
}

enum class NavMode {
    ONLINE_GNSS,
    OFFLINE_GNSS,
    DEAD_RECKONING,
    RECOVERY
}

enum class OfflinePopupType {
    OFFLINE_TRANSITION,
    ONLINE_RESTORED
}

class NavigationModeManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _connectivityState = MutableStateFlow(ConnectivityState.ONLINE)
    val connectivityState: StateFlow<ConnectivityState> = _connectivityState.asStateFlow()

    private val _locationState = MutableStateFlow(LocationState.GNSS_AVAILABLE)
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private val _navigationMode = MutableStateFlow(NavMode.ONLINE_GNSS)
    val navigationMode: StateFlow<NavMode> = _navigationMode.asStateFlow()

    private val _popupEvent = MutableSharedFlow<OfflinePopupType>(extraBufferCapacity = 1)
    val popupEvent: SharedFlow<OfflinePopupType> = _popupEvent.asSharedFlow()

    // Debug Simulation Toggles
    private var simulatedOffline: Boolean = false
    private var simulatedGnssLoss: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!simulatedOffline) {
                updateConnectivityState(ConnectivityState.ONLINE)
            }
        }

        override fun onLost(network: Network) {
            updateConnectivityState(ConnectivityState.OFFLINE)
        }
    }

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)

            val activeNet = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNet)
            val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            updateConnectivityState(if (isOnline) ConnectivityState.ONLINE else ConnectivityState.OFFLINE, emitPopup = false)
        } catch (e: Exception) {
            Log.e("NAVSYNC_MODE", "Error registering network callback: ${e.message}")
        }
    }

    fun updateGnssStatus(status: GnssStatusState) {
        val newLocState = when {
            simulatedGnssLoss || status == GnssStatusState.GNSS_LOST -> LocationState.GNSS_UNAVAILABLE
            status == GnssStatusState.GNSS_DEGRADED -> LocationState.GNSS_DEGRADED
            else -> LocationState.GNSS_AVAILABLE
        }

        if (_locationState.value != newLocState) {
            _locationState.value = newLocState
            recalculateNavigationMode()
        }
    }

    fun setSimulatedOffline(offline: Boolean) {
        simulatedOffline = offline
        updateConnectivityState(if (offline) ConnectivityState.OFFLINE else getCurrentRealConnectivity())
        Log.d("NAVSYNC_MODE", "simulatedOffline=$offline newConnectivity=${_connectivityState.value}")
    }

    fun setSimulatedGnssLoss(gnssLoss: Boolean) {
        simulatedGnssLoss = gnssLoss
        updateGnssStatus(if (gnssLoss) GnssStatusState.GNSS_LOST else GnssStatusState.GNSS_ACTIVE)
        Log.d("NAVSYNC_MODE", "simulatedGnssLoss=$gnssLoss newLocationState=${_locationState.value}")
    }

    fun resetSimulations() {
        simulatedOffline = false
        simulatedGnssLoss = false
        updateConnectivityState(getCurrentRealConnectivity())
        updateGnssStatus(GnssStatusState.GNSS_ACTIVE)
        Log.d("NAVSYNC_MODE", "Simulations reset. mode=${_navigationMode.value}")
    }

    private fun updateConnectivityState(newState: ConnectivityState, emitPopup: Boolean = true) {
        val effectiveState = if (simulatedOffline) ConnectivityState.OFFLINE else newState
        if (_connectivityState.value != effectiveState) {
            val oldState = _connectivityState.value
            _connectivityState.value = effectiveState
            recalculateNavigationMode()

            if (emitPopup) {
                if (oldState == ConnectivityState.ONLINE && effectiveState == ConnectivityState.OFFLINE) {
                    _popupEvent.tryEmit(OfflinePopupType.OFFLINE_TRANSITION)
                    Log.d("NAVSYNC_MODE", "Emitted OFFLINE_TRANSITION popup event")
                } else if (oldState == ConnectivityState.OFFLINE && effectiveState == ConnectivityState.ONLINE) {
                    _popupEvent.tryEmit(OfflinePopupType.ONLINE_RESTORED)
                    Log.d("NAVSYNC_MODE", "Emitted ONLINE_RESTORED popup event")
                }
            }
        }
    }

    private fun getCurrentRealConnectivity(): ConnectivityState {
        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        return if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            ConnectivityState.ONLINE
        } else {
            ConnectivityState.OFFLINE
        }
    }

    private fun recalculateNavigationMode() {
        val conn = _connectivityState.value
        val loc = _locationState.value

        val newMode = when {
            loc == LocationState.GNSS_UNAVAILABLE -> NavMode.DEAD_RECKONING
            conn == ConnectivityState.OFFLINE -> NavMode.OFFLINE_GNSS
            else -> NavMode.ONLINE_GNSS
        }

        if (_navigationMode.value != newMode) {
            Log.d("NAVSYNC_MODE", "NAVIGATION_MODE_TRANSITION oldMode=${_navigationMode.value} newMode=$newMode connectivity=$conn locationState=$loc")
            _navigationMode.value = newMode
        }
    }
}
