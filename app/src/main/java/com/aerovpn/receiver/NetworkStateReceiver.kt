// ====================
// NETWORK STATE RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Fix #11: Replaced deprecated CONNECTIVITY_CHANGE BroadcastReceiver with
 * ConnectivityManager.registerNetworkCallback() (API 21+).
 *
 * CONNECTIVITY_ACTION broadcast is deprecated since API 28 and unreliable in
 * background on API 24+. Use NetworkCallback for reliable network monitoring.
 *
 * Usage:
 *   val receiver = NetworkStateReceiver(context)
 *   receiver.register()   // start monitoring (e.g. in onStart / Service.onCreate)
 *   receiver.unregister() // stop monitoring (e.g. in onStop / Service.onDestroy)
 */
class NetworkStateReceiver(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStateReceiver"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            handleNetworkChange(isConnected = true, networkType = getNetworkType(network))
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            handleNetworkChange(isConnected = false, networkType = "None")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val type = when {
                isWifi -> "WiFi"
                isMobile -> "Mobile"
                else -> "Other"
            }
            Log.d(TAG, "Network capabilities changed: type=$type")
        }
    }

    private var isRegistered = false

    /**
     * Start monitoring network changes. Call from Service.onCreate() or Activity.onStart().
     */
    fun register() {
        if (isRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        isRegistered = true
        Log.d(TAG, "NetworkCallback registered")
    }

    /**
     * Stop monitoring network changes. Call from Service.onDestroy() or Activity.onStop().
     */
    fun unregister() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "NetworkCallback was not registered or already unregistered", e)
        }
        isRegistered = false
        Log.d(TAG, "NetworkCallback unregistered")
    }

    private fun getNetworkType(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    private fun handleNetworkChange(isConnected: Boolean, networkType: String) {
        Log.d(TAG, "Network changed: isConnected=$isConnected, type=$networkType")

        // Notify VPN service to handle reconnection if needed
        val vpnIntent = android.content.Intent(
            context,
            Class.forName("com.aerovpn.service.AeroVpnService")
        ).apply {
            action = "com.aerovpn.ACTION_NETWORK_CHANGED"
            putExtra("is_connected", isConnected)
            putExtra("network_type", networkType)
        }

        try {
            context.startService(vpnIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify VPN service", e)
        }
    }
}
