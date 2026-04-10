package com.aerovpn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

enum class ConnectionStatus { CONNECTED, CONNECTING, DISCONNECTED, ERROR }


class MainActivity : ComponentActivity() {

    // Fix #9: Runtime permission launcher for POST_NOTIFICATIONS (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permission results handled silently — notifications are non-critical for core VPN function
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fix #9: Request POST_NOTIFICATIONS at runtime for Android 13+ (API 33+)
        // Fix #12: Request BLUETOOTH_CONNECT and BLUETOOTH_SCAN only on API 31+
        requestRuntimePermissions()

        setContent {
            AeroVPNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AeroVPNApp()
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Fix #9: POST_NOTIFICATIONS requires runtime permission on API 33+ (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Fix #12: BLUETOOTH_CONNECT and BLUETOOTH_SCAN are runtime permissions on API 31+ (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            notificationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun AeroVPNApp() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Determine if bottom nav should be visible
    val isBottomNavVisible = currentRoute in NavigationItem.items.map { it.route }

    // Connection status - should come from ViewModel
    var connectionStatus by remember { mutableStateOf(ConnectionStatus.DISCONNECTED) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Main Content
        Box(
            modifier = Modifier.weight(1f)
        ) {
            NavigationGraph(
                navController = navController,
                modifier = Modifier.fillMaxSize()
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
