package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * NetworkStateReceiver — proper dual-mode network monitor.
 *
 * CRITICAL FIX (C2): The previous implementation was NOT a BroadcastReceiver subclass,
 * so the manifest declaration was non-functional — Android never delivered any intents
 * to it. This class now extends BroadcastReceiver so it can be registered both:
 *
 *   (a) Programmatically via registerReceiver() in AeroVpnService — for SCREEN_ON/OFF
 *       and other intents that only work with runtime-registered receivers.
 *   (b) Via ConnectivityManager.NetworkCallback — for reliable API-21+ network monitoring
 *       (CONNECTIVITY_CHANGE is deprecated and unreliable from API 24+).
 *
 * The manifest entry for this receiver has been removed (see AndroidManifest.xml fix)
 * because CONNECTIVITY_CHANGE cannot be reliably received by manifest receivers on API 24+.
 *
 * Usage in AeroVpnService:
 *   private val networkStateReceiver = NetworkStateReceiver()
 *
 *   override fun onCreate() {
 *       networkStateReceiver.register(this)
 *   }
 *   override fun onDestroy() {
 *       networkStateReceiver.unregister(this)
 *   }
 */
class NetworkStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkStateReceiver"

        /** Build the IntentFilter for programmatic registration. */
        fun buildIntentFilter(): IntentFilter = IntentFilter().apply {
            // These actions ONLY work with programmatically-registered receivers
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            // Power connected/disconnected work both ways but we register here for completeness
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver — handles screen / power intents (programmatic only)
    // -------------------------------------------------------------------------

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen ON — checking VPN connectivity")
                notifyVpnService(context, ACTION_SCREEN_STATE_CHANGED, extras = mapOf("screen_on" to true))
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen OFF")
                notifyVpnService(context, ACTION_SCREEN_STATE_CHANGED, extras = mapOf("screen_on" to false))
            }
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d(TAG, "Power connected")
                notifyVpnService(context, ACTION_POWER_STATE_CHANGED, extras = mapOf("power_connected" to true))
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d(TAG, "Power disconnected")
                notifyVpnService(context, ACTION_POWER_STATE_CHANGED, extras = mapOf("power_connected" to false))
            }
        }
    }

    // -------------------------------------------------------------------------
    // ConnectivityManager.NetworkCallback — reliable API-21+ network monitoring
    // -------------------------------------------------------------------------

    private var connectivityManager: ConnectivityManager? = null
    private var isNetworkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            registeredContext?.let { ctx ->
                handleNetworkChange(ctx, isConnected = true, networkType = getNetworkType(network))
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            registeredContext?.let { ctx ->
                handleNetworkChange(ctx, isConnected = false, networkType = "None")
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
            Log.d(TAG, "Network capabilities changed: type=$type")
        }
    }

    // Keep a weak reference to context for the NetworkCallback callbacks
    @Volatile
    private var registeredContext: Context? = null

    // -------------------------------------------------------------------------
    // Lifecycle — call from AeroVpnService.onCreate() / onDestroy()
    // -------------------------------------------------------------------------

    /**
     * Register both the BroadcastReceiver (for screen/power events) and the
     * ConnectivityManager NetworkCallback (for reliable network state changes).
     * Must be called from a Service or Activity context.
     */
    fun register(context: Context) {
        registeredContext = context.applicationContext

        // 1. Register BroadcastReceiver programmatically for screen/power intents
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    this,
                    buildIntentFilter(),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(this, buildIntentFilter())
            }
            Log.d(TAG, "BroadcastReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register BroadcastReceiver", e)
        }

        // 2. Register NetworkCallback for connectivity changes
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!isNetworkCallbackRegistered) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback)
                isNetworkCallbackRegistered = true
                Log.d(TAG, "NetworkCallback registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register NetworkCallback", e)
            }
        }
    }

    /**
     * Unregister both the BroadcastReceiver and the NetworkCallback.
     * Must be called from the same context used in register().
     */
    fun unregister(context: Context) {
        // 1. Unregister BroadcastReceiver
        try {
            context.unregisterReceiver(this)
            Log.d(TAG, "BroadcastReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BroadcastReceiver was not registered", e)
        }

        // 2. Unregister NetworkCallback
        if (isNetworkCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
                Log.d(TAG, "NetworkCallback unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "NetworkCallback was not registered or already unregistered", e)
            } finally {
                isNetworkCallbackRegistered = false
            }
        }

        registeredContext = null
        connectivityManager = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getNetworkType(network: Network): String {
        val caps = connectivityManager?.getNetworkCapabilities(network) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    private fun handleNetworkChange(context: Context, isConnected: Boolean, networkType: String) {
        Log.d(TAG, "Network changed: isConnected=$isConnected, type=$networkType")
        notifyVpnService(
            context,
            ACTION_NETWORK_CHANGED,
            extras = mapOf(
                "is_connected" to isConnected,
                "network_type" to networkType
            )
        )
    }

    private fun notifyVpnService(
        context: Context,
        action: String,
        extras: Map<String, Any> = emptyMap()
    ) {
        try {
            val vpnIntent = Intent(context, Class.forName("com.aerovpn.service.AeroVpnService")).apply {
                this.action = action
                extras.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putExtra(key, value)
                        is String -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                        else -> putExtra(key, value.toString())
                    }
                }
            }
            context.startService(vpnIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify VPN service (action=$action)", e)
        }
    }

    companion object Actions {
        const val ACTION_NETWORK_CHANGED = "com.aerovpn.ACTION_NETWORK_CHANGED"
        const val ACTION_SCREEN_STATE_CHANGED = "com.aerovpn.ACTION_SCREEN_STATE_CHANGED"
        const val ACTION_POWER_STATE_CHANGED = "com.aerovpn.ACTION_POWER_STATE_CHANGED"
    }
}
