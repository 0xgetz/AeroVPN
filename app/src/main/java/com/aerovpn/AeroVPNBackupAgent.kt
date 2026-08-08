package com.aerovpn

import android.app.backup.BackupAgentHelper
import android.app.backup.SharedPreferencesBackupHelper
import android.util.Log

/**
 * Backup agent for AeroVPN.
 *
 * Registered in AndroidManifest.xml via android:backupAgent=".AeroVPNBackupAgent".
 * Without this class the system throws ClassNotFoundException whenever Android
 * triggers a backup or restore, crashing the app process.
 *
 * AUDIT FIX: the previous helpers referenced "com.aerovpn_preferences" and
 * "aerovpn_vpn_configs" — prefs files that no code in the app ever creates, so
 * the agent silently backed up nothing. The app's real settings file
 * "aerovpn_prefs" also holds the persisted last-VPN-config (credentials!), so it
 * must NOT be registered here; res/xml/backup_rules.xml and
 * res/xml/data_extraction_rules.xml now exclude it from system backup too.
 * Only the non-secret permission-flag prefs are registered below.
 */
class AeroVPNBackupAgent : BackupAgentHelper() {

    companion object {
        private const val TAG = "AeroVPNBackupAgent"

        // Keys used to identify each BackupHelper — must be unique per agent
        private const val PERMISSIONS_BACKUP_KEY = "aerovpn_permissions"

        // SharedPreferences file names (without .xml extension).
        // Only non-secret files are listed here — credentials stay on-device.
        private const val PERMISSIONS_PREFS = "aerovpn_permissions"
    }

    override fun onCreate() {
        Log.d(TAG, "BackupAgent onCreate — registering helpers")

        // Back up the non-secret permission-request flags (allows re-asking
        // runtime permissions after a device restore without re-spamming dialogs).
        addHelper(
            PERMISSIONS_BACKUP_KEY,
            SharedPreferencesBackupHelper(this, PERMISSIONS_PREFS)
        )

        Log.d(TAG, "BackupAgent helpers registered")
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        Log.d(TAG, "Restore finished — preferences restored from backup")
    }
}
