package com.aerovpn

import android.app.Application
import androidx.multidex.MultiDex
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration

class AeroVPNApplication : Application(), Configuration.Provider {

    // Fix #22: MultiDex support for large DEX method count
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // Fix #6: removed duplicate initializeWorkManager() call — WorkManager is already
        // initialized via androidx.startup.InitializationProvider in AndroidManifest.xml,
        // which uses workManagerConfiguration below. Calling WorkManager.initialize() again
        // here would cause an IllegalStateException on Android 12+.
    }

    override val workManagerConfiguration: Configuration get() {
        return Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // VPN Service channel
            val vpnChannel = NotificationChannel(
                VPN_CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannel(vpnChannel)
        }
    }

    companion object {
        const val VPN_CHANNEL_ID = "aerovpn_service_channel"
        const val VPN_NOTIFICATION_ID = 1001
        
        @Volatile
        private var instance: AeroVPNApplication? = null
        
        fun getInstance(): AeroVPNApplication {
            return instance ?: throw IllegalStateException(
                "Application not initialized properly"
            )
        }
    }
}
