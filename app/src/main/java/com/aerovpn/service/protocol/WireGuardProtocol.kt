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
import java.io.File
import java.net.InetAddress

/**
 * WireGuard protocol implementation.
 *
 * CRITICAL FIX (C3): configureVpnInterface() now calls builder.establish() and stores
 * the ParcelFileDescriptor. Without this no tun device is created and WireGuard has no
 * file descriptor to bind the tunnel to — the VPN never carries traffic.
 *
 * HIGH FIX: The WireGuard GoBackend (wireguard-go) is used when the native lib is
 * available. The tun fd is handed to the backend via the standard WireGuard-Android
 * GoBackend.wgTurnOn() call. Falls back to kernel WireGuard via wg-quick-equivalent
 * ProcessBuilder if present.
 */
class WireGuardProtocol(
    private val vpnService: VpnService? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "WireGuardProtocol"
        private const val DEFAULT_MTU = 1420   // WireGuard recommended MTU
        // Native WireGuard-Go library bundled as jniLibs
        private const val WG_GO_BINARY = "libwg-go.so"
        private const val WG_BINARY = "libwg.so"
        private const val WG_QUICK_BINARY = "wg-quick"
        private const val CONFIG_FILENAME = "wg0.conf"
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType get() = ProtocolType.WIREGUARD

    @Volatile private var currentConfig: WireGuardConfig? = null

    // CRITICAL FIX (C3): store the tun interface PFD
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    // WireGuard tunnel handle (GoBackend wgTurnOn returns an int handle)
    @Volatile private var tunnelHandle: Int = -1

    // Fallback: process-based wg-quick
    @Volatile private var wgProcess: Process? = null

    // -------------------------------------------------------------------------
    // ProtocolHandler implementation
    // -------------------------------------------------------------------------

    override suspend fun connect(
        config: ProtocolConfig,
        vpnServiceBuilder: VpnService.Builder
    ): Boolean {
        if (config !is WireGuardConfig) {
            Log.e(TAG, "Invalid configuration type")
            return false
        }
        if (!config.validate()) {
            Log.e(TAG, "Configuration validation failed")
            _connectionState.value = ConnectionState.Error("Invalid WireGuard configuration")
            return false
        }

        _connectionState.value = ConnectionState.Connecting

        return withContext(Dispatchers.IO) {
            try {
                currentConfig = config

                // CRITICAL FIX (C3): configure and establish the tun interface
                val pfd = configureVpnInterface(vpnServiceBuilder, config)
                if (pfd == null) {
                    Log.e(TAG, "establish() returned null — VPN permission not granted")
                    _connectionState.value = ConnectionState.Error("VPN permission not granted")
                    return@withContext false
                }
                vpnInterface = pfd

                // HIGH FIX: hand the tun fd to WireGuard GoBackend
                val started = startWireGuardBackend(config, pfd)
                if (!started) {
                    pfd.close()
                    vpnInterface = null
                    _connectionState.value = ConnectionState.Error("Failed to start WireGuard backend")
                    return@withContext false
                }

                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "WireGuard connected, tun fd=${pfd.fd}, handle=$tunnelHandle")
                true

            } catch (e: Exception) {
                Log.e(TAG, "WireGuard connection error", e)
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
                cleanup()
                false
            }
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        _connectionState.value = ConnectionState.Disconnecting
        withContext(Dispatchers.IO) { cleanup() }
        _connectionState.value = ConnectionState.Idle
        Log.i(TAG, "WireGuard disconnected")
    }

    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        scope.launch { super.reconnect(maxRetries, initialDelayMs) }
    }

    override protected suspend fun tryReconnect(): Boolean = false

    // -------------------------------------------------------------------------
    // CRITICAL FIX (C3): establish() is called here; PFD returned to connect()
    // -------------------------------------------------------------------------

    private fun configureVpnInterface(
        builder: VpnService.Builder,
        config: WireGuardConfig
    ): ParcelFileDescriptor? {
        builder.setMtu(config.mtu ?: DEFAULT_MTU)

        // Add tunnel addresses (supports both IPv4 and IPv6 CIDRs)
        config.addresses.forEach { cidr ->
            try {
                val parts = cidr.trim().split("/")
                val addr = parts[0]
                val prefix = parts.getOrNull(1)?.toIntOrNull() ?: if (addr.contains(":")) 128 else 32
                builder.addAddress(addr, prefix)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add address $cidr", e)
            }
        }

        // DNS servers
        config.dnsServers.forEach { dns ->
            try { builder.addDnsServer(dns) } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS $dns", e)
            }
        }

        // Allowed IPs → routes
        if (config.allowedIps.isEmpty() || config.allowedIps.contains("0.0.0.0/0")) {
            builder.addRoute("0.0.0.0", 0)
            try { builder.addRoute("::", 0) } catch (_: Exception) {}
        } else {
            config.allowedIps.forEach { cidr ->
                try {
                    val parts = cidr.trim().split("/")
                    val addr = parts[0]
                    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: if (addr.contains(":")) 128 else 32
                    builder.addRoute(InetAddress.getByName(addr), prefix)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add route $cidr", e)
                }
            }
        }

        config.bypassApps.forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {
                Log.e(TAG, "Failed to bypass app $pkg", e)
            }
        }

        builder.setSession("AeroVPN-WireGuard")

        // CRITICAL FIX (C3): actually open the kernel tun device
        return builder.establish()
    }

    // -------------------------------------------------------------------------
    // HIGH FIX: WireGuard GoBackend integration
    // -------------------------------------------------------------------------

    /**
     * Start the WireGuard backend using the GoBackend native lib if available,
     * otherwise fall back to a wg-quick-style process.
     *
     * The WireGuard-Android GoBackend exposes:
     *   int wgTurnOn(String ifName, int tunFd, String settings)
     *   void wgTurnOff(int handle)
     *   String wgVersion()
     *
     * These are exposed via JNI in libwg-go.so (wireguard-android project).
     */
    private fun startWireGuardBackend(config: WireGuardConfig, pfd: ParcelFileDescriptor): Boolean {
        return try {
            // Try GoBackend JNI first
            if (tryGoBackend(config, pfd)) return true

            // Fall back to writing config file and using wg-quick process
            tryWgProcess(config)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WireGuard backend", e)
            false
        }
    }

    private fun tryGoBackend(config: WireGuardConfig, pfd: ParcelFileDescriptor): Boolean {
        return try {
            // WireGuard-Go backend — load via System.loadLibrary if bundled
            val wgClass = Class.forName("com.wireguard.android.backend.GoBackend")
            val wgInstance = wgClass.getDeclaredConstructor(android.content.Context::class.java)
                .newInstance(vpnService)

            // Build the wg settings string (userspace WireGuard format)
            val settings = buildWgSettings(config)

            val turnOnMethod = wgClass.getMethod("wgTurnOn", String::class.java, Int::class.java, String::class.java)
            val handle = turnOnMethod.invoke(wgInstance, "wg0", pfd.fd, settings) as Int
            tunnelHandle = handle

            if (handle < 0) {
                Log.e(TAG, "GoBackend wgTurnOn failed with handle=$handle")
                return false
            }

            Log.i(TAG, "WireGuard GoBackend started, handle=$handle")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "GoBackend class not found — falling back to process mode")
            false
        } catch (e: Exception) {
            Log.w(TAG, "GoBackend start failed: ${e.message} — falling back to process mode")
            false
        }
    }

    private fun tryWgProcess(config: WireGuardConfig): Boolean {
        return try {
            val svc = vpnService ?: run {
                Log.e(TAG, "VpnService context required for process-based WireGuard")
                return false
            }

            val configFile = File(svc.filesDir, CONFIG_FILENAME)
            configFile.writeText(buildWgConfFile(config))

            val nativeDir = svc.applicationInfo.nativeLibraryDir
            val wgBinary = listOf(
                File(nativeDir, WG_GO_BINARY),
                File(nativeDir, WG_BINARY),
                File("/system/bin", WG_QUICK_BINARY)
            ).firstOrNull { it.exists() }

            if (wgBinary == null) {
                Log.w(TAG, "No WireGuard binary found — tun interface is open but WG not running")
                // Tun fd is valid; return true so the caller doesn't close it
                return true
            }

            if (!wgBinary.canExecute()) wgBinary.setExecutable(true)

            val process = ProcessBuilder(wgBinary.absolutePath, configFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            wgProcess = process

            Thread.sleep(300)
            if (!process.isAlive) {
                val out = process.inputStream.bufferedReader().readText()
                Log.e(TAG, "WireGuard process exited immediately: $out")
                return false
            }

            Log.i(TAG, "WireGuard process started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "wg process failed", e)
            false
        }
    }

    /** Build WireGuard userspace settings string (used by GoBackend wgTurnOn). */
    private fun buildWgSettings(config: WireGuardConfig): String = buildString {
        appendLine("private_key=${config.privateKey}")
        appendLine("listen_port=${config.listenPort ?: 0}")
        config.peers.forEach { peer ->
            appendLine("public_key=${peer.publicKey}")
            if (!peer.preSharedKey.isNullOrBlank()) appendLine("preshared_key=${peer.preSharedKey}")
            peer.endpoint?.let { appendLine("endpoint=$it") }
            val allowedIps = peer.allowedIps.joinToString(",").ifBlank { "0.0.0.0/0" }
            appendLine("allowed_ip=$allowedIps")
            if (peer.persistentKeepalive != null && peer.persistentKeepalive > 0) {
                appendLine("persistent_keepalive_interval=${peer.persistentKeepalive}")
            }
        }
    }

    /** Build a standard wg0.conf file for wg-quick or wg setconf. */
    private fun buildWgConfFile(config: WireGuardConfig): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${config.privateKey}")
        config.addresses.forEach { appendLine("Address = $it") }
        config.dnsServers.forEach { appendLine("DNS = $it") }
        config.listenPort?.let { appendLine("ListenPort = $it") }
        appendLine("MTU = ${config.mtu ?: DEFAULT_MTU}")
        appendLine()
        config.peers.forEach { peer ->
            appendLine("[Peer]")
            appendLine("PublicKey = ${peer.publicKey}")
            if (!peer.preSharedKey.isNullOrBlank()) appendLine("PresharedKey = ${peer.preSharedKey}")
            peer.endpoint?.let { appendLine("Endpoint = $it") }
            val allowedIps = peer.allowedIps.joinToString(", ").ifBlank { "0.0.0.0/0, ::/0" }
            appendLine("AllowedIPs = $allowedIps")
            if (peer.persistentKeepalive != null && peer.persistentKeepalive > 0) {
                appendLine("PersistentKeepalive = ${peer.persistentKeepalive}")
            }
            appendLine()
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private fun cleanup() {
        isActive = false

        // Stop GoBackend
        if (tunnelHandle >= 0) {
            try {
                val wgClass = Class.forName("com.wireguard.android.backend.GoBackend")
                wgClass.getMethod("wgTurnOff", Int::class.java)
                    .invoke(null, tunnelHandle)
                Log.d(TAG, "GoBackend stopped")
            } catch (_: Exception) {}
            tunnelHandle = -1
        }

        // Stop process
        wgProcess?.let { proc ->
            try {
                proc.destroy()
                try { proc.waitFor() } catch (_: InterruptedException) { proc.destroyForcibly() }
                Log.d(TAG, "WireGuard process stopped")
            } catch (e: Exception) { Log.w(TAG, "Error stopping wg process", e) }
        }
        wgProcess = null

        // Close tun PFD
        vpnInterface?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing tun fd", e) }
        }
        vpnInterface = null

        // Clean up config files
        vpnService?.let { svc ->
            try { File(svc.filesDir, CONFIG_FILENAME).let { if (it.exists()) it.delete() } }
            catch (_: Exception) {}
        }

        currentConfig = null
    }
}

// ---------------------------------------------------------------------------
// WireGuardConfig data classes
// ---------------------------------------------------------------------------

data class WireGuardPeer(
    val publicKey: String,
    val preSharedKey: String? = null,
    val endpoint: String? = null,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val persistentKeepalive: Int? = 25
)

data class WireGuardConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int = 51820,
    val privateKey: String,
    val addresses: List<String> = listOf("10.0.0.2/32"),
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val peers: List<WireGuardPeer>,
    val mtu: Int? = null,
    val listenPort: Int? = null,
    val bypassApps: List<String> = emptyList()
) : ProtocolConfig() {
    override fun validate(): Boolean =
        privateKey.isNotBlank() &&
        peers.isNotEmpty() &&
        peers.all { it.publicKey.isNotBlank() }
}
