// ====================
// PACKAGE UPDATE RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BroadcastReceiver that handles app update events.
 * Used to restart VPN service after app update if it was active.
 */
class PackageUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                handlePackageUpdate(context)
            }
        }
    }

    private fun handlePackageUpdate(context: Context) {
        val prefs = context.getSharedPreferences("aerovpn_prefs", Context.MODE_PRIVATE)
        val wasConnected = prefs.getBoolean("vpn_was_connected", false)
        
        if (wasConnected) {
            // Restore VPN connection after update
            val vpnIntent = Intent(context, Class.forName("com.aerovpn.service.AeroVpnService"))
            vpnIntent.action = "com.aerovpn.ACTION_RESTORE_CONNECTION"
            
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
                e.printStackTrace()
            }
        }
        
        // Clear the flag
        prefs.edit().putBoolean("vpn_was_connected", false).apply()
    }
}
