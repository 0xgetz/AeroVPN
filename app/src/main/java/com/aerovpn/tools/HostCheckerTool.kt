package com.aerovpn.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*

/**
 * Host connectivity check result
 */
data class HostCheckResult(
    val host: String,
    val ipAddress: String?,
    val reachable: Boolean,
    val responseTime: Long,
    val sslInfo: SslInfo?,
    val httpStatus: Int?,
    val headers: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * SSL/TLS certificate information
 */
data class SslInfo(
    val valid: Boolean,
    val issuer: String,
    val subject: String,
    val validFrom: Date,
    val validTo: Date,
    val serialNumber: String,
    val signatureAlgorithm: String,
    val protocol: String,
    val cipherSuite: String,
    val peerCertificates: Int,
    val isExpired: Boolean,
    val daysUntilExpiry: Int,
    val subjectAlternativeNames: List<String>
)

/**
 * HostCheckerTool - Check host connectivity and SSL information
 * Tests host reachability, DNS resolution, and SSL certificate validity
 */
object HostCheckerTool {

    /**
     * Check host connectivity and SSL info
     */
    suspend fun checkHost(
        host: String,
        port: Int = 443,
        timeout: Int = 10000,
        checkSsl: Boolean = true
    ): Result<HostCheckResult> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            
            // DNS resolution
            val ipAddress = resolveHost(host)
            val dnsTime = System.currentTimeMillis() - startTime
            
            // Check if reachable
            val reachable = ipAddress != null
            
            // SSL check
            val sslInfo = if (checkSsl && reachable) {
                checkSslCertificate(host, port, timeout)
            } else null
            
            // HTTP status check
            val httpStatus = if (reachable) {
                checkHttpStatus(host, timeout)
            } else null
            
            val totalTime = System.currentTimeMillis() - startTime
            
            Result.success(
                HostCheckResult(
                    host = host,
                    ipAddress = ipAddress,
                    reachable = reachable,
                    responseTime = totalTime,
                    sslInfo = sslInfo,
                    httpStatus = httpStatus,
                    headers = emptyMap()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check SSL certificate for a host
     */
    private suspend fun checkSslCertificate(
        host: String,
        port: Int,
        timeout: Int
    ): SslInfo? = withContext(Dispatchers.IO) {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            
            sslContext.init(null, arrayOf(trustManager), null)
            
            val url = java.net.URL("https://$host:$port")
            val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.requestMethod = "HEAD"
            
            // Use a cert-capturing trust manager to get peer certificates
            val capturedCerts = mutableListOf<java.security.cert.X509Certificate>()
            val certCaptureTm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    capturedCerts.addAll(chain)
                }
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val captureSslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            captureSslCtx.init(null, arrayOf(certCaptureTm), null)
            connection.sslSocketFactory = captureSslCtx.socketFactory
            connection.connect()
            val certificates: Array<java.security.cert.Certificate> = capturedCerts.toTypedArray()
            
            if (certificates.isNotEmpty()) {
                val cert = certificates[0] as X509Certificate
                val now = Date()
                val expiryDate = cert.notAfter
                val daysUntilExpiry = ((expiryDate.time - now.time) / (1000 * 60 * 60 * 24)).toInt()
                
                SslInfo(
                    valid = !now.after(cert.notAfter) && !cert.notBefore.after(now),
                    issuer = cert.issuerDN?.name ?: "Unknown",
                    subject = cert.subjectDN?.name ?: "Unknown",
                    validFrom = cert.notBefore,
                    validTo = expiryDate,
                    serialNumber = cert.serialNumber.toString(16),
                    signatureAlgorithm = cert.sigAlgName ?: "Unknown",
                    protocol = "TLS",
                    cipherSuite = "Unknown",
                    peerCertificates = certificates.size,
                    isExpired = now.after(cert.notAfter),
                    daysUntilExpiry = daysUntilExpiry,
                    subjectAlternativeNames = getSubjectAlternativeNames(cert)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check HTTP status code
     */
    private suspend fun checkHttpStatus(
        host: String,
        timeout: Int
    ): Int? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://$host")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = true
            
            connection.responseCode
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve hostname to IP address
     */
    private suspend fun resolveHost(host: String): String? = withContext(Dispatchers.IO) {
        try {
            val addresses = InetAddress.getAllByName(host)
            addresses.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get Subject Alternative Names from certificate
     */
    private fun getSubjectAlternativeNames(cert: X509Certificate): List<String> {
        return try {
            cert.subjectAlternativeNames?.flatMap { entry ->
                if (entry.size >= 2) {
                    listOfNotNull(entry[1] as? String)
                } else {
                    emptyList()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check multiple hosts in parallel
     */
    suspend fun checkMultipleHosts(
        hosts: List<Pair<String, Int>>,
        timeout: Int = 10000
    ): List<HostCheckResult> = withContext(Dispatchers.IO) {
        hosts.mapNotNull { (host, port) ->
            checkHost(host, port, timeout).getOrNull()
        }
    }

    /**
     * Validate certificate expiry
     */
    fun validateCertificateExpiry(sslInfo: SslInfo): CertificateValidity {
        return when {
            sslInfo.isExpired -> CertificateValidity.EXPIRED
            sslInfo.daysUntilExpiry < 30 -> CertificateValidity.EXPIRING_SOON
            sslInfo.daysUntilExpiry < 90 -> CertificateValidity.EXPIRING_WARNING
            else -> CertificateValidity.VALID
        }
    }

    /**
     * Format SSL info for display
     */
    fun formatSslInfo(sslInfo: SslInfo): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return buildString {
            appendLine("Certificate Status: ${if (sslInfo.valid) "Valid" else "Invalid"}")
            appendLine("Subject: ${sslInfo.subject}")
            appendLine("Issuer: ${sslInfo.issuer}")
            appendLine("Valid From: ${dateFormat.format(sslInfo.validFrom)}")
            appendLine("Valid Until: ${dateFormat.format(sslInfo.validTo)}")
            appendLine("Days Until Expiry: ${sslInfo.daysUntilExpiry}")
            appendLine("Protocol: ${sslInfo.protocol}")
            appendLine("Cipher Suite: ${sslInfo.cipherSuite}")
            appendLine("Signature Algorithm: ${sslInfo.signatureAlgorithm}")
            if (sslInfo.subjectAlternativeNames.isNotEmpty()) {
                appendLine("Subject Alternative Names:")
                sslInfo.subjectAlternativeNames.forEach { appendLine("  - $it") }
            }
        }
    }
}

enum class CertificateValidity {
    VALID,
    EXPIRING_WARNING,
    EXPIRING_SOON,
    EXPIRED
}
