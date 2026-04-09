// ====================
// PACKAGE UPDATE RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
                context.startForegroundService(vpnIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Clear the flag
        prefs.edit().putBoolean("vpn_was_connected", false).apply()
    }
}
