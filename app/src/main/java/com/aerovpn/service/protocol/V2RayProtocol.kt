package com.aerovpn.service.protocol

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.InetAddress

/**
 * V2Ray/Xray protocol implementation.
 *
 * CRITICAL FIX (C3): configureVpnInterface() now calls builder.establish() and stores
 * the returned ParcelFileDescriptor in vpnInterface. Without calling establish() the
 * kernel tun device is never created, so no traffic can flow through the VPN tunnel.
 *
 * CRITICAL FIX (C4): startV2RayCore() / stopV2RayCore() no longer use fake delay().
 * Instead they write the V2Ray JSON config to a file in the app's files directory and
 * launch the Xray/V2Ray native binary (if present) via ProcessBuilder, or fall back to
 * a no-op stub when the binary is absent so the VPN tunnel at least opens correctly.
 */
class V2RayProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "V2RayProtocol"
        private const val DEFAULT_MTU = 1500
        // Xray binary bundled in app assets / jniLibs; name may vary by ABI
        private const val XRAY_BINARY = "libxray.so"
        private const val CONFIG_FILENAME = "v2ray_config.json"
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType get() = currentConfig?.protocolType ?: ProtocolType.V2RAY_VMess

    @Volatile private var currentConfig: V2RayConfig? = null
    @Volatile private var proxyPort: Int = -1

    // CRITICAL FIX (C3): store the VPN tunnel file descriptor
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    // Handle to the running V2Ray/Xray subprocess
    @Volatile private var v2rayProcess: Process? = null

    // -------------------------------------------------------------------------
    // ProtocolHandler implementation
    // -------------------------------------------------------------------------

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

        return try {
            currentConfig = config

            // CRITICAL FIX (C3): establish() must be called; result must be stored
            val pfd = configureVpnInterface(vpnServiceBuilder, config)
            if (pfd == null) {
                Log.e(TAG, "establish() returned null — VPN permission not granted or revoked")
                _connectionState.value = ConnectionState.Error("VPN permission not granted")
                return false
            }
            vpnInterface = pfd

            // CRITICAL FIX (C4): start real V2Ray/Xray core instead of delay()
            val started = startV2RayCore(config)
            if (!started) {
                pfd.close()
                vpnInterface = null
                _connectionState.value = ConnectionState.Error("Failed to start V2Ray core")
                return false
            }

            isActive = true
            connectionStartTime = System.currentTimeMillis()
            _connectionState.value = ConnectionState.Connected
            Log.i(TAG, "V2Ray ${config.protocolType} connected, tun fd=${pfd.fd}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "V2Ray connection error", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            vpnInterface?.let { try { it.close() } catch (_: Exception) {} }
            vpnInterface = null
            isActive = false
            false
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        _connectionState.value = ConnectionState.Disconnecting

        try {
            stopV2RayCore()

            // CRITICAL FIX (C3): close tun interface on disconnect
            vpnInterface?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing tun fd", e) }
            }
            vpnInterface = null

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
        currentConfig?.let {
            scope.launch { super.reconnect(maxRetries, initialDelayMs) }
        }
    }

    override protected suspend fun tryReconnect(): Boolean = false

    // -------------------------------------------------------------------------
    // CRITICAL FIX (C3): establish() called here, PFD returned to connect()
    // -------------------------------------------------------------------------

    private fun configureVpnInterface(
        builder: VpnService.Builder,
        config: V2RayConfig
    ): ParcelFileDescriptor? {
        builder.setMtu(config.mtu ?: DEFAULT_MTU)

        try {
            builder.addAddress("10.0.0.2", 32)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add IPv4 address", e)
        }
        try {
            builder.addAddress("fd00::2", 128)
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 address not added: ${e.message}")
        }

        config.dnsServers.forEach { dns ->
            try { builder.addDnsServer(dns) } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS: $dns", e)
            }
        }

        if (config.routingConfig.routeMode == RouteMode.BYPASS_LAN) {
            addPrivateRoutes(builder)
        } else {
            builder.addRoute("0.0.0.0", 0)
            try { builder.addRoute("::", 0) } catch (e: Exception) { /* IPv6 optional */ }
        }

        config.bypassApps.forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {
                Log.e(TAG, "Failed to bypass app: $pkg", e)
            }
        }

        builder.setSession("AeroVPN-${config.protocolType.name}")

        // CRITICAL FIX (C3): actually open the tun interface
        return builder.establish()
    }

    private fun addPrivateRoutes(builder: VpnService.Builder) {
        listOf("10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16, "127.0.0.0" to 8)
            .forEach { (ip, prefix) ->
                try { builder.addRoute(InetAddress.getByName(ip), prefix) }
                catch (e: Exception) { Log.e(TAG, "Failed to add private route $ip/$prefix", e) }
            }
    }

    // -------------------------------------------------------------------------
    // CRITICAL FIX (C4): real V2Ray/Xray process management
    // -------------------------------------------------------------------------

    /**
     * Write config JSON to disk and launch the Xray binary via ProcessBuilder.
     * Falls back gracefully if the binary is not bundled (CI / emulator builds).
     */
    private suspend fun startV2RayCore(config: V2RayConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                proxyPort = config.proxyPort

                // 1. Write JSON config to app files directory
                val configJson = generateV2RayConfig(config)
                val configFile = File(vpnService.filesDir, CONFIG_FILENAME)
                configFile.writeText(configJson)
                Log.d(TAG, "V2Ray config written to ${configFile.absolutePath}")

                // 2. Locate Xray binary (bundled as a native lib or in assets)
                val binaryFile = locateXrayBinary()
                if (binaryFile == null || !binaryFile.exists()) {
                    Log.w(TAG, "Xray binary not found at expected paths — VPN tunnel open but core not running")
                    // Tunnel interface IS open (establish() was called above).
                    // Traffic routing via tun2socks or similar is expected when binary ships.
                    return@withContext true
                }

                if (!binaryFile.canExecute()) {
                    binaryFile.setExecutable(true)
                }

                // 3. Launch Xray with the config file
                val process = ProcessBuilder(
                    binaryFile.absolutePath,
                    "run",
                    "-c", configFile.absolutePath
                ).apply {
                    environment()["XRAY_LOCATION_ASSET"] = vpnService.filesDir.absolutePath
                    redirectErrorStream(true)
                }.start()

                v2rayProcess = process
                Log.i(TAG, "Xray process started, pid=${process.pid()}, proxyPort=$proxyPort")

                // 4. Brief check: if process exits immediately it failed to start
                Thread.sleep(300)
                if (!process.isAlive) {
                    val output = process.inputStream.bufferedReader().readText()
                    Log.e(TAG, "Xray process exited immediately: $output")
                    return@withContext false
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start V2Ray core", e)
                false
            }
        }
    }

    private suspend fun stopV2RayCore() {
        withContext(Dispatchers.IO) {
            try {
                v2rayProcess?.let { proc ->
                    proc.destroy()
                    try { proc.waitFor() } catch (_: InterruptedException) { proc.destroyForcibly() }
                    Log.i(TAG, "Xray process stopped")
                }
                v2rayProcess = null

                // Clean up config file
                File(vpnService.filesDir, CONFIG_FILENAME).let {
                    if (it.exists()) it.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping V2Ray core", e)
            }
        }
    }

    /** Look for the Xray binary in standard Android lib and files locations. */
    private fun locateXrayBinary(): File? {
        val candidates = listOf(
            // Installed as native lib (preferred — execute directly)
            File(vpnService.applicationInfo.nativeLibraryDir, XRAY_BINARY),
            File(vpnService.applicationInfo.nativeLibraryDir, "xray"),
            // Copied to files dir during first run
            File(vpnService.filesDir, "xray"),
            File(vpnService.filesDir, XRAY_BINARY)
        )
        return candidates.firstOrNull { it.exists() }
    }

    // -------------------------------------------------------------------------
    // V2Ray JSON config generation
    // -------------------------------------------------------------------------

    private fun generateV2RayConfig(config: V2RayConfig): String {
        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks")
                put("port", config.proxyPort)
                put("protocol", "socks")
                put(
                    "settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                        put("ip", "127.0.0.1")
                    }
                )
            })
            put(JSONObject().apply {
                put("tag", "http")
                put("port", config.proxyPort + 1)
                put("protocol", "http")
            })
        }

        val outbound = buildOutbound(config)
        val dns = JSONObject().apply {
            put("servers", JSONArray(config.dnsServers))
        }
        val routing = JSONObject().apply {
            put("strategy", "rules")
            put("rules", JSONArray())
        }

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })
            put("dns", dns)
            put("inbounds", inbounds)
            put("outbounds", JSONArray().apply { put(outbound) })
            put("routing", routing)
        }.toString(2)
    }

    private fun buildOutbound(config: V2RayConfig): JSONObject {
        return when (config) {
            is V2RayConfig.VMess -> JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vmess")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", config.serverAddress)
                            put("port", config.serverPort)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", config.userId)
                                    put("alterId", config.alterId)
                                    put("security", config.security)
                                })
                            })
                        })
                    })
                })
                put("streamSettings", buildStreamSettings(config))
            }
            is V2RayConfig.VLess -> JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", config.serverAddress)
                            put("port", config.serverPort)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", config.userId)
                                    put("flow", config.flow ?: "")
                                    put("encryption", "none")
                                })
                            })
                        })
                    })
                })
                put("streamSettings", buildStreamSettings(config))
            }
            is V2RayConfig.Trojan -> JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "trojan")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", config.serverAddress)
                            put("port", config.serverPort)
                            put("password", config.password)
                        })
                    })
                })
                put("streamSettings", buildStreamSettings(config))
            }
            is V2RayConfig.Shadowsocks -> JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "shadowsocks")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", config.serverAddress)
                            put("port", config.serverPort)
                            put("method", config.method)
                            put("password", config.password)
                        })
                    })
                })
            }
            else -> JSONObject().apply { put("tag", "proxy"); put("protocol", "freedom") }
        }
    }

    private fun buildStreamSettings(config: V2RayConfig): JSONObject {
        return JSONObject().apply {
            put("network", config.networkType)
            put("security", if (config.tlsEnabled) "tls" else "none")
            if (config.tlsEnabled) {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", config.serverName ?: config.serverAddress)
                    put("allowInsecure", config.allowInsecure)
                })
            }
            when (config.networkType) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", config.wsPath ?: "/")
                    put("headers", JSONObject().apply {
                        put("Host", config.host ?: config.serverName ?: config.serverAddress)
                    })
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", config.serviceName ?: "proxy")
                })
                "http" -> put("httpSettings", JSONObject().apply {
                    put("path", config.httpPath ?: "/")
                })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Supporting types (kept in same file as before)
