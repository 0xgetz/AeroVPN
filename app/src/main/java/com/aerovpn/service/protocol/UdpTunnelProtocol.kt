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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * UDP Tunnel protocol implementation.
 *
 * CRITICAL FIX (C3): configureVpnInterface() now calls builder.establish() and stores
 * the ParcelFileDescriptor. Without this no tun device is created, so the forwarding
 * threads in startForwarding() have no file descriptor to read packets from.
 *
 * MEDIUM FIX: Real bidirectional packet forwarding is implemented:
 *   tun → encrypt → UDP → server (outbound)
 *   server → UDP → decrypt → tun (inbound)
 *
 * MEDIUM FIX: Encryption upgraded from XOR to AES-256-GCM.
 * The XOR cipher provided zero security; AES-256-GCM provides authenticated encryption.
 */
class UdpTunnelProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "UdpTunnelProtocol"
        private const val DEFAULT_MTU = 1400
        private const val MAX_PACKET_SIZE = 65535
        private const val CONNECT_TIMEOUT_MS = 10_000
        // AES-256-GCM constants
        private const val AES_KEY_SIZE = 32       // 256-bit key
        private const val GCM_IV_SIZE = 12        // 96-bit nonce (recommended)
        private const val GCM_TAG_SIZE = 128      // bits
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType get() = ProtocolType.UDP_TUNNEL

    @Volatile private var currentConfig: UdpTunnelConfig? = null

    // CRITICAL FIX (C3): store tun PFD
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private var outboundFuture: Future<*>? = null
    private var inboundFuture: Future<*>? = null

    // UDP socket to the tunnel server
    @Volatile private var udpSocket: DatagramSocket? = null

    // AES-256-GCM session key derived from config
    @Volatile private var aesKey: SecretKeySpec? = null
    private val secureRandom = SecureRandom()

    // -------------------------------------------------------------------------
    // ProtocolHandler implementation
    // -------------------------------------------------------------------------

    override suspend fun connect(
        config: ProtocolConfig,
        vpnServiceBuilder: VpnService.Builder
    ): Boolean {
        if (config !is UdpTunnelConfig) {
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

                // MEDIUM FIX: derive AES-256-GCM key (replaces broken XOR)
                aesKey = deriveAesKey(config.encryptionKey)

                // Open and bind UDP socket to the tunnel server
                val socket = createUdpSocket(config)
                udpSocket = socket

                // CRITICAL FIX (C3): establish the tun interface
                val pfd = configureVpnInterface(vpnServiceBuilder, config)
                if (pfd == null) {
                    Log.e(TAG, "establish() returned null — VPN permission not granted")
                    socket.close()
                    _connectionState.value = ConnectionState.Error("VPN permission not granted")
                    return@withContext false
                }
                vpnInterface = pfd

                // Start real bidirectional forwarding
                running.set(true)
                startForwarding(pfd, socket, config)

                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "UDP tunnel connected to ${config.serverAddress}:${config.serverPort}, tun fd=${pfd.fd}")
                true

            } catch (e: Exception) {
                Log.e(TAG, "UDP tunnel connection error", e)
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
        Log.i(TAG, "UDP tunnel disconnected")
    }

    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        scope.launch { super.reconnect(maxRetries, initialDelayMs) }
    }

    override protected suspend fun tryReconnect(): Boolean = false

    // -------------------------------------------------------------------------
    // CRITICAL FIX (C3): establish() called here, PFD returned to connect()
    // -------------------------------------------------------------------------

    private fun configureVpnInterface(
        builder: VpnService.Builder,
        config: UdpTunnelConfig
    ): ParcelFileDescriptor? {
        builder.setMtu(config.mtu ?: DEFAULT_MTU)
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
                Log.e(TAG, "Failed to bypass app $pkg", e)
            }
        }

        builder.setSession("AeroVPN-UDP")
        // CRITICAL FIX (C3): actually open the tun interface
        return builder.establish()
    }

    // -------------------------------------------------------------------------
    // UDP socket setup
    // -------------------------------------------------------------------------

    private fun createUdpSocket(config: UdpTunnelConfig): DatagramSocket {
        val socket = DatagramSocket()
        socket.soTimeout = 5000

        // Protect the socket so UDP traffic to the server bypasses the tun interface
        // (prevents routing loop)
        if (!vpnService.protect(socket)) {
            Log.w(TAG, "Failed to protect UDP socket — may cause routing loop")
        }

        socket.connect(InetSocketAddress(config.serverAddress, config.serverPort))
        Log.d(TAG, "UDP socket connected to ${config.serverAddress}:${config.serverPort}")
        return socket
    }

    // -------------------------------------------------------------------------
    // MEDIUM FIX: Real bidirectional forwarding (replaces stub/no-op)
    // -------------------------------------------------------------------------

    private fun startForwarding(
        pfd: ParcelFileDescriptor,
        socket: DatagramSocket,
        config: UdpTunnelConfig
    ) {
        val tunIn = FileInputStream(pfd.fileDescriptor)
        val tunOut = FileOutputStream(pfd.fileDescriptor)

        // Outbound: tun → encrypt → UDP → server
        outboundFuture = executor.submit {
            Log.d(TAG, "Outbound forwarding thread started")
            val buf = ByteArray(DEFAULT_MTU + 28)  // IP header overhead
            while (running.get()) {
                try {
                    val len = tunIn.read(buf)
                    if (len <= 0) continue

                    val encrypted = encryptPacket(buf, len)
                    val packet = DatagramPacket(encrypted, encrypted.size)
                    socket.send(packet)
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal timeout — loop
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Outbound error", e)
                }
            }
            Log.d(TAG, "Outbound forwarding thread exited")
        }

        // Inbound: server → UDP → decrypt → tun
        inboundFuture = executor.submit {
            Log.d(TAG, "Inbound forwarding thread started")
            val buf = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    socket.receive(packet)
                    if (packet.length <= 0) continue

                    val decrypted = decryptPacket(buf, packet.length)
                    if (decrypted != null) {
                        tunOut.write(decrypted)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal timeout — loop
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Inbound error", e)
                }
            }
            Log.d(TAG, "Inbound forwarding thread exited")
        }
    }

    // -------------------------------------------------------------------------
    // MEDIUM FIX: AES-256-GCM encryption (replaces XOR cipher)
    // -------------------------------------------------------------------------

    /**
     * Derive a 256-bit AES key from the config key material.
     * Uses SHA-256 to fit arbitrary-length keys to exactly 32 bytes.
     */
    private fun deriveAesKey(keyMaterial: String): SecretKeySpec {
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = sha256.digest(keyMaterial.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypt packet using AES-256-GCM.
     * Output format: [12-byte IV][ciphertext+16-byte GCM tag]
     */
    private fun encryptPacket(data: ByteArray, length: Int): ByteArray {
        val key = aesKey ?: return data.copyOf(length)  // fallback if key not set

        val iv = ByteArray(GCM_IV_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))
        val ciphertext = cipher.doFinal(data, 0, length)

        // Prepend IV to ciphertext
        return iv + ciphertext
    }

    /**
     * Decrypt packet using AES-256-GCM.
     * Input format: [12-byte IV][ciphertext+16-byte GCM tag]
     * Returns null on authentication failure (tampered or invalid packet).
     */
    private fun decryptPacket(data: ByteArray, length: Int): ByteArray? {
        val key = aesKey ?: return data.copyOf(length)

        if (length <= GCM_IV_SIZE) {
            Log.w(TAG, "Packet too short to contain IV ($length bytes)")
            return null
        }

        return try {
            val iv = data.copyOf(GCM_IV_SIZE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))
            cipher.doFinal(data, GCM_IV_SIZE, length - GCM_IV_SIZE)
        } catch (e: javax.crypto.AEADBadTagException) {
            Log.w(TAG, "AES-GCM authentication failed — packet discarded")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private fun cleanup() {
        running.set(false)
        isActive = false

        outboundFuture?.cancel(true)
        inboundFuture?.cancel(true)
        outboundFuture = null
        inboundFuture = null

        udpSocket?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing UDP socket", e) }
        }
        udpSocket = null

        vpnInterface?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing tun fd", e) }
        }
        vpnInterface = null

        aesKey = null
        currentConfig = null
    }
}

// ---------------------------------------------------------------------------
// UdpTunnelConfig data class
// ---------------------------------------------------------------------------

data class UdpTunnelConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int,
    val encryptionKey: String,
    val mtu: Int? = null,
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val bypassApps: List<String> = emptyList()
) : ProtocolConfig() {
    override fun validate(): Boolean =
        serverAddress.isNotBlank() &&
        serverPort in 1..65535 &&
        encryptionKey.isNotBlank()
}
