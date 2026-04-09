package com.aerovpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager

class AeroVPNApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        initializeWorkManager()
    }

    override fun getWorkManagerConfiguration(): Configuration {
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

    private fun initializeWorkManager() {
        WorkManager.initialize(this, workManagerConfiguration)
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
