package com.aerovpn.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aerovpn.AeroVPNApplication
import com.aerovpn.R
import com.aerovpn.service.protocol.ConnectionState
import com.aerovpn.service.protocol.ProtocolConfig
import com.aerovpn.service.protocol.ProtocolHandler
import com.aerovpn.service.protocol.ProtocolType
import com.aerovpn.service.protocol.SSHConfig
import com.aerovpn.service.protocol.SSHProtocol
import com.aerovpn.service.protocol.ShadowsocksConfig
import com.aerovpn.service.protocol.ShadowsocksProtocol
import com.aerovpn.service.protocol.UdpTunnelConfig
import com.aerovpn.service.protocol.UdpTunnelProtocol
import com.aerovpn.service.protocol.V2RayConfig
import com.aerovpn.service.protocol.V2RayProtocol
import com.aerovpn.service.protocol.WireGuardConfig
import com.aerovpn.service.protocol.WireGuardProtocol
import com.aerovpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Main VPN service that manages VPN connection lifecycle.
 * Handles protocol switching, connection state, and notifications.
 */
class AeroVpnService : VpnService() {

    private val binder = LocalBinder()
    // Fix #23: SupervisorJob ensures child coroutine failures don't cancel the whole scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var activeProtocolHandler: ProtocolHandler? = null

    inner class LocalBinder : Binder() {
        fun getService(): AeroVpnService = this@AeroVpnService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Fix #7: type-safe getSerializableExtra for API 33+
                val config: ProtocolConfig? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CONFIG, ProtocolConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CONFIG) as? ProtocolConfig
                }
                if (config != null) {
                    // Fix #2: startForeground with foregroundServiceType connectedDevice
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            AeroVPNApplication.VPN_NOTIFICATION_ID,
                            buildNotification(ConnectionState.Connecting),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        )
                    } else {
                        startForeground(
                            AeroVPNApplication.VPN_NOTIFICATION_ID,
                            buildNotification(ConnectionState.Connecting)
                        )
                    }
                    connect(config)
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
            else -> {
                // Fix #2: startForeground with foregroundServiceType connectedDevice
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        AeroVPNApplication.VPN_NOTIFICATION_ID,
                        buildNotification(ConnectionState.Idle),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(
                        AeroVPNApplication.VPN_NOTIFICATION_ID,
                        buildNotification(ConnectionState.Idle)
                    )
                }
            }
        }
        return START_STICKY
    }

    fun connect(config: ProtocolConfig) {
        serviceScope.launch {
            _connectionState.value = ConnectionState.Connecting
            updateNotification(ConnectionState.Connecting)

            try {
                val handler = getProtocolHandler(config)
                activeProtocolHandler = handler

                val vpnBuilder = Builder()
                    .setSession("AeroVPN")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)

                val connected = handler.connect(config, vpnBuilder)

                if (connected) {
                    _connectionState.value = ConnectionState.Connected
                    updateNotification(ConnectionState.Connected)
                } else {
                    _connectionState.value = ConnectionState.Error("Connection failed")
                    updateNotification(ConnectionState.Error("Connection failed"))
                }
            } catch (e: Exception) {
                val errorState = ConnectionState.Error(e.message ?: "Unknown error", e)
                _connectionState.value = errorState
                updateNotification(errorState)
            }
        }
    }

    fun disconnect() {
        serviceScope.launch {
            _connectionState.value = ConnectionState.Disconnecting
            updateNotification(ConnectionState.Disconnecting)

            try {
                activeProtocolHandler?.disconnect()
                activeProtocolHandler = null
            } catch (e: Exception) {
                // ignore
            } finally {
                _connectionState.value = ConnectionState.Idle
                updateNotification(ConnectionState.Idle)
                stopSelf()
            }
        }
    }

    fun activateKillSwitch() {
        // Fix #20: setBlocking(true) requires API 29+ — guard with SDK version check
        try {
            val builder = Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setBlocking(true)
            }
            builder.setSession("AeroVPN-KillSwitch")
            builder.addAddress("10.0.0.2", 24)
            builder.addRoute("0.0.0.0", 0)
            builder.establish()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to activate kill switch", e)
        }
    }

    // Fix #8: proper getProtocolHandler returning real handlers for all protocols
    private fun getProtocolHandler(config: ProtocolConfig): ProtocolHandler {
        return when (config) {
            is WireGuardConfig -> WireGuardProtocol()
            is V2RayConfig -> V2RayProtocol()
            is SSHConfig -> SSHProtocol()
            is ShadowsocksConfig -> ShadowsocksProtocol()
            is UdpTunnelConfig -> UdpTunnelProtocol()
            else -> throw IllegalArgumentException(
                "Unsupported protocol config type: ${config.javaClass.simpleName}"
            )
        }
    }

    private fun buildNotification(state: ConnectionState): Notification {
        // Fix #3: FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when (state) {
            is ConnectionState.Connected -> getString(R.string.status_connected)
            is ConnectionState.Connecting -> getString(R.string.status_connecting)
            is ConnectionState.Disconnecting -> "Disconnecting..."
            is ConnectionState.Error -> "Error: ${state.message}"
            is ConnectionState.Reconnecting -> "Reconnecting (${state.attempt}/${state.maxAttempts})..."
            else -> getString(R.string.status_disconnected)
        }

        return NotificationCompat.Builder(this, AeroVPNApplication.VPN_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH) // Fix #10: ensure heads-up on API 24-25
            .build()
    }

    private fun updateNotification(state: ConnectionState) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(AeroVPNApplication.VPN_NOTIFICATION_ID, buildNotification(state))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AeroVpnService"
        const val ACTION_CONNECT = "com.aerovpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.aerovpn.action.DISCONNECT"
        const val EXTRA_CONFIG = "extra_config"
    }
}
