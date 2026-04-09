package com.aerovpn.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application info for split tunneling
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isSystemApp: Boolean,
    val isInstalled: Boolean,
    val dataUsage: Long = 0L,
    val isSelected: Boolean = false
)

/**
 * Split tunneling mode
 */
enum class SplitTunnelMode {
    EXCLUDE_SELECTED,    // Route all apps through VPN except selected
    INCLUDE_SELECTED,    // Route only selected apps through VPN
    ALL_APPS             // Route all apps through VPN (default)
}

/**
 * Apps filter result
 */
data class AppsFilterResult(
    val apps: List<AppInfo>,
    val totalCount: Int,
    val systemAppsCount: Int,
    val userAppsCount: Int,
    val filteredCount: Int
)

/**
 * AppsFilterTool - Split tunneling app selector
 * Manage which apps use VPN connection
 */
object AppsFilterTool {

    /**
     * Get all installed apps with info
     */
    suspend fun getAllInstalledApps(context: Context): AppsFilterResult = withContext(Dispatchers.IO) {
        try {
            val packageManager = context.packageManager
            val apps = mutableListOf<AppInfo>()
            
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            installedApps.forEach { appInfo ->
                val appName = appInfo.loadLabel(packageManager).toString()
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                apps.add(
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appName,
                        icon = appInfo.loadIcon(packageManager),
                        isSystemApp = isSystemApp,
                        isInstalled = true
                    )
                )
            }
            
            val sortedApps = apps.sortedWith(
                compareByDescending<AppInfo> { !it.isSystemApp }
                    .thenBy { it.appName.lowercase() }
            )
            
            AppsFilterResult(
                apps = sortedApps,
                totalCount = sortedApps.size,
                systemAppsCount = sortedApps.count { it.isSystemApp },
                userAppsCount = sortedApps.count { !it.isSystemApp },
                filteredCount = sortedApps.size
            )
        } catch (e: Exception) {
            AppsFilterResult(
                apps = emptyList(),
                totalCount = 0,
                systemAppsCount = 0,
                userAppsCount = 0,
                filteredCount = 0
            )
        }
    }

    /**
     * Get apps filtered by search query
     */
    suspend fun filterApps(
        context: Context,
        query: String,
        showSystemApps: Boolean = false
    ): AppsFilterResult = withContext(Dispatchers.IO) {
        val allApps = getAllInstalledApps(context)
        
        val filtered = allApps.apps.filter { app ->
            val matchesQuery = query.isEmpty() || 
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            
            val matchesSystemFilter = showSystemApps || !app.isSystemApp
            
            matchesQuery && matchesSystemFilter
        }
        
        AppsFilterResult(
            apps = filtered,
            totalCount = allApps.totalCount,
            systemAppsCount = allApps.systemAppsCount,
            userAppsCount = allApps.userAppsCount,
            filteredCount = filtered.size
        )
    }

    /**
     * Get recommended apps to exclude from VPN
     */
    suspend fun getRecommendedExclusions(context: Context): List<String> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        
        // Common apps that should bypass VPN
        val recommendedExclude = listOf(
            // Local network apps
            "com.android.localtransport",
            "com.android.bluetooth",
            "com.google.android.gms",  // Google Play Services
            "com.android.vending",      // Google Play Store
            
            // Banking apps (often block VPNs)
            "com.paypal.android.p2pmobile",
            
            // Streaming services (geo-restricted content)
            "com.netflix.mediaclient",
            "com.hulu.plus",
            "com.spotify.music",
            
            // Local casting/streaming
            "com.google.android.cast",
            "com.google.android.apps.mediasharing"
        )
        
        // Filter to only installed apps
        recommendedExclude.filter { packageName ->
            try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Get apps that commonly work better with VPN
     */
    fun getRecommendedInclusions(): List<String> {
        return listOf(
            // Torrent clients
            "com.transmissionbt.android",
            "org.deluge.droid",
            
            // Privacy-focused browsers
            "org.torproject.torbrowser",
            "org.mozilla.focus",
            
            // Secure messaging
            "org.thoughtcrime.securesms",  // Signal
            "im.vector.app",               // Element
            
            // Privacy tools
            "org.torproject.android",      // Orbot
            "com.duckduckgo.mobile.android"
        )
    }

    /**
     * Search apps by name
     */
    suspend fun searchApps(
        context: Context,
        query: String
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        filterApps(context, query).apps
    }

    /**
     * Get app info by package name
     */
    suspend fun getAppInfo(
        context: Context,
        packageName: String
    ): AppInfo? = withContext(Dispatchers.IO) {
        try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            
            AppInfo(
                packageName = packageName,
                appName = appInfo.loadLabel(packageManager).toString(),
                icon = appInfo.loadIcon(packageManager),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isInstalled = true
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Check if app is a system app
     */
    fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    /**
     * Get apps by category
     */
    suspend fun getAppsByCategory(
        context: Context,
        category: AppCategory
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val allApps = getAllInstalledApps(context)
        
        when (category) {
            AppCategory.SYSTEM -> allApps.apps.filter { it.isSystemApp }
            AppCategory.USER -> allApps.apps.filter { !it.isSystemApp }
            AppCategory.ALL -> allApps.apps
            AppCategory.SOCIAL -> filterByCategory(context, allApps.apps, socialApps())
            AppCategory.BANKING -> filterByCategory(context, allApps.apps, bankingApps())
            AppCategory.STREAMING -> filterByCategory(context, allApps.apps, streamingApps())
            AppCategory.GAMING -> filterByCategory(context, allApps.apps, gamingApps())
        }
    }

    private fun filterByCategory(
        context: Context,
        apps: List<AppInfo>,
        packageNames: List<String>
    ): List<AppInfo> {
        return apps.filter { it.packageName in packageNames }
    }

    private fun socialApps(): List<String> = listOf(
        "com.facebook.katana",
        "com.instagram.android",
        "com.twitter.android",
        "com.whatsapp",
        "com.facebook.orca"  // Messenger
    )

    private fun bankingApps(): List<String> = listOf(
        "com.paypal.android.p2pmobile",
        "com.coinbase.android",
        "com.binance.dev"
    )

    private fun streamingApps(): List<String> = listOf(
        "com.netflix.mediaclient",
        "com.spotify.music",
        "com.hulu.plus",
        "com.amazon.avod.thirdpartyclient"  // Prime Video
    )

    private fun gamingApps(): List<String> = listOf(
        "com.supercell.clashofclans",
        "com.king.candycrushsaga",
        "com.mojang.minecraftpe"
    )
}

enum class AppCategory {
    ALL,
    SYSTEM,
    USER,
    SOCIAL,
    BANKING,
    STREAMING,
    GAMING
}
