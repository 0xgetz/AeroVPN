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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Custom UDP tunnel protocol implementation.
 * Provides UDP traffic tunneling with optional encryption.
 */
class UdpTunnelProtocol(
    private val vpnService: VpnService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BaseProtocolHandler() {

    companion object {
        private const val TAG = "UdpTunnelProtocol"
        private const val DEFAULT_MTU = 1500
        private const val UDP_BUFFER_SIZE = 65536
        private const val DEFAULT_TIMEOUT = 30000L
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val protocolType: ProtocolType = ProtocolType.UDP_TUNNEL

    @Volatile
    private var currentConfig: UdpTunnelConfig? = null

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var receiveThread: Thread? = null

    @Volatile
    private var isReceiving = false

    override suspend fun connect(config: ProtocolConfig, vpnServiceBuilder: VpnService.Builder): Boolean {
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

        try {
            currentConfig = config
            
            // Configure VPN interface with UDP support
            configureVpnInterface(vpnServiceBuilder, config)
            
            // Establish UDP tunnel
            val established = establishUdpTunnel(config)
            
            if (established) {
                isActive = true
                connectionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "UDP tunnel established successfully")
                return true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to establish UDP tunnel")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "UDP tunnel connection error", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            isActive = false
            return false
        }
    }

    override suspend fun disconnect() {
        if (!isActive) return
        
        _connectionState.value = ConnectionState.Disconnecting
        
        try {
            // Stop receiving thread
            isReceiving = false
            receiveThread?.interrupt()
            receiveThread?.join(1000)
            
            // Close UDP socket
            udpSocket?.let { socket ->
                if (!socket.isClosed) {
                    socket.close()
                }
            }
            
            isActive = false
            currentConfig = null
            _connectionState.value = ConnectionState.Idle
            Log.i(TAG, "UDP tunnel disconnected")
            
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
     * Configure VPN interface for UDP tunneling
     */
    private fun configureVpnInterface(builder: VpnService.Builder, config: UdpTunnelConfig) {
        // Set MTU (important for UDP to avoid fragmentation)
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
        
        // Allow UDP traffic explicitly
        // Note: VpnService.Builder doesn't have addAllowedPort,
        // but we'll route all UDP through the tunnel
        
        // Add routes based on configuration
        if (config.routeMode == UdpRouteMode.ALL_UDP) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            // Custom routing based on ports or IPs
            config.udpPorts.forEach { port ->
                // Port-based routing would need custom implementation
            }
            
            config.targetIPs.forEach { ip ->
                try {
                    val parts = ip.split("/")
                    if (parts.size == 2) {
                        val address = InetAddress.getByName(parts[0])
                        builder.addRoute(address, parts[1].toInt())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add route: $ip", e)
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
        builder.setSession("AeroVPN-UDP")
    }

    /**
     * Establish UDP tunnel to server
     */
    private suspend fun establishUdpTunnel(config: UdpTunnelConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Create UDP socket
                udpSocket = DatagramSocket()
                udpSocket?.soTimeout = config.timeout.toInt()
                
                // Connect to server
                val serverAddress = InetAddress.getByName(config.serverAddress)
                udpSocket?.connect(serverAddress, config.serverPort)
                
                Log.i(TAG, "UDP socket connected to ${config.serverAddress}:${config.serverPort}")
                
                // Perform handshake if configured
                if (config.useHandshake) {
                    val handshakeSuccess = performHandshake(config)
                    if (!handshakeSuccess) {
                        Log.e(TAG, "UDP handshake failed")
                        return@withContext false
                    }
                }
                
                // Start packet receiver
                isReceiving = true
                receiveThread = Thread {
                    receivePackets(config)
                }
                receiveThread?.start()
                
                Log.i(TAG, "UDP tunnel established, receiver started")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish UDP tunnel", e)
                false
            }
        }
    }

    /**
     * Perform UDP handshake with server
     */
    private suspend fun performHandshake(config: UdpTunnelConfig): Boolean {
        return try {
            // Send handshake packet
            val handshakeData = when {
                config.useEncryption -> {
                    // Encrypted handshake
                    createEncryptedHandshake(config)
                }
                else -> {
                    // Simple handshake
                    "HANDSHAKE:${System.currentTimeMillis()}".toByteArray()
                }
            }
            
            // Send handshake
            val packet = DatagramPacket(
                handshakeData,
                handshakeData.size
            )
            udpSocket?.send(packet)
            
            // Wait for response
            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            udpSocket?.receive(responsePacket)
            
            // Validate response
            val response = String(responsePacket.data, 0, responsePacket.length)
            response.startsWith("ACK")
            
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)
            false
        }
    }

    /**
     * Create encrypted handshake packet
     */
    private fun createEncryptedHandshake(config: UdpTunnelConfig): ByteArray {
        val timestamp = System.currentTimeMillis()
        val data = "HANDSHAKE:$timestamp".toByteArray()
        
        if (config.encryptionKey != null) {
            // In production, use proper encryption
            // For now, simple XOR as placeholder
            val key = config.encryptionKey.toByteArray()
            return data.mapIndexed { index, byte ->
                (byte.toInt() xor key[index % key.size].toInt()).toByte()
            }.toByteArray()
        }
        
        return data
    }

    /**
     * Receive UDP packets from tunnel
     */
    private fun receivePackets(config: UdpTunnelConfig) {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        
        while (isReceiving && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket?.receive(packet)
                
                if (packet.length > 0) {
                    val data = packet.data.copyOf(packet.length)
                    
                    // Decrypt if needed
                    val processedData = if (config.useEncryption && config.encryptionKey != null) {
                        decryptUdpData(data, config.encryptionKey)
                    } else {
                        data
                    }
                    
                    // Update statistics
                    updateTrafficStats(0, processedData.size.toLong())
                    
                    // Forward to VPN interface (in production, would use tun2socks or similar)
                    forwardToVpnInterface(processedData)
                }
                
            } catch (e: Exception) {
                if (isReceiving) {
                    Log.e(TAG, "Error receiving UDP packet", e)
                }
            }
        }
        
        Log.i(TAG, "UDP receiver stopped")
    }

    /**
     * Decrypt UDP data
     */
    private fun decryptUdpData(data: ByteArray, key: String): ByteArray {
        // Simple XOR decryption (production should use proper encryption like AES-GCM)
        val keyBytes = key.toByteArray()
        return data.mapIndexed { index, byte ->
            (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }.toByteArray()
    }

    /**
     * Forward packet to VPN interface
     */
    private fun forwardToVpnInterface(data: ByteArray) {
        // In production, this would write to the VPN file descriptor
        // For now, just log
        Log.d(TAG, "Forwarding ${data.size} bytes to VPN interface")
    }

    /**
     * Send UDP packet through tunnel
     */
    suspend fun sendUdpPacket(data: ByteArray): Boolean {
        return try {
            val config = currentConfig ?: return false
            
            // Encrypt if needed
            val processedData = if (config.useEncryption && config.encryptionKey != null) {
                encryptUdpData(data, config.encryptionKey)
            } else {
                data
            }
            
            // Send through tunnel
            val packet = DatagramPacket(processedData, processedData.size)
            udpSocket?.send(packet)
            
            // Update statistics
            updateTrafficStats(processedData.size.toLong(), 0)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UDP packet", e)
            false
        }
    }

    /**
     * Encrypt UDP data
     */
    private fun encryptUdpData(data: ByteArray, key: String): ByteArray {
        // Simple XOR encryption (production should use proper encryption)
        val keyBytes = key.toByteArray()
        return data.mapIndexed { index, byte ->
            (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }.toByteArray()
    }

    /**
     * Get UDP socket statistics
     */
    fun getUdpStatistics(): UdpStatistics {
        val baseStats = getStatistics()
        return UdpStatistics(
            bytesSent = baseStats.bytesSent,
            bytesReceived = baseStats.bytesReceived,
            duration = baseStats.duration,
            packetsSent = baseStats.packetsSent,
            packetsReceived = baseStats.packetsReceived,
            socketTimeout = currentConfig?.timeout ?: DEFAULT_TIMEOUT,
            bufferSize = UDP_BUFFER_SIZE
        )
    }
}

/**
 * UDP-specific statistics
 */
data class UdpStatistics(
    override val bytesSent: Long = 0L,
    override val bytesReceived: Long = 0L,
    override val duration: Long = 0L,
    override val packetsSent: Long = 0L,
    override val packetsReceived: Long = 0L,
    val socketTimeout: Long = 30000L,
    val bufferSize: Int = 65536,
    val droppedPackets: Long = 0L,
    val outOfOrderPackets: Long = 0L
) : ConnectionStatistics(bytesSent, bytesReceived, 0L, duration, packetsSent, packetsReceived)

/**
 * UDP routing mode
 */
enum class UdpRouteMode {
    ALL_UDP,        // All UDP traffic through tunnel
    SPECIFIC_PORTS, // Only specified UDP ports
    SPECIFIC_IPS    // Only traffic to/from specified IPs
}

/**
 * UDP tunnel configuration
 */
data class UdpTunnelConfig(
    override val name: String,
    override val serverAddress: String,
    override val serverPort: Int,
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val mtu: Int? = null,
    val bypassApps: List<String> = emptyList(),
    val routeMode: UdpRouteMode = UdpRouteMode.ALL_UDP,
    val udpPorts: List<Int> = emptyList(),
    val targetIPs: List<String> = emptyList(),
    val timeout: Long = DEFAULT_TIMEOUT,
    val useEncryption: Boolean = false,
    val encryptionKey: String? = null,
    val useHandshake: Boolean = true,
    val useCompression: Boolean = false,
    val maxRetransmissions: Int = 3,
    val keepAliveInterval: Long = 30000L
) : ProtocolConfig() {

    override fun validate(): Boolean {
        return serverAddress.isNotBlank() &&
               serverPort in 1..65535 &&
               timeout > 0 &&
               (!useEncryption || encryptionKey != null)
    }
    
    /**
     * Check if port should be tunneled
     */
    fun shouldTunnelPort(port: Int): Boolean {
        return when (routeMode) {
            UdpRouteMode.ALL_UDP -> true
            UdpRouteMode.SPECIFIC_PORTS -> port in udpPorts
            UdpRouteMode.SPECIFIC_IPS -> false // IP-based, not port-based
        }
    }
}
