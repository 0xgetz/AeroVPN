package com.aerovpn.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

/**
 * DNS tunnel test result
 */
data class SlowDnsResult(
    val dnsServer: String,
    val queryTime: Long,
    final val isSlow: Boolean,
    final val packetLoss: Double,
    val avgResponseTime: Long,
    val tunnelScore: DnsTunnelScore,
    val recommended: Boolean
)

enum class DnsTunnelScore {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNUSABLE
}

/**
 * DNS query type for testing
 */
enum class DnsQueryType(val recordType: String) {
    A("A"),
    AAAA("AAAA"),
    TXT("TXT"),
    MX("MX"),
    CNAME("CNAME"),
    NULL("NULL"),  // NULL records often used for tunneling
    PRIVATE("PRIVATE")  // Private class queries
}

/**
 * SlowDnsCheckerTool - DNS tunnel testing and optimization
 * Tests DNS servers for tunneling capability and detects slow DNS
 */
object SlowDnsCheckerTool {

    private val dnsTunnelPorts = listOf(53, 5353, 443, 8443)
    
    // Known DNS servers good for tunneling
    private val tunnelFriendlyDns = listOf(
        "8.8.8.8",      // Google
        "1.1.1.1",      // Cloudflare
        "9.9.9.9",      // Quad9
        "185.228.168.9", // CleanBrowsing
        "76.76.2.0"      // ControlD
    )

