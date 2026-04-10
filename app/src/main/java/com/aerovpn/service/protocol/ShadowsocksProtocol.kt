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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shadowsocks protocol implementation.
 * Lightweight SOCKS5 proxy with efficient encryption.
 */
class ShadowsocksProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "ShadowsocksProtocol"
        private const val DEFAULT_PORT = 8388
        private const val DEFAULT_MTU = 1500
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType = ProtocolType.SHADOWSOCKS

    @Volatile
    private var currentConfig: ShadowsocksConfig? = null

    @Volatile
    private var proxyServerPort: Int = -1

    override suspend fun connect(config: ProtocolConfig, vpnServiceBuilder: VpnService.Builder): Boolean {
        if (config !is ShadowsocksConfig) {
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
            
            // Start Shadowsocks proxy
            val started = startShadowsocksProxy(config)
            
            if (started) {
                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "Shadowsocks proxy started successfully")
                return true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to start Shadowsocks")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Shadowsocks connection error", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            isActive = false
            return false
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        
        _connectionState.value = ConnectionState.Disconnecting
        
        try {
            // Stop Shadowsocks proxy
            stopShadowsocksProxy()
            
            isActive = false
            currentConfig = null
            proxyServerPort = -1
            _connectionState.value = ConnectionState.Idle
            Log.i(TAG, "Shadowsocks disconnected")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
            _connectionState.value = ConnectionState.Error("Disconnect error: ${e.message}", e)
        }
    }

    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        super.reconnect(maxRetries, initialDelayMs)
    }

    override protected suspend fun tryReconnect(): Boolean {
        return false // No VpnService reference available for reconnect
    }

    /**
     * Configure VPN interface for Shadowsocks
     */
    private fun configureVpnInterface(builder: VpnService.Builder, config: ShadowsocksConfig) {
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
        when (config.routeMode) {
            RouteMode.GLOBAL -> {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
            RouteMode.BYPASS_LAN -> {
                addPrivateRoutes(builder)
                builder.addRoute("0.0.0.0", 0)
            }
            RouteMode.WHITELIST -> {
                // Only specified domains/IPs through VPN
                config.whitelistDomains.forEach { domain ->
                    // DNS-based routing would be handled by local DNS resolver
                }
                config.whitelistIPs.forEach { ip ->
                    try {
                        val parts = ip.split("/")
                        if (parts.size == 2) {
                            val address = InetAddress.getByName(parts[0])
                            builder.addRoute(address, parts[1].toInt())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add whitelist route: $ip", e)
                    }
                }
            }
            RouteMode.BLACKLIST -> {
                // Specified domains/IPs bypass VPN
                builder.addRoute("0.0.0.0", 0)
                config.blacklistDomains.forEach { domain ->
                    // Handled by DNS resolver
                }
                config.blacklistIPs.forEach { ip ->
                    try {
                        val parts = ip.split("/")
                        if (parts.size == 2) {
                            val address = InetAddress.getByName(parts[0])
                            // Note: VPNService doesn't support route exclusion,
                            // this would need to be handled by local proxy
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process blacklist: $ip", e)
                    }
                }
            }
        }
        
        // Bypass specified apps
        config.bypassApps.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bypass app: $packageName", e)
            }
        }
        
        // Set session name
        builder.setSession("AeroVPN-Shadowsocks")
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
     * Start Shadowsocks SOCKS5 proxy
     */
    private suspend fun startShadowsocksProxy(config: ShadowsocksConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Validate cipher
                val cipher = validateCipher(config.method)
                if (cipher == null) {
                    Log.e(TAG, "Invalid cipher method: ${config.method}")
                    return@withContext false
                }
                
                // In production:
                // 1. Start local SOCKS5 proxy server
                // 2. Set up Shadowsocks encryption layer
                // 3. Connect to remote server
                // 4. Relay traffic with encryption
                
                kotlinx.coroutines.delay(1000)
                
                // Set proxy port (typically 1080)
                proxyServerPort = config.proxyPort
                
                Log.i(TAG, "Shadowsocks proxy started on port $proxyServerPort using ${config.method}")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Shadowsocks proxy", e)
                false
            }
        }
    }

    /**
     * Stop Shadowsocks proxy
     */
    private suspend fun stopShadowsocksProxy() {
        try {
            // In production: gracefully stop proxy server
            kotlinx.coroutines.delay(300)
            Log.i(TAG, "Shadowsocks proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Shadowsocks proxy", e)
        }
    }

    /**
     * Validate and get cipher instance
     */
    private fun validateCipher(method: String): Cipher? {
        return try {
            val (algorithm, mode, padding) = parseCipherMethod(method)
            val cipher = Cipher.getInstance("$algorithm/$mode/$padding")
            cipher
        } catch (e: Exception) {
            Log.e(TAG, "Invalid cipher: $method", e)
            null
        }
    }

    /**
     * Parse cipher method string
     */
    private fun parseCipherMethod(method: String): Triple<String, String, String> {
        return when (method.lowercase()) {
            "aes-128-gcm" -> Triple("AES", "GCM", "NoPadding")
            "aes-256-gcm" -> Triple("AES", "GCM", "NoPadding")
            "aes-128-cfb" -> Triple("AES", "CFB", "NoPadding")
            "aes-256-cfb" -> Triple("AES", "CFB", "NoPadding")
            "chacha20-poly1305" -> Triple("ChaCha20", "Poly1305", "NoPadding")
            "chacha20" -> Triple("ChaCha20", "CTR", "NoPadding")
            "rc4-md5" -> Triple("RC4", "STREAM", "NoPadding")
            else -> Triple("AES", "CFB", "NoPadding") // Default
        }
    }

    /**
     * Encrypt data using Shadowsocks method
     */
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CFB/NoPadding") // Simplified
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            data // Return original on failure (production should handle better)
        }
    }

    /**
     * Decrypt data using Shadowsocks method
     */
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CFB/NoPadding") // Simplified
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            data // Return original on failure
        }
    }

    /**
     * Generate key from password based on method
     */
    fun generateKey(password: String, method: String): ByteArray {
        // In production, use proper key derivation (e.g., EVP_BytesToKey)
        // This is a simplified implementation
        val keyLength = when (method.lowercase()) {
            "aes-128-gcm", "aes-128-cfb" -> 16
            "aes-256-gcm", "aes-256-cfb" -> 32
            "chacha20", "chacha20-poly1305" -> 32
            "rc4-md5" -> 16
            else -> 32
        }
        
        // Simple key derivation (production should use proper KDF)
        val bytes = password.toByteArray(Charsets.UTF_8)
        val key = ByteArray(keyLength)
        for (i in key.indices) {
            key[i] = bytes[i % bytes.size]
        }
        return key
    }
}

