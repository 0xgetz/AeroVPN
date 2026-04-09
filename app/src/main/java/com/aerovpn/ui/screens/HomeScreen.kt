package com.aerovpn.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.scale

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class ServerInfo(
    val name: String,
    val country: String,
    val flag: String,
    val ping: Int,
    val load: Int
)

@Composable
fun HomeScreen(
    connectionStatus: ConnectionStatus,
    serverInfo: ServerInfo?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onServerSelectClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val connectButtonScale by animateFloatAsState(
        targetValue = if (connectionStatus == ConnectionStatus.CONNECTING) 0.9f else 1f,
        animationSpec = spring(),
        label = "connectButtonScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection Status Header
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = when (connectionStatus) {
                ConnectionStatus.DISCONNECTED -> "Ready to Connect"
                ConnectionStatus.CONNECTING -> "Connecting..."
                ConnectionStatus.CONNECTED -> "Connected"
                ConnectionStatus.RECONNECTING -> "Reconnecting..."
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Large Connect Button with Animation
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .scale(if (connectionStatus == ConnectionStatus.CONNECTED) pulseScale else connectButtonScale)
        ) {
            // Background pulse effect when connected
            if (connectionStatus == ConnectionStatus.CONNECTED) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF4CAF50).copy(alpha = 0.3f),
                                    Color(0xFF4CAF50).copy(alpha = 0.1f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
            }

            // Main Connect Button
            Button(
                onClick = {
                    if (connectionStatus == ConnectionStatus.CONNECTED) {
                        onDisconnectClick()
                    } else {
                        onConnectClick()
                    }
                },
                modifier = Modifier
                    .size(200.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        clip = true
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (connectionStatus) {
                        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.primary
                        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
                        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                        ConnectionStatus.RECONNECTING -> MaterialTheme.colorScheme.tertiary
                    }
                ),
                shape = CircleShape,
                enabled = connectionStatus != ConnectionStatus.CONNECTING
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when (connectionStatus) {
                            ConnectionStatus.DISCONNECTED -> Icons.Default.Power
                            ConnectionStatus.CONNECTING -> Icons.Default.Refresh
                            ConnectionStatus.CONNECTED -> Icons.Default.Check
                            ConnectionStatus.RECONNECTING -> Icons.Default.Refresh
                        },
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (connectionStatus) {
                            ConnectionStatus.DISCONNECTED -> "CONNECT"
                            ConnectionStatus.CONNECTING -> "CONNECTING"
                            ConnectionStatus.CONNECTED -> "CONNECTED"
                            ConnectionStatus.RECONNECTING -> "RECONNECTING"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Server Info Card
        if (serverInfo != null || connectionStatus != ConnectionStatus.CONNECTED) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Server Flag
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = serverInfo?.flag ?: "🌐",
                            fontSize = 32.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Server Details
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = serverInfo?.name ?: "Select Server",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = serverInfo?.country ?: "No server selected",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        if (serverInfo != null) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Ping",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${serverInfo.ping}ms",
                                    fontSize = 12.sp,
                                    color = if (serverInfo.ping < 100) Color(0xFF4CAF50) else Color(0xFFFF9800)
                                )
                            }
                        }
                    }

                    // Change Server Button
                    IconButton(
                        onClick = onServerSelectClick
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Change Server",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection Stats
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Connection Stats",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ConnectionStat(
                            icon = Icons.Default.ArrowDownward,
                            label = "Download",
                            value = "24.5 MB/s"
                        )
                        ConnectionStat(
                            icon = Icons.Default.ArrowUpward,
                            label = "Upload",
                            value = "8.2 MB/s"
                        )
                        ConnectionStat(
                            icon = Icons.Default.Timeline,
                            label = "Duration",
                            value = "00:15:32"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStat(
    icon: ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
