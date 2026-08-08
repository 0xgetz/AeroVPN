// ====================
// BOOT RECEIVER
// ====================
package com.aerovpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aerovpn.service.AeroVpnService

/**
 * BroadcastReceiver that handles BOOT_COMPLETED events to auto-start VPN service
 * when the device boots up (if auto-connect is enabled in settings).
 *
 * AUDIT FIX: this receiver is intentionally NOT directBootAware and no longer
 * handles LOCKED_BOOT_COMPLETED. The app's settings live in credential-encrypted
 * storage which is unavailable before the user unlocks the device — reading
 * SharedPreferences during LOCKED_BOOT_COMPLETED threw IllegalStateException and
 * crashed the process on every pre-unlock boot on API 24+. The system only
 * delivers BOOT_COMPLETED/QUICKBOOT_POWERON to non-direct-boot-aware receivers
 * after first unlock, so auto-connect now happens safely.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                handleBoot(context)
            }
        }
    }

    private fun handleBoot(context: Context) {
        // Check if auto-connect on boot is enabled in SharedPreferences.
        // Defensive try/catch: never crash the boot receiver over prefs I/O.
        val autoConnectOnBoot = try {
            context.getSharedPreferences("aerovpn_prefs", Context.MODE_PRIVATE)
                .getBoolean("auto_connect_on_boot", false)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        // M2 FIX: hand off to the service, which restores the full config it
        // persisted on the last connect(). The old code sent a custom
        // ACTION_START_VPN with server_id/protocol extras that AeroVpnService
        // never handled, so boot auto-connect was dead code.
        if (autoConnectOnBoot) {
            val vpnIntent = Intent(context, AeroVpnService::class.java)
            vpnIntent.action = AeroVpnService.ACTION_RESTORE

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
