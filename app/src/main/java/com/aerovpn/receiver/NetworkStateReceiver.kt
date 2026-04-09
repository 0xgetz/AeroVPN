// ====================
// NETWORK STATE RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

/**
 * BroadcastReceiver that monitors network connectivity changes.
 * Used to handle reconnection when network state changes.
 */
class NetworkStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ConnectivityManager.CONNECTIVITY_ACTION,
            "android.net.wifi.WIFI_STATE_CHANGED",
            "android.net.wifi.STATE_CHANGE",
            "android.net.wifi.RSSI_CHANGED" -> {
                handleNetworkChange(context)
            }
        }
    }

    private fun handleNetworkChange(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        
        val isConnected = networkInfo?.isConnected == true
        val networkType = when (networkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> "WiFi"
            ConnectivityManager.TYPE_MOBILE -> "Mobile"
            else -> "Unknown"
        }
        
        Log.d(TAG, "Network changed: isConnected=$isConnected, type=$networkType")
        
        // Notify VPN service to handle reconnection if needed
        val vpnIntent = Intent(context, Class.forName("com.aerovpn.service.AeroVpnService"))
        vpnIntent.action = "com.aerovpn.ACTION_NETWORK_CHANGED"
        vpnIntent.putExtra("is_connected", isConnected)
        vpnIntent.putExtra("network_type", networkType)
        
        try {
            context.startService(vpnIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify VPN service", e)
        }
    }
}
