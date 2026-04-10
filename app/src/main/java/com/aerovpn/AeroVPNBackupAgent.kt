package com.aerovpn

import android.app.backup.BackupAgentHelper
import android.app.backup.SharedPreferencesBackupHelper
import android.app.backup.FileBackupHelper
import android.util.Log

/**
 * Backup agent for AeroVPN.
 *
 * Registered in AndroidManifest.xml via android:backupAgent=".AeroVPNBackupAgent".
 * Without this class the system throws ClassNotFoundException whenever Android
 * triggers a backup or restore, crashing the app process.
 *
 * We back up:
 *  - Default SharedPreferences (user settings, theme, etc.)
 *  - The VPN configs shared-prefs file (stored by ExportImportTool / ConfigScreen)
 *
 * Sensitive data (private keys, passwords) should be excluded via
 * res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml.
 */
class AeroVPNBackupAgent : BackupAgentHelper() {

    companion object {
        private const val TAG = "AeroVPNBackupAgent"

        // Keys used to identify each BackupHelper — must be unique per agent
        private const val PREFS_BACKUP_KEY = "aerovpn_prefs"
        private const val CONFIG_PREFS_BACKUP_KEY = "aerovpn_config_prefs"

        // SharedPreferences file names (without .xml extension)
        private const val DEFAULT_PREFS = "com.aerovpn_preferences"
        private const val VPN_CONFIG_PREFS = "aerovpn_vpn_configs"
    }

    override fun onCreate() {
        Log.d(TAG, "BackupAgent onCreate — registering helpers")

        // Back up default app preferences
        addHelper(
            PREFS_BACKUP_KEY,
            SharedPreferencesBackupHelper(this, DEFAULT_PREFS)
        )

        // Back up VPN server configurations
        addHelper(
            CONFIG_PREFS_BACKUP_KEY,
            SharedPreferencesBackupHelper(this, VPN_CONFIG_PREFS)
        )

        Log.d(TAG, "BackupAgent helpers registered")
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        Log.d(TAG, "Restore finished — preferences restored from backup")
    }
}
