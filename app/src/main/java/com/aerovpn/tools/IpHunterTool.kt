package com.aerovpn.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result data class for IP information
 */
data class IpInfo(
    val ip: String,
    val country: String,
    val countryName: String,
    val region: String,
    val city: String,
    val latitude: Double?,
    val longitude: Double?,
    val isp: String,
    val timezone: String,
    val mobile: Boolean,
    val proxy: Boolean,
    val hosting: Boolean
)

/**
 * IpHunterTool - Get public IP and location information
 * Uses multiple IP lookup services for reliability
 */
object IpHunterTool {

    private val ipServices = listOf(
        "https://ipapi.co/json",
        "https://ipwho.is/",
        "https://api.myip.com"
    )

    /**
     * Get public IP and location information
     * @return IpInfo with IP details or null on failure
     */
    suspend fun getIpInfo(): Result<IpInfo> = withContext(Dispatchers.IO) {
        try {
            // Try each service until one succeeds
            for (service in ipServices) {
                try {
                    val connection = URL(service).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("User-Agent", "AeroVPN/1.0")

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val ipInfo = parseIpResponse(response, service)
                        if (ipInfo != null) {
                            return@withContext Result.success(ipInfo)
                        }
                    }
                } catch (e: Exception) {
                    // Try next service
                    continue
                }
            }
            Result.failure(Exception("All IP services unavailable"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get only the public IP address (faster)
     */
    suspend fun getIpAddress(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val services = listOf(
                "https://api.ipify.org",
                "https://icanhazip.com",
                "https://ifconfig.me/ip"
            )

            for (service in services) {
                try {
                    val connection = URL(service).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val ip = connection.inputStream.bufferedReader().use { 
                            it.readText().trim() 
                        }
                        if (ip.isValidIpAddress()) {
                            return@withContext Result.success(ip)
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            Result.failure(Exception("Could not retrieve IP address"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseIpResponse(response: String, service: String): IpInfo? {
        return try {
            val json = JSONObject(response)
            
            when {
                service.contains("ipapi.co") -> IpInfo(
                    ip = json.optString("ip", ""),
                    country = json.optString("country_code", ""),
                    countryName = json.optString("country_name", ""),
                    region = json.optString("region", ""),
                    city = json.optString("city", ""),
                    latitude = json.optDouble("latitude").takeUnless { it == 0.0 },
                    longitude = json.optDouble("longitude").takeUnless { it == 0.0 },
                    isp = json.optString("org", ""),
                    timezone = json.optString("timezone", ""),
                    mobile = false,
                    proxy = json.optBoolean("proxy", false),
                    hosting = json.optBoolean("hosting", false)
                )
                service.contains("ipwho.is") -> IpInfo(
                    ip = json.optString("ip", ""),
                    country = json.optString("country_code", ""),
                    countryName = json.optString("country", ""),
                    region = json.optString("region", ""),
                    city = json.optString("city", ""),
                    latitude = json.optDouble("latitude").takeUnless { it == 0.0 },
                    longitude = json.optDouble("longitude").takeUnless { it == 0.0 },
                    isp = json.optString("connection", JSONObject()).optString("isp", ""),
                    timezone = json.optString("time_zone", ""),
                    mobile = json.optBoolean("connection", JSONObject())
                        .optBoolean("mobile", false),
                    proxy = json.optBoolean("connection", JSONObject())
                        .optBoolean("proxy", false),
                    hosting = json.optBoolean("connection", JSONObject())
                        .optBoolean("hosting", false)
                )
                service.contains("myip.com") -> IpInfo(
                    ip = json.optString("ip", ""),
                    country = json.optString("cc", ""),
                    countryName = json.optString("country", ""),
                    region = "",
                    city = "",
                    latitude = null,
                    longitude = null,
                    isp = "",
                    timezone = "",
                    mobile = false,
                    proxy = false,
                    hosting = false
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun String.isValidIpAddress(): Boolean {
        return matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) &&
            split(".").all { it.toIntOrNull()?.let { octet -> octet in 0..255 } ?: false }
    }
}
