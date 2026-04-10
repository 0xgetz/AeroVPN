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
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aerovpn.AeroVPNApplication
import com.aerovpn.R
import com.aerovpn.receiver.NetworkStateReceiver
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
 *
 * CRITICAL FIX (C2): NetworkStateReceiver is now registered programmatically here
 * (onCreate/onDestroy) so that SCREEN_ON/OFF and network change callbacks are
 * actually delivered. The manifest entry for NetworkStateReceiver has been removed.
 *
 * CRITICAL FIX (kill switch): activateKillSwitch() now stores the ParcelFileDescriptor
 * returned by establish() so the blocking interface is not immediately GC'd.
 *
 * CRITICAL FIX (onDestroy): disconnect() is called on the active handler to ensure
 * resources (SSH sessions, UDP sockets, threads) are cleaned up when the system kills
 * the service from outside.
 */
class AeroVpnService : VpnService() {

    companion object {
        private const val TAG = "AeroVpnService"
        const val ACTION_CONNECT = "com.aerovpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.aerovpn.action.DISCONNECT"
        const val EXTRA_CONFIG = "extra_config"
    }

    private val binder = LocalBinder()

    // Fix #23: SupervisorJob ensures child coroutine failures don't cancel the whole scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var activeProtocolHandler: ProtocolHandler? = null

    // CRITICAL FIX (C2): programmatic receiver — replaces the broken manifest entry
    private val networkStateReceiver = NetworkStateReceiver()

    // CRITICAL FIX (kill switch): store the PFD so it is not garbage-collected
    private var killSwitchPfd: ParcelFileDescriptor? = null

    // Last config used — needed for reconnect after network change
    @Volatile
    private var lastConfig: ProtocolConfig? = null

    inner class LocalBinder : Binder() {
        fun getService(): AeroVpnService = this@AeroVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // CRITICAL FIX (C2): register receiver programmatically so all intents are delivered
        networkStateReceiver.register(this)
        Log.d(TAG, "AeroVpnService created, NetworkStateReceiver registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config: ProtocolConfig? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getSerializableExtra(EXTRA_CONFIG, ProtocolConfig::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getSerializableExtra(EXTRA_CONFIG) as? ProtocolConfig
                    }
                if (config != null) {
                    startForegroundCompat(ConnectionState.Connecting)
                    connect(config)
                }
            }

            ACTION_DISCONNECT -> {
                disconnect()
            }

            // CRITICAL FIX (C2): handle network-change action sent by NetworkStateReceiver
            NetworkStateReceiver.ACTION_NETWORK_CHANGED -> {
                val isConnected = intent.getBooleanExtra("is_connected", false)
                val networkType = intent.getStringExtra("network_type") ?: "Unknown"
                Log.d(TAG, "Network changed: connected=$isConnected type=$networkType")
                if (isConnected && _connectionState.value is ConnectionState.Error) {
                    // Auto-reconnect on network recovery if we were in error state
                    lastConfig?.let { cfg ->
                        Log.i(TAG, "Network restored — attempting reconnect")
                        connect(cfg)
                    }
                } else if (!isConnected) {
                    // Mark as error so UI can reflect loss of network
                    if (_connectionState.value is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Error("Network lost")
                        updateNotification(ConnectionState.Error("Network lost"))
                    }
                }
            }

            NetworkStateReceiver.ACTION_SCREEN_STATE_CHANGED -> {
                val screenOn = intent.getBooleanExtra("screen_on", true)
                Log.d(TAG, "Screen state changed: on=$screenOn")
                // Could implement wake-lock adjustments here
            }

            NetworkStateReceiver.ACTION_POWER_STATE_CHANGED -> {
                val powerConnected = intent.getBooleanExtra("power_connected", false)
                Log.d(TAG, "Power state changed: connected=$powerConnected")
            }

            else -> {
                startForegroundCompat(ConnectionState.Idle)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "AeroVpnService destroying")

        // CRITICAL FIX: always disconnect active handler to release resources
        serviceScope.launch {
            try {
                activeProtocolHandler?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting handler during destroy", e)
            } finally {
                activeProtocolHandler = null
            }
        }

        // Close kill-switch PFD if active
        killSwitchPfd?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing kill switch PFD", e) }
            killSwitchPfd = null
        }

        // CRITICAL FIX (C2): unregister receiver to prevent leaks
        networkStateReceiver.unregister(this)

        serviceScope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    fun connect(config: ProtocolConfig) {
        lastConfig = config
        serviceScope.launch {
            _connectionState.value = ConnectionState.Connecting
            updateNotification(ConnectionState.Connecting)

            try {
                // Disconnect any existing handler first
                activeProtocolHandler?.let {
                    try { it.disconnect() } catch (e: Exception) { /* ignore */ }
                }

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
                Log.e(TAG, "Connection error", e)
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
                Log.w(TAG, "Error during disconnect", e)
            } finally {
                _connectionState.value = ConnectionState.Idle
                updateNotification(ConnectionState.Idle)
                stopSelf()
            }
        }
    }

    /**
     * Activate kill switch — blocks all traffic by establishing a VPN interface
     * that routes everything but has no outbound connection.
     *
     * CRITICAL FIX: establish() result is now stored in killSwitchPfd so the
     * VPN tunnel interface is kept alive and not immediately garbage-collected.
     */
    fun activateKillSwitch() {
        try {
            // Close any existing kill-switch tunnel first
            killSwitchPfd?.let {
                try { it.close() } catch (e: Exception) { /* ignore */ }
                killSwitchPfd = null
            }

            val builder = Builder()
            builder.setSession("AeroVPN-KillSwitch")
            builder.addAddress("10.0.0.2", 24)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("127.0.0.1") // block DNS too

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setBlocking(true)
            }

            // CRITICAL FIX: store result — without this the PFD is GC'd immediately
            // and the kernel tears down the tun interface, making kill switch a no-op
            killSwitchPfd = builder.establish()

            if (killSwitchPfd != null) {
                Log.i(TAG, "Kill switch activated — all traffic blocked")
            } else {
                Log.e(TAG, "Kill switch establish() returned null — VPN permission not granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate kill switch", e)
        }
    }

    fun deactivateKillSwitch() {
        try {
            killSwitchPfd?.close()
            killSwitchPfd = null
            Log.i(TAG, "Kill switch deactivated")
        } catch (e: Exception) {
            Log.e(TAG, "Error deactivating kill switch", e)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getProtocolHandler(config: ProtocolConfig): ProtocolHandler {
        return when (config) {
            is WireGuardConfig -> WireGuardProtocol()
            is V2RayConfig -> V2RayProtocol(this)
            is SSHConfig -> SSHProtocol(this)
            is ShadowsocksConfig -> ShadowsocksProtocol(this)
            is UdpTunnelConfig -> UdpTunnelProtocol(this)
            else -> throw IllegalArgumentException(
                "Unsupported protocol config type: ${config.javaClass.simpleName}"
            )
        }
    }

    private fun startForegroundCompat(state: ConnectionState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AeroVPNApplication.VPN_NOTIFICATION_ID,
                buildNotification(state),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(AeroVPNApplication.VPN_NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(state: ConnectionState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(AeroVPNApplication.VPN_NOTIFICATION_ID, buildNotification(state))
    }
}
