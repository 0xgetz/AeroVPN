package com.aerovpn.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aerovpn.config.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Export/Import format types
 */
enum class ConfigFormat {
    JSON,
    OVPN,      // OpenVPN format
    WIREGUARD, // WireGuard format
    V2RAY,     // V2Ray/Vmess link
    SHADOWSOCKS,
    CUSTOM
}

/**
 * Export result
 */
data class ExportResult(
    val success: Boolean,
    val fileUri: Uri? = null,
    val filePath: String? = null,
    val configCount: Int,
    val format: ConfigFormat,
    val fileSize: Long,
    val errorMessage: String? = null
)

/**
 * Import result
 */
data class ImportResult(
    val success: Boolean,
    val configsImported: Int,
    val configsFailed: Int,
    val importedConfigs: List<VpnConfig>,
    val errors: List<String>
)

/**
 * Backup file info
 */
data class BackupInfo(
    val name: String,
    val path: String,
    val size: Long,
    val date: Long,
    val configCount: Int,
    val format: ConfigFormat
)

/**
 * ExportImportTool - Config export/import utilities
 * Manage VPN configuration backup and restore
 */
object ExportImportTool {

    private const val BACKUP_DIR = "AeroVPN/backups"
    private const val EXPORT_DIR = "AeroVPN/exports"

