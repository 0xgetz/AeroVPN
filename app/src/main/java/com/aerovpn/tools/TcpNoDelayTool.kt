package com.aerovpn.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.net.Socket

/**
 * TCP optimization configuration
 */
data class TcpConfig(
    val tcpNoDelay: Boolean = true,
    val soTimeout: Int = 30000,
    val sendBufferSize: Int? = null,
    val receiveBufferSize: Int? = null,
    val keepAlive: Boolean = true,
    val connectionTimeout: Int = 10000
)

/**
 * Network quality metrics
 */
data class NetworkQuality(
    val type: NetworkType,
    val bandwidth: BandwidthEstimate,
    val latency: LatencyEstimate,
    val stability: StabilityScore,
    val recommended: TcpConfig
)

enum class NetworkType {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_3G,
    CELLULAR_2G,
    ETHERNET,
    UNKNOWN
}

enum class BandwidthEstimate {
    EXCELLENT,    // > 100 Mbps
    GOOD,         // 50-100 Mbps
    FAIR,         // 10-50 Mbps
    POOR,         // 1-10 Mbps
    VERY_POOR     // < 1 Mbps
}

enum class LatencyEstimate {
    EXCELLENT,    // < 50ms
    GOOD,         // 50-100ms
    FAIR,         // 100-200ms
    POOR,         // 200-500ms
    VERY_POOR     // > 500ms
}

enum class StabilityScore {
    HIGH,
    MEDIUM,
    LOW,
    UNSTABLE
}

/**
 * TcpNoDelayTool - TCP optimization settings
 * Configure TCP socket options for better VPN performance
 */
object TcpNoDelayTool {

