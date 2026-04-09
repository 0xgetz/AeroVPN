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
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> {
                handlePowerChange(context, action)
            }
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF -> {
                handleScreenStateChange(context, action)
            }
        }
    }

    private fun handlePowerChange(context: Context, action: String) {
        val isCharging = action == Intent.ACTION_POWER_CONNECTED

        val prefs = context.getSharedPreferences("aerovpn_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_charging", isCharging).apply()
    }

    private fun handleScreenStateChange(context: Context, action: String) {
        val isScreenOn = action == Intent.ACTION_SCREEN_ON

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
