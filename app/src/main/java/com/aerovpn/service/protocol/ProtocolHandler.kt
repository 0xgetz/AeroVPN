package com.aerovpn.service.protocol

import android.net.VpnService
import kotlinx.coroutines.flow.StateFlow

/**
 * Base interface for all VPN protocol implementations.
 * Each protocol (WireGuard, V2Ray, SSH, Shadowsocks, UDP Tunnel) implements this interface.
 */
interface ProtocolHandler {
    
    /**
     * Protocol type identifier
     */
    val protocolType: ProtocolType
    
    /**
     * Current connection state
     */
    val connectionState: StateFlow<ConnectionState>
    
    /**
     * Establish VPN connection with the given configuration
     * @param config Protocol-specific configuration
     * @param vpnService Builder for configuring VPN interface
     * @return true if connection established successfully
     */
    suspend fun connect(config: ProtocolConfig, vpnService: VpnService.Builder): Boolean
    
    /**
     * Gracefully disconnect VPN
     */
    suspend fun disconnect()
    
    /**
     * Check if connection is active
     */
    fun isConnected(): Boolean
    
    /**
     * Get connection statistics (bytes sent/received, duration, etc.)
     */
    fun getStatistics(): ConnectionStatistics
    
    /**
     * Reconnect with exponential backoff
     * @param maxRetries Maximum number of reconnection attempts
     * @param initialDelayMs Initial delay before first retry
     */
    suspend fun reconnect(maxRetries: Int = 3, initialDelayMs: Long = 1000L)
}

/**
 * Supported VPN protocol types
 */
enum class ProtocolType {
    WIREGUARD,
    V2RAY_VMess,
    V2RAY_VLESS,
    V2RAY_TROJAN,
    V2RAY_SHADOWSOCKS,
    SSH,
    SHADOWSOCKS,
    UDP_TUNNEL
}

/**
 * Connection state representation
 */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Disconnecting : ConnectionState()
    data class Error(val message: String, val throwable: Throwable? = null) : ConnectionState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState()
}

/**
 * Connection statistics data class
 */
data class ConnectionStatistics(
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val startTime: Long = 0L,
    val duration: Long = 0L,
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L
)

/**
 * Base class for protocol-specific configurations
 */
abstract class ProtocolConfig {
    abstract val serverAddress: String
    abstract val serverPort: Int
    abstract val name: String
    
    /**
     * Validate configuration parameters
     * @return true if configuration is valid
     */
    abstract fun validate(): Boolean
}

/**
 * Base implementation with common functionality
 */
abstract class BaseProtocolHandler : ProtocolHandler {
    
    @Volatile
    protected var isActive = false
    
    @Volatile
    protected var connectionStartTime = 0L
    
    @Volatile
    protected var bytesSent = 0L
    
    @Volatile
    protected var bytesReceived = 0L
    
    override fun isConnected(): Boolean = isActive
    
    override fun getStatistics(): ConnectionStatistics {
        val duration = if (connectionStartTime > 0) {
            System.currentTimeMillis() - connectionStartTime
        } else 0L
        
        return ConnectionStatistics(
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            startTime = connectionStartTime,
            duration = duration,
            packetsSent = 0L, // Implement in concrete classes
            packetsReceived = 0L
        )
    }
    
    override suspend fun reconnect(maxRetries: Int, initialDelayMs: Long) {
        var delay = initialDelayMs
        
        for (attempt in 1..maxRetries) {
            if (!isActive) return
            
            // Emit reconnecting state (implement in concrete class)
            disconnect()
            
            kotlinx.coroutines.delay(delay)
            
            // Try to reconnect (implement in concrete class)
            val reconnected = tryReconnect()
            
            if (reconnected) {
                return
            }
            
            // Exponential backoff
            delay *= 2
        }
        
        // All retries exhausted
        isActive = false
    }
    
    /**
     * Attempt to reconnect - implement in concrete classes
     * @return true if reconnection successful
     */
    protected abstract suspend fun tryReconnect(): Boolean
    
    /**
     * Update traffic statistics
     * @param sent Bytes sent
     * @param received Bytes received
     */
    protected fun updateTrafficStats(sent: Long, received: Long) {
        bytesSent += sent
        bytesReceived += received
    }
}
