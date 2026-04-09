package com.aerovpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SettingsItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val type: SettingsItemType
)

enum class SettingsItemType {
    NAVIGATION,
    TOGGLE,
    SELECT,
    TEXT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    darkThemeEnabled: Boolean,
    onDarkThemeToggle: (Boolean) -> Unit,
    onBackClick: () -> Unit
) {
    var killSwitchEnabled by remember { mutableStateOf(false) }
    var autoReconnectEnabled by remember { mutableStateOf(true) }
    var showProtocolDialog by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf("Auto") }

    if (showProtocolDialog) {
        ProtocolSelectionDialog(
            selectedProtocol = selectedProtocol,
            onProtocolSelected = {
                selectedProtocol = it
                showProtocolDialog = false
            },
            onDismiss = { showProtocolDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // General Settings Section
            item {
                SettingsSectionTitle("General")
            }

            item {
                SettingsItem(
                    title = "Protocol",
                    description = selectedProtocol,
                    icon = Icons.Default.Shield,
                    onClick = { showProtocolDialog = true }
                )
            }

            item {
                SettingsToggleItem(
                    title = "Kill Switch",
                    description = "Block internet when VPN disconnects",
                    icon = Icons.Default.Lock,
                    checked = killSwitchEnabled,
                    onCheckedChange = { killSwitchEnabled = it }
                )
            }

            item {
                SettingsToggleItem(
                    title = "Auto Reconnect",
                    description = "Automatically reconnect on connection loss",
                    icon = Icons.Default.Refresh,
                    checked = autoReconnectEnabled,
                    onCheckedChange = { autoReconnectEnabled = it }
                )
            }

            // Appearance Section
            item {
                SettingsSectionTitle("Appearance")
            }

            item {
                SettingsToggleItem(
                    title = "Dark Theme",
                    description = "Use dark theme for the app",
                    icon = Icons.Default.Palette,
                    checked = darkThemeEnabled,
                    onCheckedChange = onDarkThemeToggle
                )
            }

            // Privacy Section
            item {
                SettingsSectionTitle("Privacy & Security")
            }

            item {
                SettingsItem(
                    title = "DNS Settings",
                    description = "Configure custom DNS servers",
                    icon = Icons.Default.Dns,
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    title = "IP Leak Protection",
                    description = "Prevent IP and DNS leaks",
                    icon = Icons.Default.Shield,
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    title = "Split Tunneling",
                    description = "Choose which apps use VPN",
                    icon = Icons.Default.CallSplit,
                    onClick = { }
                )
            }

            // Advanced Section
            item {
                SettingsSectionTitle("Advanced")
            }

            item {
                SettingsItem(
                    title = "Connection Logs",
                    description = "View connection history",
                    icon = Icons.Default.Article,
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    title = "App Version",
                    description = "v1.0.0 (Build 1)",
                    icon = Icons.Default.Info,
                    onClick = { }
                )
            }

            // About Section
            item {
                SettingsSectionTitle("About")
            }

            item {
                SettingsItem(
                    title = "Privacy Policy",
                    description = "Read our privacy policy",
                    icon = Icons.Default.Policy,
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    title = "Terms of Service",
                    description = "View terms and conditions",
                    icon = Icons.Default.Gavel,
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    title = "Help & Support",
                    description = "Get help and contact support",
                    icon = Icons.Default.Help,
                    onClick = { }
                )
            }
        }
    }
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingsItem(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun ProtocolSelectionDialog(
    selectedProtocol: String,
    onProtocolSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Protocol") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val protocols = listOf(
                    "Auto" to "Automatically select best protocol",
                    "WireGuard" to "Fast and modern VPN protocol",
                    "V2Ray" to "Advanced proxy protocol (VMess/VLess)",
                    "SSH" to "Secure Shell tunneling",
                    "Shadowsocks" to "Lightweight proxy protocol"
                )

                protocols.forEach { (protocol, description) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProtocolSelected(protocol) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedProtocol == protocol) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedProtocol == protocol,
                                onClick = { onProtocolSelected(protocol) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = protocol,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
