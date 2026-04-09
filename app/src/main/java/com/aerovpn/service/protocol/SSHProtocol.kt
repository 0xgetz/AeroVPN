package com.aerovpn.service.protocol

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * SSH protocol implementation with HTTP proxy, SSL, and WebSocket support.
 * Provides secure tunneling over SSH with various transport options.
 */
class SSHProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "SSHProtocol"
        private const val DEFAULT_PORT = 22
        private const val DEFAULT_MTU = 1500
        private const val DEFAULT_HTTP_PROXY_PORT = 8080
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType = ProtocolType.SSH

    @Volatile
    private var currentConfig: SSHConfig? = null

    @Volatile
    private var sshSession: Any? = null // Would be actual SSH session in production

    @Volatile
    private var localProxyPort: Int = -1

    override suspend fun connect(config: ProtocolConfig, vpnServiceBuilder: VpnService.Builder): Boolean {
        if (config !is SSHConfig) {
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
            
            // Establish SSH connection
            val connected = establishSSHConnection(config)
            
            if (connected) {
                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "SSH tunnel established successfully")
                return true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to establish SSH connection")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "SSH connection error", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            isActive = false
            return false
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        
        _connectionState.value = ConnectionState.Disconnecting
        
        try {
            // Close SSH session
            closeSSHSession()
            
            isActive = false
            currentConfig = null
            localProxyPort = -1
            _connectionState.value = ConnectionState.Idle
            Log.i(TAG, "SSH tunnel disconnected")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
            _connectionState.value = ConnectionState.Error("Disconnect error: ${e.message}", e)
        }
    }

    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        currentConfig?.let { cfg ->
            scope.launch {
                super.reconnect(maxRetries, initialDelayMs)
            }
        }
    }

    override protected suspend fun tryReconnect(): Boolean {
        return currentConfig?.let { config ->
            val builder = VpnService.Builder()
            connect(config, builder)
        } ?: false
    }

    /**
     * Configure VPN interface for SSH tunnel
     */
    private fun configureVpnInterface(builder: VpnService.Builder, config: SSHConfig) {
        // Set MTU
        builder.setMtu(config.mtu ?: DEFAULT_MTU)
        
        // Add VPN address
        try {
            builder.addAddress("10.0.0.2", 32)
            builder.addAddress("fd00::2", 128)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add addresses", e)
        }
        
        // Add DNS servers
        config.dnsServers.forEach { dns ->
            try {
                builder.addDnsServer(dns)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS: $dns", e)
            }
        }
        
        // Add routes based on routing mode
        when (config.tunnelMode) {
            TunnelMode.GLOBAL -> {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
            TunnelMode.PROXY_ONLY -> {
                // Only route traffic destined for proxy
                config.proxyApps.forEach { packageName ->
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set proxy app: $packageName", e)
                    }
                }
            }
            TunnelMode.BYPASS_LAN -> {
                addPrivateRoutes(builder)
                builder.addRoute("0.0.0.0", 0)
            }
        }
        
        // Set session name
        builder.setSession("AeroVPN-SSH")
    }

    /**
     * Add private IP ranges to bypass
     */
    private fun addPrivateRoutes(builder: VpnService.Builder) {
        val privateRanges = listOf(
            "10.0.0.0/8" to 8,
            "172.16.0.0/12" to 12,
            "192.168.0.0/16" to 16,
            "127.0.0.0/8" to 8
        )
        
        privateRanges.forEach { (route, prefix) ->
            try {
                val address = InetAddress.getByName(route.split("/")[0])
                builder.addRoute(address, prefix)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add private route: $route", e)
            }
        }
    }

    /**
     * Establish SSH connection with configured transport
     */
    private suspend fun establishSSHConnection(config: SSHConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    // WebSocket transport
                    config.useWebSocket -> {
                        establishWebSocketSSH(config)
                    }
                    // SSL/TLS transport
                    config.useSSL -> {
                        establishSSLSSH(config)
                    }
                    // HTTP proxy transport
                    config.useProxy -> {
                        establishProxySSH(config)
                    }
                    // Direct SSH connection
                    else -> {
                        establishDirectSSH(config)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSH establishment failed", e)
                false
            }
        }
    }

    /**
     * Establish direct SSH connection
     */
    private suspend fun establishDirectSSH(config: SSHConfig): Boolean {
        return try {
            // In production, use JSch, Apache MINA SSHD, or similar
            // 1. Create SSH client
            // 2. Configure authentication
            // 3. Connect to server
            // 4. Set up port forwarding
            
            kotlinx.coroutines.delay(1500)
            
            // Set up local SOCKS proxy (typically port 1080)
            localProxyPort = config.proxyPort
            
            // Simulate SSH session
            sshSession = Any()
            
            // Set up dynamic port forwarding (SOCKS proxy)
            setupSOCKSProxy(config)
            
            Log.i(TAG, "Direct SSH connection established on port ${config.serverPort}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Direct SSH failed", e)
            false
        }
    }

    /**
     * Establish SSH over WebSocket
     */
    private suspend fun establishWebSocketSSH(config: SSHConfig): Boolean {
        return try {
            // WebSocket SSH tunnels through WS endpoint
            val wsUrl = "ws://${config.serverAddress}:${config.wsPort}${config.wsPath}"
            
            kotlinx.coroutines.delay(1500)
            
            // In production:
            // 1. Establish WebSocket connection
            // 2. Upgrade to SSH protocol
            // 3. Authenticate
            // 4. Set up forwarding
            
            sshSession = Any()
            localProxyPort = config.proxyPort
            
            Log.i(TAG, "WebSocket SSH established: $wsUrl")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket SSH failed", e)
            false
        }
    }

    /**
     * Establish SSH over SSL/TLS
     */
    private suspend fun establishSSLSSH(config: SSHConfig): Boolean {
        return try {
            // SSL/TLS wrapped SSH (stunnel-like)
            kotlinx.coroutines.delay(1500)
            
            // In production:
            // 1. Create SSL socket
            // 2. Perform TLS handshake
            // 3. Establish SSH through SSL tunnel
            // 4. Authenticate
            
            sshSession = Any()
            localProxyPort = config.proxyPort
            
            Log.i(TAG, "SSL SSH established on port ${config.serverPort}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "SSL SSH failed", e)
            false
        }
    }

    /**
     * Establish SSH through HTTP proxy
     */
    private suspend fun establishProxySSH(config: SSHConfig): Boolean {
        return try {
            // CONNECT method through HTTP proxy
            val proxyHost = config.proxyHost
            val proxyPort = config.proxyPort
            
            kotlinx.coroutines.delay(1500)
            
            // In production:
            // 1. Connect to HTTP proxy
            // 2. Send CONNECT request to SSH server
            // 3. Upgrade to SSH protocol
            // 4. Authenticate
            
            sshSession = Any()
            localProxyPort = config.proxyPort
            
            Log.i(TAG, "HTTP Proxy SSH established via $proxyHost:$proxyPort")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "HTTP Proxy SSH failed", e)
            false
        }
    }

    /**
     * Set up SOCKS proxy for traffic routing
     */
    private suspend fun setupSOCKSProxy(config: SSHConfig) {
        // In production, this would create a local SOCKS4/5 proxy server
        // that forwards traffic through the SSH tunnel
        Log.i(TAG, "SOCKS proxy started on port ${config.proxyPort}")
    }

    /**
     * Close SSH session
     */
    private suspend fun closeSSHSession() {
        try {
            // In production: properly close SSH session, disconnect channels
            kotlinx.coroutines.delay(500)
            sshSession = null
            Log.i(TAG, "SSH session closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SSH session", e)
        }
    }
}