    /**
     * Apply TCP optimizations to a socket
     */
    suspend fun applyTcpOptimizations(
        socket: Socket,
        config: TcpConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = config.tcpNoDelay
            socket.soTimeout = config.soTimeout
            socket.keepAlive = config.keepAlive
            
            config.sendBufferSize?.let { socket.sendBufferSize = it }
            config.receiveBufferSize?.let { socket.receiveBufferSize = it }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get recommended TCP config for current network
     */
    suspend fun getRecommendedConfig(context: Context): Result<TcpConfig> = withContext(Dispatchers.IO) {
        try {
            val networkQuality = assessNetworkQuality(context)
            val config = generateTcpConfig(networkQuality)
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Assess current network quality
     */
    suspend fun assessNetworkQuality(context: Context): NetworkQuality = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkType.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                when (capabilities.linkDownstreamBandwidthKbps) {
                    in 0..500 -> NetworkType.CELLULAR_2G
                    in 501..2000 -> NetworkType.CELLULAR_3G
                    in 2001..10000 -> NetworkType.CELLULAR_4G
                    else -> NetworkType.CELLULAR_5G
                }
            }
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
        
        val bandwidth = estimateBandwidth(capabilities?.linkDownstreamBandwidthKbps ?: 0)
        val latency = estimateLatency(networkType)
        val stability = estimateStability(networkType)
        
        NetworkQuality(
            type = networkType,
            bandwidth = bandwidth,
            latency = latency,
            stability = stability,
            recommended = generateTcpConfig(
                NetworkQuality(networkType, bandwidth, latency, stability, TcpConfig())
            )
        )
    }

    /**
     * Monitor network changes
     */
    fun monitorNetworkChanges(context: Context): Flow<NetworkQuality> = callbackFlow {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val quality = assessNetworkQualityForCapabilities(capabilities)
                trySend(quality)
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val quality = assessNetworkQualityForCapabilities(networkCapabilities)
                trySend(quality)
            }
            
            override fun onLost(network: Network) {
                trySend(
                    NetworkQuality(
                        type = NetworkType.UNKNOWN,
                        bandwidth = BandwidthEstimate.VERY_POOR,
                        latency = LatencyEstimate.VERY_POOR,
                        stability = StabilityScore.UNSTABLE,
                        recommended = TcpConfig()
                    )
                )
            }
        }
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, callback)
        
        // Send initial assessment
        val initialQuality = assessNetworkQuality(context)
        trySend(initialQuality)
        
        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Test TCP connection speed
     */
    suspend fun testTcpSpeed(
        host: String,
        port: Int,
        timeout: Int = 10000
    ): TcpSpeedTestResult = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            
            // Test with default config
            val connectStart = System.currentTimeMillis()
            socket.connect(java.net.InetSocketAddress(host, port), timeout)
            val connectTime = System.currentTimeMillis() - connectStart
            
            // Test with TCP_NODELAY enabled
            socket.tcpNoDelay = true
            val startTime = System.currentTimeMillis()
            val data = ByteArray(10240) { 0 }
            socket.outputStream.write(data)
            socket.outputStream.flush()
            val throughputTime = System.currentTimeMillis() - startTime
            
            val throughputKbps = (data.size * 8) / throughputTime.toDouble()
            
            socket.close()
            
            TcpSpeedTestResult(
                connectionTime = connectTime,
                throughputKbps = throughputKbps,
                packetLoss = 0.0,
                jitter = 0
            )
        } catch (e: Exception) {
            TcpSpeedTestResult(
                connectionTime = -1,
                throughputKbps = 0.0,
                packetLoss = 100.0,
                jitter = -1,
                error = e.message
            )
        }
    }

    private fun estimateBandwidth(downstreamKbps: Int): BandwidthEstimate {
        return when {
            downstreamKbps > 100000 -> BandwidthEstimate.EXCELLENT
            downstreamKbps > 50000 -> BandwidthEstimate.GOOD
            downstreamKbps > 10000 -> BandwidthEstimate.FAIR
            downstreamKbps > 1000 -> BandwidthEstimate.POOR
            else -> BandwidthEstimate.VERY_POOR
        }
    }

    private fun estimateLatency(networkType: NetworkType): LatencyEstimate {
        return when (networkType) {
            NetworkType.WIFI, NetworkType.ETHERNET -> LatencyEstimate.EXCELLENT
            NetworkType.CELLULAR_5G -> LatencyEstimate.GOOD
            NetworkType.CELLULAR_4G -> LatencyEstimate.FAIR
            NetworkType.CELLULAR_3G -> LatencyEstimate.POOR
            NetworkType.CELLULAR_2G -> LatencyEstimate.VERY_POOR
            NetworkType.UNKNOWN -> LatencyEstimate.POOR
        }
    }

    private fun estimateStability(networkType: NetworkType): StabilityScore {
        return when (networkType) {
            NetworkType.WIFI -> StabilityScore.MEDIUM  // Can fluctuate
            NetworkType.ETHERNET -> StabilityScore.HIGH
            NetworkType.CELLULAR_5G, NetworkType.CELLULAR_4G -> StabilityScore.MEDIUM
            NetworkType.CELLULAR_3G, NetworkType.CELLULAR_2G -> StabilityScore.LOW
            NetworkType.UNKNOWN -> StabilityScore.UNSTABLE
        }
    }

    private fun generateTcpConfig(quality: NetworkQuality): TcpConfig {
        return when (quality.bandwidth) {
            BandwidthEstimate.EXCELLENT, BandwidthEstimate.GOOD -> TcpConfig(
                tcpNoDelay = true,
                soTimeout = 30000,
                sendBufferSize = 65536,
                receiveBufferSize = 65536,
                keepAlive = true
            )
            BandwidthEstimate.FAIR -> TcpConfig(
                tcpNoDelay = true,
                soTimeout = 45000,
                sendBufferSize = 32768,
                receiveBufferSize = 32768,
                keepAlive = true
            )
            BandwidthEstimate.POOR, BandwidthEstimate.VERY_POOR -> TcpConfig(
                tcpNoDelay = false,  // Enable Nagle's algorithm for small packets
                soTimeout = 60000,
                sendBufferSize = 8192,
                receiveBufferSize = 8192,
                keepAlive = true,
                connectionTimeout = 30000
            )
        }
    }

    private fun assessNetworkQualityForCapabilities(capabilities: NetworkCapabilities?): NetworkQuality {
        return if (capabilities != null) {
            val bandwidth = estimateBandwidth(capabilities.linkDownstreamBandwidthKbps)
            NetworkQuality(
                type = NetworkType.WIFI,
                bandwidth = bandwidth,
                latency = LatencyEstimate.GOOD,
                stability = StabilityScore.MEDIUM,
                recommended = TcpConfig()
            )
        } else {
            NetworkQuality(
                type = NetworkType.UNKNOWN,
                bandwidth = BandwidthEstimate.VERY_POOR,
                latency = LatencyEstimate.VERY_POOR,
                stability = StabilityScore.UNSTABLE,
                recommended = TcpConfig()
            )
        }
    }
}

data class TcpSpeedTestResult(
    val connectionTime: Long,
    val throughputKbps: Double,
    val packetLoss: Double,
    val jitter: Int,
    val error: String? = null
)
