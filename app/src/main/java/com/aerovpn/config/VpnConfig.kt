package com.aerovpn.config

import java.io.Serializable
import java.util.UUID

/**
 * Unified VPN configuration model used across export/import and UI layers.
 */
data class VpnConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String,
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val password: String? = null,
    val configData: String? = null,
    val method: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val lastUsed: String = "",
    // Legacy/compat field
    val server: String = host
) : Serializable
