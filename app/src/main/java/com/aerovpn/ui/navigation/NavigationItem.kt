package com.aerovpn.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    object Home : NavigationItem(
        route = "home",
        label = "Home",
        icon = Icons.Default.Home,
        selectedIcon = Icons.Filled.Home
    )

    object Servers : NavigationItem(
        route = "servers",
        label = "Servers",
        icon = Icons.Default.Dns,
        selectedIcon = Icons.Filled.Dns
    )

    object Tools : NavigationItem(
        route = "tools",
        label = "Tools",
        icon = Icons.Default.Build,
        selectedIcon = Icons.Filled.Build
    )

    object Configs : NavigationItem(
        route = "configs",
        label = "Configs",
        icon = Icons.Default.Folder,
        selectedIcon = Icons.Filled.Folder
    )

    object Settings : NavigationItem(
        route = "settings",
        label = "Settings",
        icon = Icons.Default.Settings,
        selectedIcon = Icons.Filled.Settings
    )

    companion object {
        val items = listOf(Home, Servers, Tools, Configs, Settings)
    }
}