    /**
     * Export VPN configurations to file
     */
    suspend fun exportConfigs(
        context: Context,
        configs: List<VpnConfig>,
        format: ConfigFormat = ConfigFormat.JSON,
        fileName: String? = null
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val actualFileName = fileName ?: "aerovpn_backup_$timestamp.${format.name.lowercase()}"
            
            val exportDir = File(context.getExternalFilesDir(null), EXPORT_DIR)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            
            val exportFile = File(exportDir, actualFileName)
            val content = when (format) {
                ConfigFormat.JSON -> exportToJson(configs)
                ConfigFormat.OVPN -> exportToOvpn(configs)
                ConfigFormat.WIREGUARD -> exportToWireGuard(configs)
                ConfigFormat.V2RAY -> exportToV2Ray(configs)
                ConfigFormat.SHADOWSOCKS -> exportToShadowsocks(configs)
                ConfigFormat.CUSTOM -> exportToCustom(configs)
            }
            
            exportFile.writeText(content)
            
            Result.success(
                ExportResult(
                    success = true,
                    filePath = exportFile.absolutePath,
                    configCount = configs.size,
                    format = format,
                    fileSize = exportFile.length()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Export to URI (for sharing)
     */
    suspend fun exportToUri(
        context: Context,
        configs: List<VpnConfig>,
        uri: Uri,
        format: ConfigFormat = ConfigFormat.JSON
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            val content = when (format) {
                ConfigFormat.JSON -> exportToJson(configs)
                ConfigFormat.OVPN -> exportToOvpn(configs)
                ConfigFormat.WIREGUARD -> exportToWireGuard(configs)
                ConfigFormat.V2RAY -> exportToV2Ray(configs)
                ConfigFormat.SHADOWSOCKS -> exportToShadowsocks(configs)
                ConfigFormat.CUSTOM -> exportToCustom(configs)
            }
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            
            val fileSize = context.contentResolver.openInputStream(uri)?.use { 
                it.available().toLong() 
            } ?: 0L
            
            Result.success(
                ExportResult(
                    success = true,
                    fileUri = uri,
                    configCount = configs.size,
                    format = format,
                    fileSize = fileSize
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Import configurations from file
     */
    suspend fun importConfigs(
        context: Context,
        uri: Uri
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { 
                it.bufferedReader().use { reader -> reader.readText() }
            } ?: return@withContext Result.failure(Exception("Could not read file"))
            
            val format = detectFormat(content)
            val configs = when (format) {
                ConfigFormat.JSON -> importFromJson(content)
                ConfigFormat.OVPN -> importFromOvpn(content)
                ConfigFormat.WIREGUARD -> importFromWireGuard(content)
                ConfigFormat.V2RAY -> importFromV2Ray(content)
                ConfigFormat.SHADOWSOCKS -> importFromShadowsocks(content)
                ConfigFormat.CUSTOM -> importFromCustom(content)
            }
            
            Result.success(
                ImportResult(
                    success = true,
                    configsImported = configs.size,
                    configsFailed = 0,
                    importedConfigs = configs,
                    errors = emptyList()
                )
            )
        } catch (e: Exception) {
            Result.success(
                ImportResult(
                    success = false,
                    configsImported = 0,
                    configsFailed = 1,
                    importedConfigs = emptyList(),
                    errors = listOf(e.message ?: "Unknown error")
                )
            )
        }
    }

    /**
     * List available backup files
     */
    suspend fun listBackups(context: Context): List<BackupInfo> = withContext(Dispatchers.IO) {
        val backupDir = File(context.getExternalFilesDir(null), BACKUP_DIR)
        
        if (!backupDir.exists()) {
            return@withContext emptyList()
        }
        
        backupDir.listFiles { file ->
            file.isFile && (file.extension == "json" || file.extension == "ovpn" || file.extension == "conf")
        }?.mapNotNull { file ->
            try {
                BackupInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    date = file.lastModified(),
                    configCount = countConfigsInFile(file),
                    format = detectFormatFromFile(file)
                )
            } catch (e: Exception) {
                null
            }
        }?.sortedByDescending { it.date } ?: emptyList()
    }

    /**
     * Delete backup file
     */
    suspend fun deleteBackup(context: Context, backupPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(backupPath)
            if (file.exists() && file.delete()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete backup"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Restore from backup
     */
    suspend fun restoreBackup(
        context: Context,
        backupPath: String
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val file = File(backupPath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Backup file not found"))
            }
            
            val uri = Uri.fromFile(file)
            importConfigs(context, uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Share configuration as link
     */
    fun shareAsLink(config: VpnConfig): String {
        return when (config.protocol) {
            "v2ray", "vmess", "vless" -> encodeVmessLink(config)
            "shadowsocks" -> encodeShadowsocksLink(config)
            "trojan" -> encodeTrojanLink(config)
            else -> config.name // Unknown protocol
        }
    }

    /**
     * Import from link
     */
    suspend fun importFromLink(link: String): Result<VpnConfig> = withContext(Dispatchers.IO) {
        try {
            when {
                link.startsWith("vmess://") -> parseVmessLink(link)
                link.startsWith("ss://") -> parseShadowsocksLink(link)
                link.startsWith("trojan://") -> parseTrojanLink(link)
                link.startsWith("vless://") -> parseVlessLink(link)
                else -> Result.failure(Exception("Unsupported link format"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Private helper functions

    private fun exportToJson(configs: List<VpnConfig>): String {
        val jsonArray = JSONArray()
        configs.forEach { config ->
            val json = JSONObject().apply {
                put("name", config.name)
                put("protocol", config.protocol)
                put("host", config.host)
                put("port", config.port)
                put("password", config.password)
                put("method", config.method)
                put("username", config.username)
                put("notes", config.notes)
                put("createdAt", config.createdAt)
            }
            jsonArray.put(json)
        }
        return jsonArray.toString(2)
    }

    private fun exportToOvpn(configs: List<VpnConfig>): String {
        return configs.joinToString("\n\n# ---\n\n") { config ->
            buildString {
                appendLine("# ${config.name}")
                appendLine("client")
                appendLine("dev tun")
                appendLine("proto tcp-client")
                appendLine("remote ${config.host} ${config.port}")
                appendLine("resolv-retry infinite")
                appendLine("nobind")
                appendLine("persist-key")
                appendLine("persist-tun")
                appendLine("cipher AES-256-CBC")
                appendLine("auth SHA256")
                appendLine("verb 3")
            }
        }
    }

    private fun exportToWireGuard(configs: List<VpnConfig>): String {
        return configs.joinToString("\n\n") { config ->
            buildString {
                appendLine("# ${config.name}")
                appendLine("[Interface]")
                appendLine("PrivateKey = ${config.password ?: "REPLACE_ME"}")
                appendLine("Address = 10.0.0.2/24")
                appendLine("DNS = 1.1.1.1")
                appendLine("")
                appendLine("[Peer]")
                appendLine("PublicKey = ${config.username ?: "REPLACE_ME"}")
                appendLine("Endpoint = ${config.host}:${config.port}")
                appendLine("AllowedIPs = 0.0.0.0/0")
            }
        }
    }

    private fun exportToV2Ray(configs: List<VpnConfig>): String {
        return configs.joinToString("\n") { config ->
            encodeVmessLink(config)
        }
    }

    private fun exportToShadowsocks(configs: List<VpnConfig>): String {
        return configs.joinToString("\n") { config ->
            encodeShadowsocksLink(config)
        }
    }

    private fun exportToCustom(configs: List<VpnConfig>): String {
        return exportToJson(configs)
    }

    private fun importFromJson(content: String): List<VpnConfig> {
        val jsonArray = JSONArray(content)
        return (0 until jsonArray.length()).map { index ->
            val json = jsonArray.getJSONObject(index)
            VpnConfig(
                name = json.optString("name", "Imported Config"),
                protocol = json.optString("protocol", "custom"),
                host = json.optString("host", ""),
                port = json.optInt("port", 443),
                password = json.optString("password", null),
                method = json.optString("method", "auto"),
                username = json.optString("username", null),
                notes = json.optString("notes", null),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun importFromOvpn(content: String): List<VpnConfig> {
        // Simplified OVPN import
        val configs = mutableListOf<VpnConfig>()
        var currentConfig: VpnConfig? = null
        
        content.lines().forEach { line ->
            when {
                line.startsWith("# ") -> {
                    currentConfig?.let { configs.add(it) }
                    currentConfig = VpnConfig(
                        name = line.removePrefix("# ").trim(),
                        protocol = "openvpn",
                        host = "",
                        port = 1194
                    )
                }
                line.startsWith("remote ") -> {
                    val parts = line.removePrefix("remote ").trim().split(" ")
                    currentConfig = currentConfig?.copy(
                        host = parts.getOrNull(0) ?: "",
                        port = parts.getOrNull(1)?.toIntOrNull() ?: 1194
                    )
                }
            }
        }
        
        currentConfig?.let { configs.add(it) }
        return configs
    }

    private fun importFromWireGuard(content: String): List<VpnConfig> {
        val configs = mutableListOf<VpnConfig>()
        var currentConfig: VpnConfig? = null
        
        content.lines().forEach { line ->
            when {
                line.startsWith("# ") -> {
                    currentConfig?.let { configs.add(it) }
                    currentConfig = VpnConfig(
                        name = line.removePrefix("# ").trim(),
                        protocol = "wireguard",
                        host = "",
                        port = 51820
                    )
                }
                line.startsWith("Endpoint = ") -> {
                    val endpoint = line.removePrefix("Endpoint = ").trim()
                    val parts = endpoint.split(":")
                    currentConfig = currentConfig?.copy(
                        host = parts.getOrNull(0) ?: "",
                        port = parts.getOrNull(1)?.toIntOrNull() ?: 51820
                    )
                }
            }
        }
        
        currentConfig?.let { configs.add(it) }
        return configs
    }

    private fun importFromV2Ray(content: String): List<VpnConfig> {
        return content.lines()
            .filter { it.startsWith("vmess://") || it.startsWith("vless://") }
            .mapNotNull { line ->
                parseVmessLink(line).getOrNull() ?: parseVlessLink(line).getOrNull()
            }
    }

    private fun importFromShadowsocks(content: String): List<VpnConfig> {
        return content.lines()
            .filter { it.startsWith("ss://") }
            .mapNotNull { line ->
                parseShadowsocksLink(line).getOrNull()
            }
    }

    private fun importFromCustom(content: String): List<VpnConfig> {
        return importFromJson(content)
    }

    private fun detectFormat(content: String): ConfigFormat {
        return when {
            content.trim().startsWith("[") || content.trim().startsWith("{") -> ConfigFormat.JSON
            content.contains("client") && content.contains("remote") -> ConfigFormat.OVPN
            content.contains("[Interface]") && content.contains("[Peer]") -> ConfigFormat.WIREGUARD
            content.lines().any { it.startsWith("vmess://") || it.startsWith("vless://") } -> ConfigFormat.V2RAY
            content.lines().any { it.startsWith("ss://") } -> ConfigFormat.SHADOWSOCKS
            else -> ConfigFormat.CUSTOM
        }
    }

    private fun detectFormatFromFile(file: File): ConfigFormat {
        return when (file.extension.lowercase()) {
            "json" -> ConfigFormat.JSON
            "ovpn" -> ConfigFormat.OVPN
            "conf" -> ConfigFormat.WIREGUARD
            else -> ConfigFormat.CUSTOM
        }
    }

    private fun countConfigsInFile(file: File): Int {
        return try {
            val content = file.readText()
            when {
                content.trim().startsWith("[") -> JSONArray(content).length()
                else -> content.lines().count { 
                    it.startsWith("# ") || 
                    it.startsWith("vmess://") || 
                    it.startsWith("ss://") 
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun encodeVmessLink(config: VpnConfig): String {
        // Simplified VMess link encoding
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", config.name)
            put("add", config.host)
            put("port", config.port.toString())
            put("id", config.password ?: "")
            put("aid", "0")
            put("net", "tcp")
            put("type", "none")
            put("host", "")
            put("path", "")
            put("tls", "")
        }
        
        val base64 = android.util.Base64.encodeToString(
            json.toString().toByteArray(),
            android.util.Base64.NO_WRAP
        )
        
        return "vmess://$base64"
    }

    private fun encodeShadowsocksLink(config: VpnConfig): String {
        val userInfo = "${config.method}:${config.password}"
        val base64 = android.util.Base64.encodeToString(
            userInfo.toByteArray(),
            android.util.Base64.NO_WRAP
        )
        
        return "ss://$base64@${config.host}:${config.port}"
    }

    private fun encodeTrojanLink(config: VpnConfig): String {
        return "trojan://${config.password}@${config.host}:${config.port}"
    }

    private fun parseVmessLink(link: String): Result<VpnConfig> {
        return try {
            val base64 = link.removePrefix("vmess://")
            val json = String(android.util.Base64.decode(base64, android.util.Base64.NO_WRAP))
            val obj = JSONObject(json)
            
            Result.success(
                VpnConfig(
                    name = obj.optString("ps", "VMess Config"),
                    protocol = "vmess",
                    host = obj.optString("add", ""),
                    port = obj.optInt("port", 443),
                    password = obj.optString("id", ""),
                    method = obj.optString("net", "tcp"),
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseVlessLink(link: String): Result<VpnConfig> {
        return try {
            val uri = android.net.Uri.parse(link)
            Result.success(
                VpnConfig(
                    name = uri.getQueryParameter("sni") ?: "VLESS Config",
                    protocol = "vless",
                    host = uri.host ?: "",
                    port = uri.port ?: 443,
                    password = uri.userInfo ?: "",
                    method = uri.getQueryParameter("security") ?: "tls",
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseShadowsocksLink(link: String): Result<VpnConfig> {
        return try {
            val uri = android.net.Uri.parse(link)
            var userInfo = uri.userInfo ?: ""
            
            // Try to decode base64 userInfo
            try {
                userInfo = String(android.util.Base64.decode(userInfo, android.util.Base64.NO_WRAP))
            } catch (e: Exception) {
                // Keep as is
            }
            
            val parts = userInfo.split(":")
            
            Result.success(
                VpnConfig(
                    name = "Shadowsocks",
                    protocol = "shadowsocks",
                    host = uri.host ?: "",
                    port = uri.port ?: 8388,
                    password = parts.getOrNull(1),
                    method = parts.getOrNull(0) ?: "auto",
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTrojanLink(link: String): Result<VpnConfig> {
        return try {
            val uri = android.net.Uri.parse(link)
            Result.success(
                VpnConfig(
                    name = "Trojan",
                    protocol = "trojan",
                    host = uri.host ?: "",
                    port = uri.port ?: 443,
                    password = uri.userInfo ?: "",
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
