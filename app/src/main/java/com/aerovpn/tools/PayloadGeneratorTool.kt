package com.aerovpn.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Payload configuration for HTTP/TLS tunneling
 */
data class PayloadConfig(
    val method: String = "GET",
    val path: String = "/",
    val host: String,
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val tlsVersion: String = "TLSv1.2",
    val sni: String? = null,
    val alpnProtocols: List<String> = listOf("http/1.1")
)

/**
 * Generated payload with full request details
 */
data class GeneratedPayload(
    val rawRequest: String,
    val headers: Map<String, String>,
    val payloadSize: Int,
    val encoding: String = "base64",
    val encodedPayload: String
)

/**
 * PayloadGeneratorTool - Generate HTTP/TLS payloads for tunneling
 * Creates custom HTTP requests for bypassing restrictions
 */
object PayloadGeneratorTool {

    /**
     * Generate HTTP payload with custom headers
     */
    suspend fun generateHttpPayload(config: PayloadConfig): Result<GeneratedPayload> = withContext(Dispatchers.IO) {
        try {
            val headersBuilder = StringBuilder()
            headersBuilder.appendLine("${config.method} ${config.path} HTTP/1.1")
            headersBuilder.appendLine("Host: ${config.host}")
            headersBuilder.appendLine("User-Agent: ${config.userAgent}")
            headersBuilder.appendLine("Accept: */*")
            headersBuilder.appendLine("Connection: keep-alive")
            
            // Add custom headers
            config.headers.forEach { (key, value) ->
                headersBuilder.appendLine("$key: $value")
            }
            
            // Add content length if body exists
            if (config.body != null) {
                headersBuilder.appendLine("Content-Type: application/octet-stream")
                headersBuilder.appendLine("Content-Length: ${config.body.length}")
            }
            
            headersBuilder.appendLine()
            if (config.body != null) {
                headersBuilder.appendLine(config.body)
            }
            
            val rawRequest = headersBuilder.toString()
            val encodedPayload = android.util.Base64.encodeToString(
                rawRequest.toByteArray(), 
                android.util.Base64.NO_WRAP
            )
            
            Result.success(
                GeneratedPayload(
                    rawRequest = rawRequest,
                    headers = config.headers + mapOf(
                        "Host" to config.host,
                        "User-Agent" to config.userAgent
                    ),
                    payloadSize = rawRequest.length,
                    encodedPayload = encodedPayload
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate TLS Client Hello payload for SNI tunneling
     */
    suspend fun generateTlsPayload(
        sni: String,
        alpnProtocols: List<String> = listOf("http/1.1")
    ): Result<TlsPayload> = withContext(Dispatchers.IO) {
        try {
            // TLS 1.2 Client Hello structure
            val tlsVersion = when {
                sni.contains("cloudflare") -> "TLSv1.3"
                else -> "TLSv1.2"
            }
            
            val payload = TlsPayload(
                sni = sni,
                tlsVersion = tlsVersion,
                alpnProtocols = alpnProtocols,
                cipherSuites = getDefaultCipherSuites(),
                extensions = buildTlsExtensions(sni, alpnProtocols)
            )
            
            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate WebSocket upgrade payload
     */
    suspend fun generateWebSocketPayload(
        host: String,
        path: String,
        subProtocol: String? = null
    ): Result<GeneratedPayload> = withContext(Dispatchers.IO) {
        try {
            val key = generateWebSocketKey()
            
            val headers = mapOf(
                "Host" to host,
                "Upgrade" to "websocket",
                "Connection" to "Upgrade",
                "Sec-WebSocket-Key" to key,
                "Sec-WebSocket-Version" to "13",
                "Sec-WebSocket-Protocol" to (subProtocol ?: "binary")
            )
            
            val config = PayloadConfig(
                method = "GET",
                path = path,
                host = host,
                headers = headers
            )
            
            generateHttpPayload(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Test payload against actual endpoint
     */
    suspend fun testPayload(
        url: String,
        config: PayloadConfig
    ): Result<PayloadTestResult> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.requestMethod = config.method
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            
            // Set headers
            connection.setRequestProperty("Host", config.host)
            connection.setRequestProperty("User-Agent", config.userAgent)
            config.headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            // Send body if exists
            if (config.body != null) {
                connection.doOutput = true
                connection.outputStream.use { 
                    it.write(config.body.toByteArray()) 
                }
            }
            
            val responseCode = connection.responseCode
            val responseHeaders = buildMap {
                connection.headerFields.forEach { (key, values) ->
                    if (key != null) {
                        put(key, values.joinToString("; "))
                    }
                }
            }
            
            val responseBody = try {
                connection.inputStream?.bufferedReader()?.use { it.readText() } 
                    ?: connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: ""
            } catch (e: Exception) {
                ""
            }
            
            Result.success(
                PayloadTestResult(
                    success = responseCode in 200..299,
                    responseCode = responseCode,
                    responseHeaders = responseHeaders,
                    responseBody = responseBody,
                    responseTime = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getDefaultCipherSuites(): List<String> = listOf(
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384"
    )

    private fun buildTlsExtensions(sni: String, alpnProtocols: List<String>): Map<String, Any> = buildMap {
        put("server_name", sni)
        put("alpn_protocols", alpnProtocols)
        put("supported_versions", listOf("TLSv1.2", "TLSv1.3"))
        put("signature_algorithms", listOf("ecdsa_secp256r1_sha256", "rsa_pkcs1_sha256"))
    }

    private fun generateWebSocketKey(): String {
        val bytes = ByteArray(16)
        @Suppress("DEPRECATION")
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}

/**
 * TLS payload data
 */
data class TlsPayload(
    val sni: String,
    val tlsVersion: String,
    val alpnProtocols: List<String>,
    val cipherSuites: List<String>,
    val extensions: Map<String, Any>
)

/**
 * Payload test result
 */
data class PayloadTestResult(
    val success: Boolean,
    val responseCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String,
    val responseTime: Long
)