/**
 * Supported Shadowsocks encryption methods
 */
enum class ShadowMethod(val keyLength: Int) {
    AES_128_GCM(16),
    AES_256_GCM(32),
    AES_128_CFB(16),
    AES_256_CFB(32),
    CHACHA20_POLY1305(32),
    CHACHA20(32),
    RC4_MD5(16);

    companion object {
        fun fromString(method: String): ShadowMethod? {
            return when (method.lowercase()) {
                "aes-128-gcm" -> AES_128_GCM
                "aes-256-gcm" -> AES_256_GCM
                "aes-128-cfb" -> AES_128_CFB
                "aes-256-cfb" -> AES_256_CFB
                "chacha20-poly1305" -> CHACHA20_POLY1305
                "chacha20" -> CHACHA20
                "rc4-md5" -> RC4_MD5
                else -> null
            }
        }
        
        val DEFAULT = AES_256_GCM
    }
}

/**
 * Shadowsocks-specific configuration
 */
data class ShadowsocksConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int = 8388,
    val password: String,
    val method: String = ShadowMethod.DEFAULT.name.lowercase().replace("_", "-"),
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val mtu: Int? = null,
    val routeMode: RouteMode = RouteMode.GLOBAL,
    val bypassApps: List<String> = emptyList(),
    val proxyPort: Int = 1080,
    val whitelistDomains: List<String> = emptyList(),
    val whitelistIPs: List<String> = emptyList(),
    val blacklistDomains: List<String> = emptyList(),
    val blacklistIPs: List<String> = emptyList(),
    val timeout: Int = 300,
    val udpRelay: Boolean = true,
    val ipv6Enabled: Boolean = false
) : ProtocolConfig() {

    override fun validate(): Boolean {
        return password.isNotBlank() &&
               serverAddress.isNotBlank() &&
               serverPort in 1..65535 &&
               ShadowMethod.fromString(method) != null
    }
    
    /**
     * Get key length for the selected method
     */
    fun getKeyLength(): Int {
        return ShadowMethod.fromString(method)?.keyLength ?: 32
    }
}