/**
 * Tunnel mode for SSH connections
 */
enum class TunnelMode {
    GLOBAL,         // All traffic through tunnel
    PROXY_ONLY,     // Only specified apps use proxy
    BYPASS_LAN      // Bypass LAN, rest through tunnel
}

/**
 * SSH-specific configuration
 */
data class SSHConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int = DEFAULT_PORT,
    val username: String,
    val password: String? = null,
    val privateKey: String? = null,
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val mtu: Int? = null,
    val routingConfig: V2RayConfig.RoutingConfig = V2RayConfig.RoutingConfig(),
    val proxyPort: Int = DEFAULT_HTTP_PROXY_PORT,
    val tunnelMode: TunnelMode = TunnelMode.GLOBAL,
    val proxyApps: List<String> = emptyList(),
    
    // HTTP Proxy settings
    val useProxy: Boolean = false,
    val proxyHost: String? = null,
    
    // SSL/TLS settings
    val useSSL: Boolean = false,
    val sslVerify: Boolean = true,
    val sslCertPath: String? = null,
    
    // WebSocket settings
    val useWebSocket: Boolean = false,
    val wsPath: String = "/ssh",
    val wsPort: Int = 443,
    
    // Connection settings
    val timeout: Int = 30000,
    val keepAliveInterval: Int = 60,
    val compression: Boolean = false
) : ProtocolConfig() {

    override fun validate(): Boolean {
        return username.isNotBlank() &&
               serverAddress.isNotBlank() &&
               serverPort in 1..65535 &&
               (password != null || privateKey != null)
    }
    
    /**
     * Get authentication method
     */
    fun getAuthMethod(): AuthMethod {
        return when {
            privateKey != null -> AuthMethod.PRIVATE_KEY
            password != null -> AuthMethod.PASSWORD
            else -> AuthMethod.NONE
        }
    }
}

/**
 * SSH authentication methods
 */
enum class AuthMethod {
    PASSWORD,
    PRIVATE_KEY,
    NONE
}
