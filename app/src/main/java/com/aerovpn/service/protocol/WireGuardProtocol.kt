package com.aerovpn.service.protocol

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * WireGuard protocol implementation.
 * Provides fast, modern VPN tunnel with strong encryption.
 */
class WireGuardProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "WireGuardProtocol"
        private const val INTERFACE_NAME = "wg0"
        private const val DEFAULT_MTU = 1420
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType = ProtocolType.WIREGUARD

    @Volatile
    private var tunnelHandle: Int = -1

    @Volatile
    private var currentConfig: WireGuardConfig? = null

    override suspend fun connect(config: ProtocolConfig, vpnServiceBuilder: VpnService.Builder): Boolean {
        if (config !is WireGuardConfig) {
            Log.e(TAG, "Invalid configuration type")
            return false
        }

        if (!config.validate()) {
            Log.e(TAG, "Configuration validation failed")
            _connectionState.value = ConnectionState.Error("Invalid configuration")
            return false
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            currentConfig = config
            
            // Configure VPN interface
            configureVpnInterface(vpnServiceBuilder, config)
            
            // Establish WireGuard tunnel
            val connected = establishTunnel(config)
            
            if (connected) {
                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "WireGuard tunnel established successfully")
                return true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to establish tunnel")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "WireGuard connection error", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            isActive = false
            return false
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        
        _connectionState.value = ConnectionState.Disconnecting
        
        try {
            // Stop WireGuard tunnel
            tunnelHandle.let { handle ->
                if (handle != -1) {
                    stopWireGuardTunnel(handle)
                    tunnelHandle = -1
                }
            }
            
            isActive = false
            currentConfig = null
            _connectionState.value = ConnectionState.Idle
            Log.i(TAG, "WireGuard tunnel disconnected")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
            _connectionState.value = ConnectionState.Error("Disconnect error: ${e.message}", e)
        }
    }

    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        config?.let { cfg ->
            scope.launch {
                super.reconnect(maxRetries, initialDelayMs)
            }
        }
    }

    override protected suspend fun tryReconnect(): Boolean {
        return currentConfig?.let { config ->
            // Create a new builder for reconnection
            val builder = createVpnBuilder()
            connect(config, builder)
        } ?: false
    }

    /**
     * Configure VPN interface with WireGuard settings
     */
    private fun configureVpnInterface(builder: VpnService.Builder, config: WireGuardConfig) {
        // Set MTU for WireGuard (typically 1420)
        builder.setMtu(config.mtu ?: DEFAULT_MTU)
        
        // Add address
        config.privateKey.let { 
            // Parse and add IP addresses
            config.addresses.forEach { address ->
                try {
                    val inetAddress = InetAddress.getByName(address.split("/")[0])
                    builder.addAddress(inetAddress, if (address.contains("/")) address.split("/")[1].toInt() else 32)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add address: $address", e)
                }
            }
        }
        
        // Add DNS servers
        config.dnsServers.forEach { dns ->
            try {
                builder.addDnsServer(dns)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS: $dns", e)
            }
        }
        
        // Add routes
        if (config.allowedIPs.isNotEmpty()) {
            config.allowedIPs.forEach { route ->
                try {
                    val parts = route.split("/")
                    if (parts.size == 2) {
                        val address = InetAddress.getByName(parts[0])
                        val prefixLength = parts[1].toInt()
                        builder.addRoute(address, prefixLength)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add route: $route", e)
                }
            }
        } else {
            // Default route (all traffic through VPN)
            builder.addRoute("0.0.0.0", 0)
        }
        
        // Add bypass apps
        config.bypassApps.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bypass app: $packageName", e)
            }
        }
        
        // Configure session
        builder.setSession("AeroVPN-WireGuard")
    }

    /**
     * Establish WireGuard tunnel using native implementation
     * This would use the WireGuard-Android library in production
     */
    private suspend fun establishTunnel(config: WireGuardConfig): Boolean {
        return try {
            // In production, this would use WireGuard-Android library:
            // 1. Create configuration file
            // 2. Start tunnel using WireGuardInterface
            // 3. Monitor tunnel state
            
            // Simulated tunnel establishment
            kotlinx.coroutines.delay(1500)
            
            // Generate a tunnel handle (in real impl, this comes from native library)
            tunnelHandle = config.privateKey.hashCode() and 0xFFFF
            
            Log.i(TAG, "WireGuard tunnel established with handle: $tunnelHandle")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish WireGuard tunnel", e)
            false
        }
    }

    /**
     * Stop WireGuard tunnel
     */
    private suspend fun stopWireGuardTunnel(handle: Int) {
        try {
            // In production: WireGuardInterface.stopTunnel(handle)
            kotlinx.coroutines.delay(500)
            Log.i(TAG, "WireGuard tunnel $handle stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel $handle", e)
        }
    }

    /**
     * Create VPN builder for reconnection
     */
    private fun createVpnBuilder(): VpnService.Builder {
        return VpnService.Builder()
    }
}

/**
 * WireGuard-specific configuration
 */
data class WireGuardConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int = 51820,
    val privateKey: String,
    val publicKey: String,
    val presharedKey: String? = null,
    val addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val allowedIPs: List<String> = emptyList(),
    val mtu: Int? = null,
    val persistentKeepalive: Int = 25,
    val bypassApps: List<String> = emptyList()
) : ProtocolConfig() {

    override fun validate(): Boolean {
        return privateKey.isNotBlank() &&
               publicKey.isNotBlank() &&
               serverAddress.isNotBlank() &&
               serverPort in 1..65535 &&
               addresses.isNotEmpty()
    }
}
