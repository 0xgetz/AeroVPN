// ====================
// POWER STATE RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * BroadcastReceiver that monitors power state changes.
 * Used to optimize battery usage and handle screen on/off events.
 */
class PowerStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> {
                handlePowerChange(context, intent.action)
            }
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF -> {
                handleScreenStateChange(context, intent.action)
            }
        }
    }

    private fun handlePowerChange(context: Context, action: String) {
        val isCharging = action == Intent.ACTION_POWER_CONNECTED
        
        // Optionally adjust VPN behavior based on charging state
        val prefs = context.getSharedPreferences("aerovpn_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_charging", isCharging).apply()
    }

    private fun handleScreenStateChange(context: Context, action: String) {
        val isScreenOn = action == Intent.ACTION_SCREEN_ON
        
        // Screen off: potentially reduce keep-alive frequency to save battery
        // Screen on: restore normal keep-alive frequency
        val vpnIntent = Intent(context, Class.forName("com.aerovpn.service.AeroVpnService"))
        vpnIntent.action = "com.aerovpn.ACTION_SCREEN_STATE_CHANGED"
        vpnIntent.putExtra("screen_on", isScreenOn)
        
        try {
            context.startService(vpnIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
