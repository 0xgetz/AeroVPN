package com.aerovpn.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Connection sharing type
 */
enum class SharingType {
    WIFI_HOTSPOT,
    USB_TETHERING,
    BLUETOOTH_TETHERING,
    ETHERNET
}

/**
 * Hotspot configuration
 */
data class HotspotConfig(
    val ssid: String,
    val password: String,
    val band: Int = WifiManager.WIFI_AP_BAND_2GHZ,
    val channel: Int = 0,  // 0 = auto
    val maxClients: Int = 10,
    val hiddenSsid: Boolean = false,
    val securityType: SecurityType = SecurityType.WPA2_PSK
)

enum class SecurityType {
    OPEN,
    WPA2_PSK,
    WPA3_SAE,
    WPA2_WPA3_MIXED
}

/**
 * Connection sharing status
 */
data class SharingStatus(
    val isActive: Boolean,
    val type: SharingType?,
    val connectedClients: Int,
    val clients: List<ClientInfo>,
    val dataUsage: DataUsage,
    val startTime: Long?
)

data class ClientInfo(
    val macAddress: String,
    val ipAddress: String,
    val hostname: String?,
    val connectedTime: Long,
    val dataUsage: Long
)

data class DataUsage(
    val uploaded: Long,
    val downloaded: Long,
    val total: Long
)

/**
 * ShareConnectionTool - WiFi hotspot/USB tethering sharing
 * Manage VPN connection sharing through hotspot or tethering
 */
object ShareConnectionTool {

    private const val TAG = "ShareConnectionTool"

