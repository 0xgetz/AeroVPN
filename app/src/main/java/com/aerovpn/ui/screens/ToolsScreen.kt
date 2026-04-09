package com.aerovpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ToolItem(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onToolClick: (ToolItem) -> Unit,
    onBackClick: () -> Unit
) {
    val tools = listOf(
        ToolItem(
            id = "ip_hunter",
            name = "IP Hunter",
            description = "Find and analyze IP addresses",
            icon = Icons.Default.Search,
            color = Color(0xFF2196F3)
        ),
        ToolItem(
            id = "ping_tools",
            name = "Ping Tools",
            description = "Test server connectivity and latency",
            icon = Icons.Default.Speed,
            color = Color(0xFF4CAF50)
        ),
        ToolItem(
            id = "dns_checker",
            name = "DNS Checker",
            description = "Check DNS leaks and resolve issues",
            icon = Icons.Default.Dns,
            color = Color(0xFF9C27B0)
        ),
        ToolItem(
            id = "web_checker",
            name = "Web Checker",
            description = "Verify website accessibility",
            icon = Icons.Default.Language,
            color = Color(0xFFFF9800)
        ),
        ToolItem(
            id = "port_scanner",
            name = "Port Scanner",
            description = "Scan open ports on servers",
            icon = Icons.Default.Scanner,
            color = Color(0xFFF44336)
        ),
        ToolItem(
            id = "traceroute",
            name = "Traceroute",
            description = "Trace network path to destination",
            icon = Icons.Default.Route,
            color = Color(0xFF00BCD4)
        ),
        ToolItem(
            id = "connection_test",
            name = "Connection Test",
            description = "Test download and upload speeds",
            icon = Icons.Default.TrendingUp,
            color = Color(0xFF673AB7)
        ),
        ToolItem(
            id = "kill_switch",
            name = "Kill Switch",
            description = "Configure internet kill switch",
            icon = Icons.Default.Lock,
            color = Color(0xFF795548)
        ),
        ToolItem(
            id = "split_tunnel",
            name = "Split Tunnel",
            description = "Configure per-app routing",
            icon = Icons.Default.CallSplit,
            color = Color(0xFF607D8B)
        ),
        ToolItem(
            id = "firewall",
            name = "Firewall",
            description = "Configure app firewall rules",
            icon = Icons.Default.Shield,
            color = Color(0xFF3F51B5)
        )
    )

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
                    text = "Advanced Tools",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Tools Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(tools, key = { it.id }) { tool ->
                ToolGridItem(
                    tool = tool,
                    onClick = { onToolClick(tool) }
                )
            }
        }
    }
}

@Composable
fun ToolGridItem(
    tool: ToolItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon with background
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(
                        color = tool.color.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.name,
                    modifier = Modifier.size(32.dp),
                    tint = tool.color
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tool Name
            Text(
                text = tool.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Tool Description
            Text(
                text = tool.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 2
            )
        }
    }
}