    /**
     * Test DNS server for tunneling capability
     */
    suspend fun testDnsTunnel(
        dnsServer: String,
        port: Int = 53,
        testDomain: String = "google.com"
    ): Result<SlowDnsResult> = withContext(Dispatchers.IO) {
        try {
            val responseTimes = mutableListOf<Long>()
            val totalTests = 5
            var successCount = 0
            
            // Perform multiple DNS queries
            for (i in 0 until totalTests) {
                val startTime = System.currentTimeMillis()
                val resolved = queryDns(dnsServer, port, testDomain)
                val endTime = System.currentTimeMillis()
                
                if (resolved) {
                    successCount++
                    responseTimes.add(endTime - startTime)
                }
            }
            
            val packetLoss = ((totalTests - successCount).toDouble() / totalTests) * 100
            val avgResponseTime = if (responseTimes.isNotEmpty()) {
                responseTimes.average().toLong()
            } else {
                Long.MAX_VALUE
            }
            
            val tunnelScore = calculateTunnelScore(avgResponseTime, packetLoss)
            val isSlow = avgResponseTime > 1000 || packetLoss > 50
            
            Result.success(
                SlowDnsResult(
                    dnsServer = dnsServer,
                    queryTime = avgResponseTime,
                    isSlow = isSlow,
                    packetLoss = packetLoss,
                    avgResponseTime = avgResponseTime,
                    tunnelScore = tunnelScore,
                    recommended = tunnelFriendlyDns.contains(dnsServer) && tunnelScore in listOf(DnsTunnelScore.EXCELLENT, DnsTunnelScore.GOOD)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Test different query types for tunneling
     */
    suspend fun testDnsQueryTypes(
        dnsServer: String,
        port: Int = 53
    ): Map<DnsQueryType, Long> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<DnsQueryType, Long>()
        
        DnsQueryType.values().forEach { type ->
            val startTime = System.currentTimeMillis()
            queryDnsWithType(dnsServer, port, type)
            val endTime = System.currentTimeMillis()
            results[type] = endTime - startTime
        }
        
        results
    }

    /**
     * Test DNS tunneling through different ports
     */
    suspend fun testDnsPorts(
        dnsServer: String,
        domain: String = "google.com"
    ): Map<Int, SlowDnsResult> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<Int, SlowDnsResult>()
        
        dnsTunnelPorts.forEach { port ->
            testDnsTunnel(dnsServer, port, domain).onSuccess { result ->
                results[port] = result
            }
        }
        
        results
    }

    /**
     * Optimize DNS settings for tunneling
     */
    suspend fun optimizeDnsForTunneling(
        primaryDns: String,
        secondaryDns: String? = null
    ): DnsOptimizationResult = withContext(Dispatchers.IO) {
        val primaryTest = testDnsTunnel(primaryDns).getOrNull()
        val secondaryTest = secondaryDns?.let { testDnsTunnel(it).getOrNull() }
        
        DnsOptimizationResult(
            primaryDns = primaryDns,
            primaryScore = primaryTest?.tunnelScore ?: DnsTunnelScore.UNUSABLE,
            secondaryDns = secondaryDns,
            secondaryScore = secondaryTest?.tunnelScore,
            recommendation = generateDnsRecommendation(primaryTest, secondaryTest),
            estimatedStability = calculateStabilityScore(primaryTest, secondaryTest)
        )
    }

    /**
     * Check if DNS is being blocked or throttled
     */
    suspend fun detectDnsInterference(
        dnsServer: String
    ): DnsInterferenceResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<Boolean>()
        val testDomains = listOf(
            "google.com",
            "cloudflare.com",
            "dns.google.com",
            "one.one.one.one"
        )
        
        var blockedCount = 0
        var slowCount = 0
        
        testDomains.forEach { domain ->
            val startTime = System.currentTimeMillis()
            val resolved = queryDns(dnsServer, 53, domain)
            val responseTime = System.currentTimeMillis() - startTime
            
            if (!resolved) {
                blockedCount++
            } else if (responseTime > 2000) {
                slowCount++
            }
            
            results.add(resolved)
        }
        
        val blockRate = (blockedCount.toDouble() / testDomains.size) * 100
        val slowRate = (slowCount.toDouble() / testDomains.size) * 100
        
        DnsInterferenceResult(
            dnsServer = dnsServer,
            isBlocked = blockRate > 50,
            isThrottled = slowRate > 50,
            blockRate = blockRate,
            slowRate = slowRate,
            interference = when {
                blockRate > 50 -> InterferenceType.BLOCKED
                slowRate > 50 -> InterferenceType.THROTTLED
                blockRate > 0 || slowRate > 0 -> InterferenceType.SUSPECTED
                else -> InterferenceType.NONE
            }
        )
    }

    private suspend fun queryDns(
        dnsServer: String,
        port: Int,
        domain: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            
            val address = InetAddress.getByName(dnsServer)
            val query = buildDnsQuery(domain)
            
            val packet = DatagramPacket(query, query.size, address, port)
            socket.send(packet)
            
            val response = ByteArray(512)
            val responsePacket = DatagramPacket(response, response.size)
            socket.receive(responsePacket)
            socket.close()
            
            responsePacket.length > 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun queryDnsWithType(
        dnsServer: String,
        port: Int,
        queryType: DnsQueryType
    ): Boolean = withContext(Dispatchers.IO) {
        // Simplified - would need proper DNS query building for each type
        queryDns(dnsServer, port, "google.com")
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        // Simplified DNS query for A record
        val header = byteArrayOf(
            0x00, 0x01, // ID
            0x01, 0x00, // Flags: standard query
            0x00, 0x01, // QDCOUNT: 1 question
            0x00, 0x00, // ANCOUNT: 0
            0x00, 0x00, // NSCOUNT: 0
            0x00, 0x00  // ARCOUNT: 0
        )
        
        val query = domain.split(".").flatMap { label ->
            listOf(label.length.toByte()) + label.toByteArray().map { it.toByte() }
        } + byteArrayOf(0x00) // End of domain name
        
        val qType = byteArrayOf(0x00, 0x01) // A record
        val qClass = byteArrayOf(0x00, 0x01) // IN class
        
        return header + query.toByteArray() + qType + qClass
    }

    private fun calculateTunnelScore(responseTime: Long, packetLoss: Double): DnsTunnelScore {
        return when {
            responseTime < 100 && packetLoss < 5 -> DnsTunnelScore.EXCELLENT
            responseTime < 300 && packetLoss < 10 -> DnsTunnelScore.GOOD
            responseTime < 1000 && packetLoss < 30 -> DnsTunnelScore.FAIR
            responseTime < 2000 && packetLoss < 50 -> DnsTunnelScore.POOR
            else -> DnsTunnelScore.UNUSABLE
        }
    }

    private fun generateDnsRecommendation(
        primary: SlowDnsResult?,
        secondary: SlowDnsResult?
    ): String {
        return buildString {
            if (primary?.tunnelScore in listOf(DnsTunnelScore.EXCELLENT, DnsTunnelScore.GOOD)) {
                append("Primary DNS is suitable for tunneling.")
            } else {
                append("Consider using Cloudflare (1.1.1.1) or Google (8.8.8.8) DNS.")
            }
            if (secondary != null && secondary.tunnelScore in listOf(DnsTunnelScore.POOR, DnsTunnelScore.UNUSABLE)) {
                append(" Secondary DNS may cause issues.")
            }
        }
    }

    private fun calculateStabilityScore(
        primary: SlowDnsResult?,
        secondary: SlowDnsResult?
    ): Double {
        val primaryScore = when (primary?.tunnelScore) {
            DnsTunnelScore.EXCELLENT -> 1.0
            DnsTunnelScore.GOOD -> 0.8
            DnsTunnelScore.FAIR -> 0.6
            DnsTunnelScore.POOR -> 0.4
            else -> 0.2
        }
        
        val secondaryScore = when (secondary?.tunnelScore) {
            DnsTunnelScore.EXCELLENT -> 0.3
            DnsTunnelScore.GOOD -> 0.2
            DnsTunnelScore.FAIR -> 0.1
            else -> 0.0
        }
        
        return primaryScore + secondaryScore
    }
}

data class DnsOptimizationResult(
    val primaryDns: String,
    val primaryScore: DnsTunnelScore,
    val secondaryDns: String?,
    val secondaryScore: DnsTunnelScore?,
    val recommendation: String,
    val estimatedStability: Double
)

data class DnsInterferenceResult(
    val dnsServer: String,
    val isBlocked: Boolean,
    val isThrottled: Boolean,
    val blockRate: Double,
    val slowRate: Double,
    val interference: InterferenceType
)

enum class InterferenceType {
    NONE,
    SUSPECTED,
    THROTTLED,
    BLOCKED
}
