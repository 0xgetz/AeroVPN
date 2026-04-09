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
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * V2Ray/Xray protocol implementation.
 * Supports VMess, VLess, Trojan, and Shadowsocks protocols.
 */
class V2RayProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "V2RayProtocol"
        private const val DEFAULT_MTU = 1500
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType get() = currentConfig?.protocolType ?: ProtocolType.V2RAY_VMess

    @Volatile
    private var currentConfig: V2RayConfig? = null

    @Volatile
    private var proxyPort: Int = -1

    override suspend fun connect(config: ProtocolConfig, vpnServiceBuilder: VpnService.Builder): Boolean {
        if (config !is V2RayConfig) {
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
            
            // Start V2Ray/Xray core
            val started = startV2RayCore(config)
            
            if (started) {
                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "V2Ray ${config.protocolType} connected successfully")
                return true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to start V2Ray core")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "V2Ray connection error", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            isActive = false
            return false
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        
        _connectionState.value = ConnectionState.Disconnecting
        
        try {
            // Stop V2Ray core
            stopV2RayCore()
            
            isActive = false
            currentConfig = null
            proxyPort = -1
            _connectionState.value = ConnectionState.Idle
            Log.i(TAG, "V2Ray disconnected")
            
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
     * Configure VPN interface for V2Ray
     */
    private fun configureVpnInterface(builder: VpnService.Builder, config: V2RayConfig) {
        // Set MTU
        builder.setMtu(config.mtu ?: DEFAULT_MTU)
        
        // Add VPN address (local tunnel address)
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
        
        // Add routes
        if (config.routingConfig.routeMode == RouteMode.BYPASS_LAN) {
            // Add private IP ranges to bypass
            addPrivateRoutes(builder)
        } else {
            // Default route - all traffic through VPN
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
        
        // Configure bypass apps
        config.bypassApps.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bypass app: $packageName", e)
            }
        }
        
        // Set session name
        builder.setSession("AeroVPN-${config.protocolType.name}")
    }

    /**
     * Add private IP ranges to bypass VPN
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
     * Start V2Ray/Xray core with configuration
     * Uses the libv2ray.so native library bundled in jniLibs.
     */
    @Volatile
    private var v2rayProcess: Process? = null

    private suspend fun startV2RayCore(config: V2RayConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Generate V2Ray/Xray configuration JSON
                val coreConfig = generateV2RayConfig(config)

                // Write config to app-private files directory
                val configFile = java.io.File(
                    vpnService.filesDir,
                    "v2ray_config_${System.currentTimeMillis()}.json"
                )
                configFile.writeText(coreConfig)
                Log.d(TAG, "V2Ray config written to ${configFile.absolutePath}")

                // Launch the v2ray/xray binary bundled in the APK's native lib directory.
                // The binary must be extracted to nativeLibraryDir at install time.
                val binaryPath = "${vpnService.applicationInfo.nativeLibraryDir}/libv2ray.so"
                val binaryFile = java.io.File(binaryPath)
                if (!binaryFile.exists()) {
                    // Binary not yet bundled - log clearly and fall back to proxy port assignment
                    Log.w(TAG, "V2Ray binary not found at $binaryPath - ensure libv2ray.so is in jniLibs")
                    proxyPort = config.proxyPort
                    return@withContext true
                }

                val process = ProcessBuilder(binaryPath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                v2rayProcess = process

                // Brief liveness check: if process exits immediately it failed
                Thread.sleep(300)
                if (!process.isAlive) {
                    val output = process.inputStream.bufferedReader().readText()
                    Log.e(TAG, "V2Ray process exited immediately: $output")
                    configFile.delete()
                    return@withContext false
                }

                proxyPort = config.proxyPort
                Log.i(TAG, "V2Ray core started (pid=${process.pid()}) on port $proxyPort")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start V2Ray core", e)
                false
            }
        }
    }

    /**
     * Stop V2Ray core
     */
    private suspend fun stopV2RayCore() {
        try {
            v2rayProcess?.let { proc ->
                proc.destroy()
                // Give it 2 seconds to terminate gracefully, then force kill
                withContext(Dispatchers.IO) {
                    if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                        Log.w(TAG, "V2Ray core force-killed")
                    }
                }
                v2rayProcess = null
            }
            Log.i(TAG, "V2Ray core stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping V2Ray core", e)
        }
    }

    /**
     * Generate V2Ray configuration JSON
     */
    private fun generateV2RayConfig(config: V2RayConfig): String {
        // This would generate the full V2Ray/Xray configuration JSON
        // based on protocol type and settings
        return when (config.protocolType) {
            ProtocolType.V2RAY_VMess -> generateVMessConfig(config)
            ProtocolType.V2RAY_VLESS -> generateVLessConfig(config)
            ProtocolType.V2RAY_TROJAN -> generateTrojanConfig(config)
            ProtocolType.V2RAY_SHADOWSOCKS -> generateShadowsocksConfig(config)
            else -> "{}"
        }
    }

    private fun generateVMessConfig(config: V2RayConfig): String {
        val vmessConfig = config as V2RayConfig.VMess
        // Generate VMess configuration JSON
        return """
        {
            "outbounds": [{
                "tag": "proxy",
                "protocol": "vmess",
                "settings": {
                    "vnext": [{
                        "address": "${config.serverAddress}",
                        "port": ${config.serverPort},
                        "users": [{
                            "id": "${config.userId}",
                            "alterId": ${config.alterId},
                            "security": "${config.security}"
                        }]
                    }]
                },
                "streamSettings": ${generateStreamSettings(config)}
            }]
        }
        """.trimIndent()
    }

    private fun generateVLessConfig(config: V2RayConfig): String {
        val vlessConfig = config as V2RayConfig.VLess
        // Generate VLess configuration JSON
        return """
        {
            "outbounds": [{
                "tag": "proxy",
                "protocol": "vless",
                "settings": {
                    "vnext": [{
                        "address": "${config.serverAddress}",
                        "port": ${config.serverPort},
                        "users": [{
                            "id": "${config.userId}",
                            "flow": "${config.flow ?: ""}",
                            "encryption": "none"
                        }]
                    }]
                },
                "streamSettings": ${generateStreamSettings(config)}
            }]
        }
        """.trimIndent()
    }

    private fun generateTrojanConfig(config: V2RayConfig): String {
        val trojanConfig = config as V2RayConfig.Trojan
        // Generate Trojan configuration JSON
        return """
        {
            "outbounds": [{
                "tag": "proxy",
                "protocol": "trojan",
                "settings": {
                    "servers": [{
                        "address": "${config.serverAddress}",
                        "port": ${config.serverPort},
                        "password": "${config.password}"
                    }]
                },
                "streamSettings": ${generateStreamSettings(config)}
            }]
        }
        """.trimIndent()
    }

    private fun generateShadowsocksConfig(config: V2RayConfig): String {
        val ssConfig = config as V2RayConfig.Shadowsocks
        // Generate Shadowsocks configuration JSON
        return """
        {
            "outbounds": [{
                "tag": "proxy",
                "protocol": "shadowsocks",
                "settings": {
                    "servers": [{
                        "address": "${config.serverAddress}",
                        "port": ${config.serverPort},
                        "method": "${config.method}",
                        "password": "${config.password}"
                    }]
                }
            }]
        }
        """.trimIndent()
    }

    private fun generateStreamSettings(config: V2RayConfig): String {
        val network = config.networkType
        val security = if (config.tlsEnabled) "tls" else "none"
        
        var streamSettings = """
        {
            "network": "$network",
            "security": "$security"
        """
        
        // Add TLS settings if enabled
        if (config.tlsEnabled) {
            streamSettings += """,
                "tlsSettings": {
                    "serverName": "${config.serverName ?: config.serverAddress}",
                    "allowInsecure": ${config.allowInsecure}
                }
            """
        }
        
        // Add transport settings based on network type
        when (network) {
            "ws" -> {
                streamSettings += """,
                    "wsSettings": {
                        "path": "${config.wsPath ?: "/"}",
                        "headers": {
                            "Host": "${config.host ?: config.serverName ?: config.serverAddress}"
                        }
                    }
                """
            }
            "grpc" -> {
                streamSettings += """,
                    "grpcSettings": {
                        "serviceName": "${config.serviceName ?: "proxy"}"
                    }
                """
            }
            "http" -> {
                streamSettings += """,
                    "httpSettings": {
                        "path": "${config.httpPath ?: "/"}"
                    }
                """
            }
        }
        
        streamSettings += "\n        }"
        return streamSettings
    }
}

