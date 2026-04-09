package com.aerovpn.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aerovpn.ui.navigation.BottomNavigationBar
import com.aerovpn.ui.navigation.NavigationGraph
import com.aerovpn.ui.navigation.NavigationItem
import com.aerovpn.ui.theme.AeroVPNTheme

enum class ConnectionStatus { CONNECTED, CONNECTING, DISCONNECTED, ERROR }


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
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
