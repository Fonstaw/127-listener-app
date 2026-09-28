package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.NetworkStatus
import com.example.util.ServerReachability

@Composable
fun NetworkConnectionBanner(
    networkStatus: NetworkStatus,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val hasInternet = networkStatus.isInternetAvailable
    val reachability = networkStatus.serverReachability

    // Determine color schemes and indicators based on internet and server state
    val (statusColor, badgeBgColor, statusTitle, statusSubtitle, statusIcon) = when {
        !hasInternet -> {
            CustomStatusTuple(
                color = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                title = "Offline • No Internet Connection",
                subtitle = "Telebirr SMS receipts will be saved locally in Room DB and synced once online.",
                icon = Icons.Default.WifiOff
            )
        }
        reachability == ServerReachability.CHECKING -> {
            CustomStatusTuple(
                color = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                title = "Checking Server Reachability...",
                subtitle = "Internet is active. Testing connection to your configured bot endpoint.",
                icon = Icons.Default.Sync
            )
        }
        reachability == ServerReachability.REACHABLE -> {
            val latencyText = if (networkStatus.latencyMs != null) " • ${networkStatus.latencyMs}ms" else ""
            CustomStatusTuple(
                color = Color(0xFF059669), // Emerald
                containerColor = Color(0xFF059669).copy(alpha = 0.10f),
                title = "Bot Server Online$latencyText",
                subtitle = "Real-time sync active. Receipts will automatically push to your Telegram bot.",
                icon = Icons.Default.CloudDone
            )
        }
        reachability == ServerReachability.NOT_CONFIGURED -> {
            CustomStatusTuple(
                color = Color(0xFFD97706), // Amber
                containerColor = Color(0xFFD97706).copy(alpha = 0.12f),
                title = "Server URL Not Configured",
                subtitle = "Connected to internet. Open Settings to configure your Telegram Bot backend URL and API key.",
                icon = Icons.Default.SettingsSuggest
            )
        }
        reachability == ServerReachability.UNREACHABLE -> {
            CustomStatusTuple(
                color = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                title = "API Server Unreachable",
                subtitle = networkStatus.errorMessage ?: "Server timed out or refused connection. Receipts saved locally.",
                icon = Icons.Default.CloudOff
            )
        }
        else -> {
            CustomStatusTuple(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                title = "Checking Connection...",
                subtitle = "Verifying internet and backend server connectivity.",
                icon = Icons.Default.CloudSync
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("network_status_banner"),
        shape = RoundedCornerShape(12.dp),
        color = badgeBgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.25f)),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Status indicator beacon dot
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(17.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            letterSpacing = 0.1.sp
                        ),
                        color = statusColor,
                        maxLines = 1
                    )
                }

                // Refresh / Recheck Button
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Recheck Connection",
                        tint = statusColor,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // Expand indicator
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle details",
                    tint = statusColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded || reachability == ServerReachability.NOT_CONFIGURED || !hasInternet,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, start = 20.dp, end = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = statusSubtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    if (reachability == ServerReachability.NOT_CONFIGURED || reachability == ServerReachability.UNREACHABLE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onOpenSettings,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Configure API URL", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }

                            TextButton(
                                onClick = onRefresh,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Retry Ping", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class CustomStatusTuple(
    val color: Color,
    val containerColor: Color,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