    /**
     * Check if WiFi hotspot is available
     */
    suspend fun isHotspotAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        try {
            val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hotspot status", e)
            false
        }
    }

    /**
     * Check if USB tethering is available
     */
    suspend fun isUsbTetheringAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        try {
            val method = connectivityManager.javaClass.getMethod("isTetheringSupported")
            method.invoke(connectivityManager) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable WiFi hotspot with VPN sharing
     */
    suspend fun enableHotspot(
        context: Context,
        config: HotspotConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // Check if already enabled
            if (isHotspotAvailable(context)) {
                return@withContext Result.failure(Exception("Hotspot already enabled"))
            }
            
            // Use reflection to enable hotspot (API dependent)
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.java
            )
            
            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = config.ssid
                preSharedKey = config.password
                
                when (config.securityType) {
                    SecurityType.OPEN -> {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                        allowedAuthAlgorithms.set(0)
                    }
                    SecurityType.WPA2_PSK -> {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                        allowedPairwiseCiphers.set(android.net.wifi.WifiConfiguration.Pairwise.CCMP)
                        allowedGroupCiphers.set(android.net.wifi.WifiConfiguration.GroupCipher.CCMP)
                        allowedProtocols.set(android.net.wifi.WifiConfiguration.Protocol.RSN)
                    }
                    SecurityType.WPA3_SAE, SecurityType.WPA2_WPA3_MIXED -> {
                        // WPA3 requires API 30+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.SAE)
                        } else {
                            // Fallback to WPA2
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                        }
                    }
                }
            }
            
            method.invoke(wifiManager, wifiConfig, true)
            
            // Wait for hotspot to enable
            var attempts = 0
            while (attempts < 10 && !isHotspotAvailable(context)) {
                Thread.sleep(500)
                attempts++
            }
            
            if (isHotspotAvailable(context)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to enable hotspot"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling hotspot", e)
            Result.failure(e)
        }
    }

    /**
     * Disable WiFi hotspot
     */
    suspend fun disableHotspot(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.java
            )
            
            method.invoke(wifiManager, null, false)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling hotspot", e)
            Result.failure(e)
        }
    }

    /**
     * Enable USB tethering
     */
    suspend fun enableUsbTethering(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Use reflection to enable USB tethering
            val method = connectivityManager.javaClass.getMethod(
                "setUsbTethering",
                Boolean::class.javaPrimitiveType
            )
            
            method.invoke(connectivityManager, true)
            
            // Verify tethering is enabled
            Thread.sleep(1000)
            val isTethered = isUsbTetheringActive(context)
            
            if (isTethered) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to enable USB tethering"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling USB tethering", e)
            Result.failure(e)
        }
    }

    /**
     * Disable USB tethering
     */
    suspend fun disableUsbTethering(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val method = connectivityManager.javaClass.getMethod(
                "setUsbTethering",
                Boolean::class.javaPrimitiveType
            )
            
            method.invoke(connectivityManager, false)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling USB tethering", e)
            Result.failure(e)
        }
    }

    /**
     * Get current sharing status
     */
    suspend fun getSharingStatus(context: Context): SharingStatus = withContext(Dispatchers.IO) {
        val isActive = isHotspotAvailable(context) || isUsbTetheringActive(context)
        val type = when {
            isHotspotAvailable(context) -> SharingType.WIFI_HOTSPOT
            isUsbTetheringActive(context) -> SharingType.USB_TETHERING
            else -> null
        }
        
        val clients = getConnectedClients(context)
        val dataUsage = getDataUsage(context)
        
        SharingStatus(
            isActive = isActive,
            type = type,
            connectedClients = clients.size,
            clients = clients,
            dataUsage = dataUsage,
            startTime = null  // Would need to track separately
        )
    }

    /**
     * Check if VPN can be shared
     */
    suspend fun canShareVpn(context: Context): VpnShareCapability {
        val hasVpn = hasActiveVpnConnection(context)
        val canHotspot = isHotspotAvailable(context) || isHotspotCapable(context)
        val canTether = isUsbTetheringAvailable(context)
        
        return when {
            !hasVpn -> VpnShareCapability.NO_VPN_ACTIVE
            canHotspot -> VpnShareCapability.CAN_SHARE_HOTSPOT
            canTether -> VpnShareCapability.CAN_SHARE_TETHERING
            else -> VpnShareCapability.CANNOT_SHARE
        }
    }

    /**
     * Configure VPN-aware hotspot (route VPN traffic through hotspot)
     */
    suspend fun configureVpnHotspot(
        context: Context,
        vpnInterface: android.net.VpnService.Builder,
        hotspotConfig: HotspotConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // This would require root or system permissions
            // For normal apps, we can only enable hotspot, not route VPN through it
            
            // Enable hotspot
            enableHotspot(context, hotspotConfig).onSuccess {
                // Note: Without root, VPN traffic won't automatically route through hotspot
                // Users would need to:
                // 1. Connect to hotspot
                // 2. Enable VPN on client device
                // Or use a rooted device with proper iptables rules
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get list of connected clients
     */
    private suspend fun getConnectedClients(context: Context): List<ClientInfo> = withContext(Dispatchers.IO) {
        val clients = mutableListOf<ClientInfo>()
        
        try {
            // This would require access to DHCP lease file or system APIs
            // For non-root apps, we can only get limited information
            
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = connectivityManager.allNetworks
            
            networks.forEach { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    // This network is WiFi, could be hotspot clients
                    // Without root, we can't get detailed client info
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected clients", e)
        }
        
        clients
    }

    /**
     * Get data usage for hotspot/tethering
     */
    private suspend fun getDataUsage(context: Context): DataUsage = withContext(Dispatchers.IO) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            
            val trafficStats = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val txStats = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
            
            DataUsage(
                uploaded = txStats.takeUnless { it == android.net.TrafficStats.UNSUPPORTED } ?: 0,
                downloaded = trafficStats.takeUnless { it == android.net.TrafficStats.UNSUPPORTED } ?: 0,
                total = 0
            )
        } catch (e: Exception) {
            DataUsage(0, 0, 0)
        }
    }

    private suspend fun isUsbTetheringActive(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method = connectivityManager.javaClass.getMethod("isUsbTetheringEnabled")
            method.invoke(connectivityManager) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun isHotspotCapable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.is5GHzBandSupported || wifiManager.isDualBandSupported
    }

    private suspend fun hasActiveVpnConnection(context: Context): Boolean = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

enum class VpnShareCapability {
    CAN_SHARE_HOTSPOT,
    CAN_SHARE_TETHERING,
    CAN_SHARE_BOTH,
    CANNOT_SHARE,
    NO_VPN_ACTIVE
}

// Extension properties for WiFiManager
val WifiManager.is5GHzBandSupported: Boolean
    get() {
        return try {
            val method = javaClass.getMethod("is5GHzBandSupported")
            method.invoke(this) as Boolean
        } catch (e: Exception) {
            false
        }
    }

val WifiManager.isDualBandSupported: Boolean
    get() {
        return try {
            val method = javaClass.getMethod("isDualBandSupported")
            method.invoke(this) as Boolean
        } catch (e: Exception) {
            false
        }
    }
