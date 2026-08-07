// ====================
// BOOT RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BroadcastReceiver that handles BOOT_COMPLETED events to auto-start VPN service
 * when the device boots up (if auto-connect is enabled in settings).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                handleBoot(context)
            }
        }
    }

    private fun handleBoot(context: Context) {
        // Check if auto-connect on boot is enabled in SharedPreferences
        val prefs = context.getSharedPreferences("aerovpn_prefs", Context.MODE_PRIVATE)
        val autoConnectOnBoot = prefs.getBoolean("auto_connect_on_boot", false)
        
        if (autoConnectOnBoot) {
            val lastServerId = prefs.getString("last_server_id", null)
            val lastProtocol = prefs.getString("last_protocol", null)
            
            if (lastServerId != null && lastProtocol != null) {
                // Start VPN service with saved configuration
                val vpnIntent = Intent(context, Class.forName("com.aerovpn.service.AeroVpnService"))
                vpnIntent.action = "com.aerovpn.ACTION_START_VPN"
                vpnIntent.putExtra("server_id", lastServerId)
                vpnIntent.putExtra("protocol", lastProtocol)
                
                try {
                    // Fix: startForegroundService() was added in API 26 (Android 8.0).
                    // Calling it unconditionally throws NoSuchMethodError on Android 7.x
                    // (API 24/25), crashing the app. Fall back to startService() there —
                    // AeroVpnService calls startForeground() itself on those versions.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        @Suppress("DEPRECATION")
                        context.startService(vpnIntent)
                    }
                } catch (e: Exception) {
                    // Service may not start in locked boot state
                    e.printStackTrace()
                }
            }
        }
    }
}