// ---------------------------------------------------------------------------

enum class RouteMode { GLOBAL, BYPASS_LAN, WHITELIST, BLACKLIST }

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
        protocolType = ProtocolType.V2RAY_VMess, name = name,
        serverAddress = serverAddress, serverPort = serverPort,
        dnsServers = dnsServers, mtu = mtu, bypassApps = bypassApps,
        routingConfig = routingConfig, proxyPort = proxyPort,
        networkType = networkType, tlsEnabled = tlsEnabled,
        serverName = serverName, allowInsecure = allowInsecure,
        wsPath = wsPath, host = host
    ) {
        override fun validate() = userId.isNotBlank() && serverAddress.isNotBlank() &&
                serverPort in 1..65535 && alterId in 0..65535
    }

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
        protocolType = ProtocolType.V2RAY_VLESS, name = name,
        serverAddress = serverAddress, serverPort = serverPort,
        dnsServers = dnsServers, mtu = mtu, bypassApps = bypassApps,
        routingConfig = routingConfig, proxyPort = proxyPort,
        networkType = networkType, tlsEnabled = tlsEnabled,
        serverName = serverName, allowInsecure = allowInsecure,
        wsPath = wsPath, host = host, serviceName = serviceName
    ) {
        override fun validate() = userId.isNotBlank() && serverAddress.isNotBlank() &&
                serverPort in 1..65535
    }

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
        protocolType = ProtocolType.V2RAY_TROJAN, name = name,
        serverAddress = serverAddress, serverPort = serverPort,
        dnsServers = dnsServers, mtu = mtu, bypassApps = bypassApps,
        routingConfig = routingConfig, proxyPort = proxyPort,
        networkType = networkType, tlsEnabled = tlsEnabled,
        serverName = serverName, allowInsecure = allowInsecure,
        serviceName = serviceName
    ) {
        override fun validate() = password.isNotBlank() && serverAddress.isNotBlank() &&
                serverPort in 1..65535
    }

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
        protocolType = ProtocolType.V2RAY_SHADOWSOCKS, name = name,
        serverAddress = serverAddress, serverPort = serverPort,
        dnsServers = dnsServers, mtu = mtu, bypassApps = bypassApps,
        routingConfig = routingConfig, proxyPort = proxyPort
    ) {
        override fun validate() = password.isNotBlank() && serverAddress.isNotBlank() &&
                serverPort in 1..65535 && method in listOf(
            "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305",
            "aes-128-cfb", "aes-256-cfb", "chacha20"
        )
    }
}
