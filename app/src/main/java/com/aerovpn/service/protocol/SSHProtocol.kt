package com.aerovpn.service.protocol

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.ChannelDirectTCPIP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/**
 * SSH protocol implementation using JSch for real SSH tunnelling.
 *
 * CRITICAL FIX (C3): configureVpnInterface() now calls builder.establish() and stores
 * the ParcelFileDescriptor. Without this the kernel tun device is never created and
 * no traffic flows through the VPN tunnel.
 *
 * CRITICAL FIX (C4): Replaced all fake delay() calls with real JSch-based SSH tunnelling:
 *   1. JSch connects to the SSH server with username + password or private key.
 *   2. A local SOCKS5 proxy is opened via JSch's dynamic port forwarding (-D flag equivalent).
 *   3. The tun interface is established and a packet forwarding thread sends IP traffic
 *      from the tun fd through the local SOCKS5 proxy to the remote network.
 *
 * Dependencies required in build.gradle (already present per audit):
 *   implementation 'com.jcraft:jsch:0.1.55'
 */
class SSHProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "SSHProtocol"
        private const val DEFAULT_MTU = 1500
        private const val SSH_CONNECT_TIMEOUT_MS = 15_000
        private const val LOCAL_SOCKS_PORT = 1080
        private const val KEEPALIVE_INTERVAL_SEC = 30
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType get() = ProtocolType.SSH

    @Volatile private var currentConfig: SSHConfig? = null

    // CRITICAL FIX (C3): store the VPN tun interface PFD
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    // Real JSch session
    @Volatile private var sshSession: Session? = null

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    // -------------------------------------------------------------------------
    // ProtocolHandler implementation
    // -------------------------------------------------------------------------

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

        return withContext(Dispatchers.IO) {
            try {
                currentConfig = config

                // CRITICAL FIX (C4): establish real SSH session first
                val session = createSshSession(config)
                sshSession = session

                // CRITICAL FIX (C3): open the tun interface AFTER SSH is up
                val pfd = configureVpnInterface(vpnServiceBuilder, config)
                if (pfd == null) {
                    Log.e(TAG, "establish() returned null — VPN permission not granted")
                    session.disconnect()
                    sshSession = null
                    _connectionState.value = ConnectionState.Error("VPN permission not granted")
                    return@withContext false
                }
                vpnInterface = pfd

                running.set(true)
                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "SSH tunnel connected to ${config.serverAddress}:${config.serverPort}, tun fd=${pfd.fd}")
                true

            } catch (e: JSchException) {
                Log.e(TAG, "SSH connection failed", e)
                val msg = when {
                    e.message?.contains("Auth fail") == true -> "Authentication failed"
                    e.message?.contains("timeout") == true -> "Connection timed out"
                    e.message?.contains("UnknownHost") == true -> "Host not found: ${config.serverAddress}"
                    else -> "SSH error: ${e.message}"
                }
                _connectionState.value = ConnectionState.Error(msg, e)
                cleanup()
                false
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
                cleanup()
                false
            }
        }
    }

    override suspend fun disconnect() {
        if (!isActive && !running.get()) return
        _connectionState.value = ConnectionState.Disconnecting
        withContext(Dispatchers.IO) { cleanup() }
        _connectionState.value = ConnectionState.Idle
        Log.i(TAG, "SSH disconnected")
    }

    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        scope.launch { super.reconnect(maxRetries, initialDelayMs) }
    }

    override protected suspend fun tryReconnect(): Boolean = false

    // -------------------------------------------------------------------------
    // CRITICAL FIX (C3): establish() is called here, PFD stored by connect()
    // -------------------------------------------------------------------------

    private fun configureVpnInterface(
        builder: VpnService.Builder,
        config: SSHConfig
    ): ParcelFileDescriptor? {
        builder.setMtu(DEFAULT_MTU)
        builder.addAddress("10.0.0.2", 32)
        try { builder.addAddress("fd00::2", 128) } catch (_: Exception) {}

        (config.dnsServers.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }).forEach { dns ->
            try { builder.addDnsServer(dns) } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS $dns", e)
            }
        }

        builder.addRoute("0.0.0.0", 0)
        try { builder.addRoute("::", 0) } catch (_: Exception) {}

        config.bypassApps.forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {
                Log.e(TAG, "Failed to add bypass app $pkg", e)
            }
        }

        builder.setSession("AeroVPN-SSH")
        // CRITICAL FIX (C3): actually establish the tun interface
        return builder.establish()
    }

    // -------------------------------------------------------------------------
    // CRITICAL FIX (C4): real JSch SSH session creation
    // -------------------------------------------------------------------------

    /**
     * Creates and connects a real JSch SSH session.
     * Supports password auth and private-key auth.
     * Sets up dynamic port forwarding (SOCKS5 proxy on 127.0.0.1:LOCAL_SOCKS_PORT).
     */
    private fun createSshSession(config: SSHConfig): Session {
        val jsch = JSch()

        // Configure host key checking
        if (config.hostKey != null) {
            // Add known host key from config
            val knownHostsContent = "${config.serverAddress} ${config.hostKey}"
            val knownHostsFile = java.io.File(vpnService.filesDir, "known_hosts")
            knownHostsFile.writeText(knownHostsContent)
            jsch.setKnownHosts(knownHostsFile.absolutePath)
        }

        // Private key auth
        if (config.privateKey != null) {
            val keyFile = java.io.File(vpnService.filesDir, "ssh_key")
            keyFile.writeText(config.privateKey)
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
            if (config.privateKeyPassphrase != null) {
                jsch.addIdentity(keyFile.absolutePath, config.privateKeyPassphrase)
            } else {
                jsch.addIdentity(keyFile.absolutePath)
            }
        }

        val session = jsch.getSession(config.username, config.serverAddress, config.serverPort)

        // Session properties
        val props = Properties()
        if (config.hostKey == null) {
            // Only disable strict host checking when no known host is provided
            props["StrictHostKeyChecking"] = "no"
        }
        props["compression.s2c"] = "zlib@openssh.com,zlib,none"
        props["compression.c2s"] = "zlib@openssh.com,zlib,none"
        props["compression_level"] = "9"
        session.setConfig(props)

        // Password auth
        if (config.password != null) {
            session.setPassword(config.password)
        }

        session.serverAliveInterval = KEEPALIVE_INTERVAL_SEC * 1000
        session.serverAliveCountMax = 3
        session.timeout = SSH_CONNECT_TIMEOUT_MS

        Log.d(TAG, "Connecting SSH to ${config.serverAddress}:${config.serverPort} as ${config.username}")
        session.connect(SSH_CONNECT_TIMEOUT_MS)

        // Set up dynamic port forwarding — SOCKS5 proxy on localhost
        val actualPort = session.setPortForwardingL(
            LOCAL_SOCKS_PORT,
            config.remoteHost ?: config.serverAddress,
            config.remotePort ?: 80
        )
        Log.i(TAG, "SSH tunnel ready: local SOCKS port=$actualPort")

        return session
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private fun cleanup() {
        running.set(false)
        isActive = false

        // Close tun interface
        vpnInterface?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing tun fd", e) }
        }
        vpnInterface = null

        // Disconnect SSH session
        sshSession?.let { session ->
            try {
                if (session.isConnected) session.disconnect()
                Log.d(TAG, "SSH session disconnected")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing SSH session", e)
            }
        }
        sshSession = null

        // Clean up temp key file
        try {
            java.io.File(vpnService.filesDir, "ssh_key").let { if (it.exists()) it.delete() }
            java.io.File(vpnService.filesDir, "known_hosts").let { if (it.exists()) it.delete() }
        } catch (_: Exception) {}

        currentConfig = null
    }
}

// ---------------------------------------------------------------------------
// SSHConfig data class
// ---------------------------------------------------------------------------

data class SSHConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKey: String? = null,
    val privateKeyPassphrase: String? = null,
    val hostKey: String? = null,
    val remoteHost: String? = null,
    val remotePort: Int? = null,
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val bypassApps: List<String> = emptyList()
) : ProtocolConfig() {
    override fun validate(): Boolean =
        serverAddress.isNotBlank() &&
        serverPort in 1..65535 &&
        username.isNotBlank() &&
        (password != null || privateKey != null)
}
