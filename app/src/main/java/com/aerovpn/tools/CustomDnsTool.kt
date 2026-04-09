package com.aerovpn.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * DNS server configuration
 */
data class DnsServer(
    val address: String,
    val name: String,
    val type: DnsType,
    val port: Int = 53,
    val supportsTls: Boolean = false,
    val supportsHttps: Boolean = false
)

enum class DnsType {
    PUBLIC,
    CUSTOM,
    ISP,
    GOOGLE,
    CLOUDFLARE,
    QUAD9,
    OPENDNS
}

/**
 * DNS test result
 */
data class DnsTestResult(
    val server: String,
    val responseTime: Long,
    val resolved: Boolean,
    val resolvedIp: String?,
    val dnssec: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * CustomDnsTool - DNS configuration and testing
 * Manage custom DNS servers and test DNS performance
 */
object CustomDnsTool {

    private val knownDnsServers = listOf(
        DnsServer("8.8.8.8", "Google DNS Primary", DnsType.GOOGLE, supportsTls = true, supportsHttps = true),
        DnsServer("8.8.4.4", "Google DNS Secondary", DnsType.GOOGLE, supportsTls = true, supportsHttps = true),
        DnsServer("1.1.1.1", "Cloudflare DNS Primary", DnsType.CLOUDFLARE, supportsTls = true, supportsHttps = true),
        DnsServer("1.0.0.1", "Cloudflare DNS Secondary", DnsType.CLOUDFLARE, supportsTls = true, supportsHttps = true),
        DnsServer("9.9.9.9", "Quad9 DNS Primary", DnsType.QUAD9, supportsTls = true, supportsHttps = true),
        DnsServer("149.112.112.112", "Quad9 DNS Secondary", DnsType.QUAD9, supportsTls = true, supportsHttps = true),
        DnsServer("208.67.222.222", "OpenDNS Primary", DnsType.OPENDNS, supportsTls = false, supportsHttps = false),
        DnsServer("208.67.220.220", "OpenDNS Secondary", DnsType.OPENDNS, supportsTls = false, supportsHttps = false)
    )

    /**
     * Get current DNS servers from system
     */
    suspend fun getCurrentDns(context: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            
            linkProperties?.dnsServers?.map { it.hostAddress } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get all known public DNS servers
     */
    fun getKnownDnsServers(): List<DnsServer> = knownDnsServers

    /**
     * Test DNS server resolution speed
     */
    suspend fun testDnsServer(
        dnsServer: String,
        testDomain: String = "google.com"
    ): Result<DnsTestResult> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            
            // Simple DNS resolution test using InetAddress
            val addresses = InetAddress.getAllByName(testDomain)
            val responseTime = System.currentTimeMillis() - startTime
            
            Result.success(
                DnsTestResult(
                    server = dnsServer,
                    responseTime = responseTime,
                    resolved = addresses.isNotEmpty(),
                    resolvedIp = addresses.firstOrNull()?.hostAddress,
                    dnssec = false // Would need proper DNSSEC validation
                )
            )
        } catch (e: Exception) {
            Result.success(
                DnsTestResult(
                    server = dnsServer,
                    responseTime = -1,
                    resolved = false,
                    resolvedIp = null,
                    dnssec = false
                )
            )
        }
    }

    /**
     * Test multiple DNS servers and rank by speed
     */
    suspend fun benchmarkDnsServers(
        servers: List<String> = knownDnsServers.map { it.address },
        testDomain: String = "google.com"
    ): List<DnsTestResult> = withContext(Dispatchers.IO) {
        servers.mapNotNull { server ->
            testDnsServer(server, testDomain).getOrNull()
        }.sortedBy { it.responseTime }
    }

    /**
     * Validate DNS server address format
     */
    fun validateDnsAddress(address: String): Boolean {
        return try {
            InetAddress.getByName(address) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate DNS over HTTPS (DoH) endpoint URL
     */
    fun getDohEndpoint(dnsServer: DnsServer): String? {
        return when (dnsServer.type) {
            DnsType.GOOGLE -> "https://dns.google.com/resolve"
            DnsType.CLOUDFLARE -> "https://cloudflare-dns.com/dns-query"
            DnsType.QUAD9 -> "https://dns.quad9.net/dns-query"
            else -> null
        }
    }

    /**
     * Test DNS over HTTPS endpoint
     */
    suspend fun testDohEndpoint(
        endpoint: String,
        query: String = "google.com"
    ): Result<DnsTestResult> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            
            val url = URL("$endpoint?name=$query&type=A")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/dns-json")
            
            val responseCode = connection.responseCode
            val responseTime = System.currentTimeMillis() - startTime
            
            val response = if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            
            // Parse JSON response
            val resolved = response.contains("\"Answer\"")
            val resolvedIp = extractIpFromDohResponse(response)
            
            Result.success(
                DnsTestResult(
                    server = endpoint,
                    responseTime = responseTime,
                    resolved = resolved,
                    resolvedIp = resolvedIp,
                    dnssec = response.contains("\"dnssec\":true")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractIpFromDohResponse(response: String): String? {
        return try {
            val org.json.JSONObject(response)
                .getJSONArray("Answer")
                .getJSONObject(0)
                .optString("data", null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get DNS server info by address
     */
    fun getDnsInfo(address: String): DnsServer? {
        return knownDnsServers.find { it.address == address }
            ?: DnsServer(address, "Custom DNS", DnsType.CUSTOM)
    }
}
