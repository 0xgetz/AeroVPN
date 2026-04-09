package com.aerovpn.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aerovpn.ui.screens.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text

@Composable
fun NavigationGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = NavigationItem.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Home Screen
        composable(NavigationItem.Home.route) {
            HomeScreen(
                connectionStatus = ConnectionStatus.DISCONNECTED, // Should come from ViewModel
                serverInfo = null, // Should come from ViewModel
                onConnectClick = { /* Handle connect */ },
                onDisconnectClick = { /* Handle disconnect */ },
                onServerSelectClick = {
                    navController.navigate(NavigationItem.Servers.route)
                }
            )
        }

        // Server List Screen
        composable(NavigationItem.Servers.route) {
            ServerListScreen(
                servers = emptyList(), // Should come from ViewModel
                searchQuery = "", // Should come from ViewModel
                onSearchQueryChange = { /* Handle search */ },
                onServerClick = { /* Handle server selection */ },
                onFavoriteToggle = { /* Handle favorite toggle */ },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Tools Screen
        composable(NavigationItem.Tools.route) {
            ToolsScreen(
                onToolClick = { tool ->
                    // Navigate to tool detail or open tool
                },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Config Screen
        composable(NavigationItem.Configs.route) {
            ConfigScreen(
                configs = emptyList(), // Should come from ViewModel
                onConfigClick = { config ->
                    // Handle config selection
                },
                onFavoriteToggle = { configId ->
                    // Handle favorite toggle
                },
                onImportClick = {
                    // Handle import
                },
                onExportClick = {
                    // Handle export
                },
                onCreateClick = {
                    // Handle create new config
                },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Settings Screen
        composable(NavigationItem.Settings.route) {
            SettingsScreen(
                darkThemeEnabled = false, // Should come from ViewModel
                onDarkThemeToggle = { enabled ->
                    // Handle theme toggle
                },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Tool Detail Screen (for advanced tools)
        composable(
            route = "tool/{toolId}",
            arguments = listOf(
                navArgument("toolId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val toolId = backStackEntry.arguments?.getString("toolId") ?: return@composable
            ToolDetailScreen(
                toolId = toolId,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Config Detail Screen
        composable(
            route = "config/{configId}",
            arguments = listOf(
                navArgument("configId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val configId = backStackEntry.arguments?.getString("configId") ?: return@composable
            ConfigDetailScreen(
                configId = configId,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
fun ToolDetailScreen(
    toolId: String,
    onBackClick: () -> Unit
) {
    // Placeholder for tool detail screens
    // Each tool (IP Hunter, Ping Tools, DNS Checker, etc.) would have its own implementation
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        androidx.compose.material3.Text(
            text = "Tool: $toolId",
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 20.sp
            )
        )
    }
}

@Composable
fun ConfigDetailScreen(
    configId: String,
    onBackClick: () -> Unit
) {
    // Placeholder for config detail screen
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        androidx.compose.material3.Text(
            text = "Config: $configId",
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 20.sp
            )
        )
    }
}