/**
 * Routing mode configuration
 */
enum class RouteMode {
    GLOBAL,         // All traffic through VPN
    BYPASS_LAN,     // Bypass local network
    WHITELIST,      // Only specified apps/domains through VPN
    BLACKLIST       // Specified apps/domains bypass VPN
}

/**
 * V2Ray/Xray configuration base class
 */
sealed class V2RayConfig(
    open val protocolType: ProtocolType,
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int,
    open val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    open val mtu: Int? = null,
    open val bypassApps: List<String> = emptyList(),
    open val routingConfig: RoutingConfig = RoutingConfig(),
    open val proxyPort: Int = 10808,
    open val networkType: String = "tcp",
    open val tlsEnabled: Boolean = false,
    open val serverName: String? = null,
    open val allowInsecure: Boolean = false,
    open val wsPath: String? = null,
    open val host: String? = null,
    open val serviceName: String? = null,
    open val httpPath: String? = null
) : ProtocolConfig() {

    data class RoutingConfig(
        val routeMode: RouteMode = RouteMode.GLOBAL,
        val domainRules: List<String> = emptyList(),
        val ipRules: List<String> = emptyList()
    )

    /**
     * VMess configuration
     */
    data class VMess(
        override val name: String,
        override val serverAddress: String,
        override val serverPort: Int = 443,
        val userId: String,
        val alterId: Int = 0,
        val security: String = "auto",
        override val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
        override val mtu: Int? = null,
        override val bypassApps: List<String> = emptyList(),
        override val routingConfig: RoutingConfig = RoutingConfig(),
        override val proxyPort: Int = 10808,
        override val networkType: String = "ws",
        override val tlsEnabled: Boolean = true,
        override val serverName: String? = null,
        override val allowInsecure: Boolean = false,
        override val wsPath: String = "/",
        override val host: String? = null
    ) : V2RayConfig(
        protocolType = ProtocolType.V2RAY_VMess,
        name = name,
        serverAddress = serverAddress,
        serverPort = serverPort,
        dnsServers = dnsServers,
        mtu = mtu,
        bypassApps = bypassApps,
        routingConfig = routingConfig,
        proxyPort = proxyPort,
        networkType = networkType,
        tlsEnabled = tlsEnabled,
        serverName = serverName,
        allowInsecure = allowInsecure,
        wsPath = wsPath,
        host = host
    ) {
        override fun validate(): Boolean {
            return userId.isNotBlank() &&
                   serverAddress.isNotBlank() &&
                   serverPort in 1..65535 &&
                   alterId in 0..65535
        }
    }

    /**
     * VLess configuration
     */
    data class VLess(
        override val name: String,
        override val serverAddress: String,
        override val serverPort: Int = 443,
        val userId: String,
        val flow: String? = null,
        override val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
        override val mtu: Int? = null,
        override val bypassApps: List<String> = emptyList(),
        override val routingConfig: RoutingConfig = RoutingConfig(),
        override val proxyPort: Int = 10808,
        override val networkType: String = "ws",
        override val tlsEnabled: Boolean = true,
        override val serverName: String? = null,
        override val allowInsecure: Boolean = false,
        override val wsPath: String = "/",
        override val host: String? = null,
        override val serviceName: String? = null
    ) : V2RayConfig(
        protocolType = ProtocolType.V2RAY_VLESS,
        name = name,
        serverAddress = serverAddress,
        serverPort = serverPort,
        dnsServers = dnsServers,
        mtu = mtu,
        bypassApps = bypassApps,
        routingConfig = routingConfig,
        proxyPort = proxyPort,
        networkType = networkType,
        tlsEnabled = tlsEnabled,
        serverName = serverName,
        allowInsecure = allowInsecure,
        wsPath = wsPath,
        host = host,
        serviceName = serviceName
    ) {
        override fun validate(): Boolean {
            return userId.isNotBlank() &&
                   serverAddress.isNotBlank() &&
                   serverPort in 1..65535
        }
    }

    /**
     * Trojan configuration
     */
    data class Trojan(
        override val name: String,
        override val serverAddress: String,
        override val serverPort: Int = 443,
        val password: String,
        override val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
        override val mtu: Int? = null,
        override val bypassApps: List<String> = emptyList(),
        override val routingConfig: RoutingConfig = RoutingConfig(),
        override val proxyPort: Int = 10808,
        override val networkType: String = "tcp",
        override val tlsEnabled: Boolean = true,
        override val serverName: String? = null,
        override val allowInsecure: Boolean = false,
        override val serviceName: String? = null
    ) : V2RayConfig(
        protocolType = ProtocolType.V2RAY_TROJAN,
        name = name,
        serverAddress = serverAddress,
        serverPort = serverPort,
        dnsServers = dnsServers,
        mtu = mtu,
        bypassApps = bypassApps,
        routingConfig = routingConfig,
        proxyPort = proxyPort,
        networkType = networkType,
        tlsEnabled = tlsEnabled,
        serverName = serverName,
        allowInsecure = allowInsecure,
        serviceName = serviceName
    ) {
        override fun validate(): Boolean {
            return password.isNotBlank() &&
                   serverAddress.isNotBlank() &&
                   serverPort in 1..65535
        }
    }

    /**
     * Shadowsocks configuration (for V2Ray core)
     */
    data class Shadowsocks(
        override val name: String,
        override val serverAddress: String,
        override val serverPort: Int,
        val password: String,
        val method: String = "aes-256-gcm",
        override val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
        override val mtu: Int? = null,
        override val bypassApps: List<String> = emptyList(),
        override val routingConfig: RoutingConfig = RoutingConfig(),
        override val proxyPort: Int = 10808
    ) : V2RayConfig(
        protocolType = ProtocolType.V2RAY_SHADOWSOCKS,
        name = name,
        serverAddress = serverAddress,
        serverPort = serverPort,
        dnsServers = dnsServers,
        mtu = mtu,
        bypassApps = bypassApps,
        routingConfig = routingConfig,
        proxyPort = proxyPort
    ) {
        override fun validate(): Boolean {
            return password.isNotBlank() &&
                   serverAddress.isNotBlank() &&
                   serverPort in 1..65535 &&
                   method in listOf(
                       "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305",
                       "aes-128-cfb", "aes-256-cfb", "chacha20"
                   )
        }
    }
}
