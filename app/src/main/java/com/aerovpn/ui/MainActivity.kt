package com.aerovpn.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aerovpn.ui.navigation.BottomNavigationBar
import com.aerovpn.ui.navigation.NavigationGraph
import com.aerovpn.ui.navigation.NavigationItem
import com.aerovpn.ui.theme.AeroVPNTheme

// NOTE: the canonical ConnectionStatus enum lives in
// com.aerovpn.ui.screens (HomeScreen.kt). A duplicate enum here used to
// shadow it for anyone importing com.aerovpn.ui.* — removed.

/**
 * Compose-observable snapshot of the runtime permissions the app cares about.
 *
 * Hoisted in MainActivity (which owns the ActivityResultLaunchers) and passed
 * down to the Settings screen so users can grant or repair permissions in-app.
 */
class PermissionUiState {
    /** POST_NOTIFICATIONS - runtime permission on Android 13+ (API 33). */
    var notificationsGranted by mutableStateOf(false)

    /** True when the system dialog can still be shown (never asked, or can re-ask). */
    var notificationsCanAsk by mutableStateOf(true)

    /** BLUETOOTH_CONNECT + BLUETOOTH_SCAN - runtime permissions on Android 12+ (API 31). */
    var bluetoothGranted by mutableStateOf(false)
    var bluetoothCanAsk by mutableStateOf(true)
}

class MainActivity : ComponentActivity() {

    private val permissionPrefs: SharedPreferences by lazy {
        getSharedPreferences("aerovpn_permissions", MODE_PRIVATE)
    }

    private val permissionUi = PermissionUiState()

    // Notification permission launcher (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshPermissionState()
    }

    // Bluetooth permissions launcher (API 31+)
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        refreshPermissionState()
        requestNotificationsOnFirstLaunch()

        setContent {
            AeroVPNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AeroVPNApp(
                        permissionUi = permissionUi,
                        onRequestNotifications = ::requestNotifications,
                        onRequestBluetooth = ::requestBluetooth,
                        onOpenAppSettings = ::openAppSettings,
                        onOpenNotificationSettings = ::openNotificationSettings
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from the system permission / settings screens
        refreshPermissionState()
    }

    // ------------------------------------------------------------------
    // Permission state
    // ------------------------------------------------------------------

    private fun refreshPermissionState() {
        permissionUi.notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        permissionUi.notificationsCanAsk =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                canStillAsk(Manifest.permission.POST_NOTIFICATIONS, PREFS_NOTIFICATIONS_ASKED)

        permissionUi.bluetoothGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED)
        permissionUi.bluetoothCanAsk =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (canStillAsk(Manifest.permission.BLUETOOTH_CONNECT, PREFS_BLUETOOTH_ASKED) &&
                    canStillAsk(Manifest.permission.BLUETOOTH_SCAN, PREFS_BLUETOOTH_ASKED))
    }

    /**
     * True if the runtime dialog can still be shown for [permission]:
     *  - never asked before, or
     *  - previously denied but the system still allows re-asking
     *    (shouldShowRequestPermissionRationale() == true).
     * False once the user picked "Don't ask again" - the only remaining path
     * is the system app-settings page, so the UI shows an "Open Settings" action.
     */
    private fun canStillAsk(permission: String, askedFlag: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val askedBefore = permissionPrefs.getBoolean(askedFlag, false)
        return !askedBefore || shouldShowRequestPermissionRationale(permission)
    }

    // ------------------------------------------------------------------
    // Permission requests
    // ------------------------------------------------------------------

    /**
     * Notifications back the VPN foreground service on Android 13+, so ask once
     * automatically on first launch. Afterwards the user manages this from the
     * Permissions section in Settings - never re-spam the dialog.
     */
    private fun requestNotificationsOnFirstLaunch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        if (permissionPrefs.getBoolean(PREFS_NOTIFICATIONS_ASKED, false)) return
        requestNotifications()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionPrefs.edit().putBoolean(PREFS_NOTIFICATIONS_ASKED, true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Bluetooth is optional (connection sharing) and is intentionally NOT
     * auto-requested at startup - the user opts in from Settings.
     */
    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionPrefs.edit().putBoolean(PREFS_BLUETOOTH_ASKED, true).apply()
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // System settings deep links (fallback once the dialog can't be shown)
    // ------------------------------------------------------------------

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    companion object {
        private const val PREFS_NOTIFICATIONS_ASKED = "notification_permission_asked"
        private const val PREFS_BLUETOOTH_ASKED = "bluetooth_permission_asked"
    }
}

@Composable
fun AeroVPNApp(
    permissionUi: PermissionUiState = PermissionUiState(),
    onRequestNotifications: () -> Unit = {},
    onRequestBluetooth: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {}
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Determine if bottom nav should be visible
    val isBottomNavVisible = currentRoute in NavigationItem.items.map { it.route }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Main Content
        Box(
            modifier = Modifier.weight(1f)
        ) {
            NavigationGraph(
                navController = navController,
                modifier = Modifier.fillMaxSize(),
                permissionUi = permissionUi,
                onRequestNotifications = onRequestNotifications,
                onRequestBluetooth = onRequestBluetooth,
                onOpenAppSettings = onOpenAppSettings,
                onOpenNotificationSettings = onOpenNotificationSettings
            )
        }

        // Bottom Navigation Bar
        BottomNavigationBar(
            navController = navController,
            isVisible = isBottomNavVisible
        )
    }
}

@Composable
fun AeroVPNAppPreview() {
    AeroVPNTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AeroVPNApp()
        }
    }
}
