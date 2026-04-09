package com.aerovpn.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Ping test result
 */
data class PingResult(
    val host: String,
    val ipAddress: String?,
    val success: Boolean,
    val packetsSent: Int,
    val packetsReceived: Int,
    val packetLoss: Double,
    val minLatency: Long,
    val maxLatency: Long,
    val avgLatency: Long,
    val jitter: Long,
    val results: List<SinglePingResult>
)

/**
 * Single ping attempt result
 */
data class SinglePingResult(
    val sequenceNumber: Int,
    val ttl: Int,
    val responseTime: Long,
    val success: Boolean,
    val timeout: Boolean = false
)

/**
 * Continuous ping monitoring result
 */
data class PingMonitorResult(
    val host: String,
    val averageLatency: Long,
    val packetLoss: Double,
    val stability: PingStability,
    val trend: PingTrend,
    val alert: Boolean
)

enum class PingStability {
    STABLE,
    MODERATE,
    UNSTABLE,
    CRITICAL
}

enum class PingTrend {
    IMPROVING,
    STABLE,
    DEGRADING,
    VOLATILE
}

/**
 * PingTool - Ping and latency testing
 * Test host reachability and measure network latency
 */
object PingTool {

    /**
     * Ping a host multiple times
     */
    suspend fun ping(
        host: String,
        count: Int = 4,
        timeout: Int = 2000,
        interval: Int = 1000
    ): Result<PingResult> = withContext(Dispatchers.IO) {
        try {
            val results = mutableListOf<SinglePingResult>()
            var packetsReceived = 0
            var totalLatency = 0L
            var minLatency = Long.MAX_VALUE
            var maxLatency = Long.MIN_VALUE
            
            // Resolve hostname
            val ipAddress = resolveHost(host)
            
            for (i in 0 until count) {
                val result = singlePing(host, timeout, i)
                results.add(result)
                
                if (result.success) {
                    packetsReceived++
                    totalLatency += result.responseTime
                    minLatency = minOf(minLatency, result.responseTime)
                    maxLatency = maxOf(maxLatency, result.responseTime)
                }
                
                if (i < count - 1) {
                    Thread.sleep(interval.toLong())
                }
            }
            
            val packetLoss = ((count - packetsReceived).toDouble() / count) * 100
            val avgLatency = if (packetsReceived > 0) totalLatency / packetsReceived else 0
            val jitter = if (results.size > 1) calculateJitter(results.filter { it.success }) else 0
            
            Result.success(
                PingResult(
                    host = host,
                    ipAddress = ipAddress,
                    success = packetsReceived > 0,
                    packetsSent = count,
                    packetsReceived = packetsReceived,
                    packetLoss = packetLoss,
                    minLatency = minLatency.takeUnless { it == Long.MAX_VALUE } ?: 0,
                    maxLatency = maxLatency.takeUnless { it == Long.MIN_VALUE } ?: 0,
                    avgLatency = avgLatency,
                    jitter = jitter,
                    results = results
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Continuous ping monitoring
     */
    suspend fun monitorPing(
        host: String,
        duration: Long = 60000,  // 1 minute default
        interval: Int = 2000
    ): List<PingMonitorResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PingMonitorResult>()
        val startTime = System.currentTimeMillis()
        var lastAvgLatency = 0L
        val latencyHistory = mutableListOf<Long>()
        
        while (System.currentTimeMillis() - startTime < duration) {
            ping(host, count = 3, timeout = 2000).onSuccess { pingResult ->
                val stability = calculateStability(pingResult)
                val trend = calculateTrend(latencyHistory, pingResult.avgLatency)
                
                latencyHistory.add(pingResult.avgLatency)
                if (latencyHistory.size > 10) {
                    latencyHistory.removeAt(0)
                }
                
                val isAlert = pingResult.packetLoss > 50 || pingResult.avgLatency > 1000
                
                results.add(
                    PingMonitorResult(
                        host = host,
                        averageLatency = pingResult.avgLatency,
                        packetLoss = pingResult.packetLoss,
                        stability = stability,
                        trend = trend,
                        alert = isAlert
                    )
                )
                
                lastAvgLatency = pingResult.avgLatency
            }
            
            Thread.sleep(interval.toLong())
        }
        
        results
    }

    /**
     * Fast ping test for server selection
     */
    suspend fun quickPing(host: String): Result<Long> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val address = InetAddress.getByName(host)
            val reachable = address.isReachable(1000)
            val endTime = System.currentTimeMillis()
            
            if (reachable) {
                Result.success(endTime - startTime)
            } else {
                Result.failure(Exception("Host unreachable"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ping multiple hosts in parallel and rank by latency
     */
    suspend fun pingMultipleHosts(
        hosts: List<String>,
        count: Int = 3
    ): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        hosts.mapNotNull { host ->
            quickPing(host).getOrNull()?.let { latency ->
                host to latency
            }
        }.sortedBy { it.second }
    }

    /**
     * Test connection quality to server
     */
    suspend fun testConnectionQuality(
        host: String,
        port: Int? = null
    ): ConnectionQuality = withContext(Dispatchers.IO) {
        val pingResult = ping(host).getOrNull()
        
        when {
            pingResult == null -> ConnectionQuality.UNREACHABLE
            pingResult.packetLoss > 50 -> ConnectionQuality.POOR
            pingResult.avgLatency > 500 -> ConnectionQuality.POOR
            pingResult.avgLatency > 200 -> ConnectionQuality.FAIR
            pingResult.avgLatency > 100 -> ConnectionQuality.GOOD
            else -> ConnectionQuality.EXCELLENT
        }
    }

    private suspend fun singlePing(
        host: String,
        timeout: Int,
        sequenceNumber: Int
    ): SinglePingResult = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(host)
            val startTime = System.currentTimeMillis()
            val reachable = address.isReachable(timeout)
            val endTime = System.currentTimeMillis()
            val responseTime = endTime - startTime
            
            if (reachable) {
                SinglePingResult(
                    sequenceNumber = sequenceNumber,
                    ttl = 64,  // Default TTL, would need raw socket to get actual
                    responseTime = responseTime,
                    success = true
                )
            } else {
                SinglePingResult(
                    sequenceNumber = sequenceNumber,
                    ttl = 0,
                    responseTime = timeout.toLong(),
                    success = false,
                    timeout = true
                )
            }
        } catch (e: Exception) {
            SinglePingResult(
                sequenceNumber = sequenceNumber,
                ttl = 0,
                responseTime = 0,
                success = false
            )
        }
    }

    private suspend fun resolveHost(host: String): String? = withContext(Dispatchers.IO) {
        try {
            InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateJitter(results: List<SinglePingResult>): Long {
        if (results.size < 2) return 0
        
        val latencies = results.map { it.responseTime }
        val differences = latencies.zipWithNext { a, b -> kotlin.math.abs(a - b) }
        return differences.average().toLong()
    }

    private fun calculateStability(pingResult: PingResult): PingStability {
        return when {
            pingResult.packetLoss <= 5 && pingResult.jitter < 20 -> PingStability.STABLE
            pingResult.packetLoss <= 20 && pingResult.jitter < 50 -> PingStability.MODERATE
            pingResult.packetLoss <= 50 && pingResult.jitter < 100 -> PingStability.UNSTABLE
            else -> PingStability.CRITICAL
        }
    }

    private fun calculateTrend(history: List<Long>, current: Long): PingTrend {
        if (history.size < 2) return PingTrend.STABLE
        
        val recentAvg = history.takeLast(3).average()
        val olderAvg = history.dropLast(3).take(3).average()
        
        val change = recentAvg - olderAvg
        
        return when {
            kotlin.math.abs(change) < 10 -> PingTrend.STABLE
            change > 50 -> PingTrend.DEGRADING
            change < -50 -> PingTrend.IMPROVING
            else -> PingTrend.VOLATILE
        }
    }

    /**
     * Format ping result for display
     */
    fun formatPingResult(pingResult: PingResult): String {
        return buildString {
            appendLine("Ping statistics for ${pingResult.host}:")
            appendLine("  Packets: Sent = ${pingResult.packetsSent}, Received = ${pingResult.packetsReceived}, Lost = ${pingResult.packetsSent - pingResult.packetsReceived} (${pingResult.packetLoss.toInt()}% loss)")
            appendLine("Approximate round trip times in milli-seconds:")
            appendLine("  Minimum = ${pingResult.minLatency}ms, Maximum = ${pingResult.maxLatency}ms, Average = ${pingResult.avgLatency}ms")
            if (pingResult.jitter > 0) {
                appendLine("  Jitter = ${pingResult.jitter}ms")
            }
        }
    }
}

enum class ConnectionQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNREACHABLE
}
