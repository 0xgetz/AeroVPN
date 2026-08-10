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
import android.os.PowerManager
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
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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

        // M2: canonical restore action used by BootReceiver / PackageUpdateReceiver.
        const val ACTION_RESTORE = "com.aerovpn.ACTION_RESTORE"

        // Legacy actions from earlier receiver builds — accepted for compatibility.
        private const val ACTION_START_VPN_LEGACY = "com.aerovpn.ACTION_START_VPN"
        private const val ACTION_RESTORE_LEGACY = "com.aerovpn.ACTION_RESTORE_CONNECTION"

        // Shared prefs file also used by the receivers.
        private const val PREFS_NAME = "aerovpn_prefs"
        private const val PREFS_LAST_CONFIG = "last_vpn_config"
        private const val PREFS_LAST_CONFIG_TYPE = "last_vpn_config_type"
        private const val PREFS_WAS_CONNECTED = "vpn_was_connected"
    }

    private val binder = LocalBinder()

    // Fix #23: SupervisorJob ensures child coroutine failures don't cancel the whole scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // H2: serializes connect()/disconnect() so the active handler and connection
    // state are never mutated by two coroutines at the same time.
    private val lifecycleMutex = Mutex()

    // M1: partial wakelock held while a tunnel is active, so Doze / CPU sleep
    // does not stall the packet-forwarding and keepalive threads.
    private var wakeLock: PowerManager.WakeLock? = null

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
                // H2 FIX: guard the typed extra read. On API 33+ the typed
                // getSerializableExtra throws ClassCastException when the extra is
                // not a ProtocolConfig — a caller sending a wrong extra used to
                // crash the whole service. Both paths now degrade to null safely.
                val config: ProtocolConfig? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getSerializableExtra(EXTRA_CONFIG, ProtocolConfig::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getSerializableExtra(EXTRA_CONFIG) as? ProtocolConfig
                    }
                } catch (e: ClassCastException) {
                    Log.w(TAG, "EXTRA_CONFIG is not a ProtocolConfig — ignoring", e)
                    null
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
                val isConnected = intent?.getBooleanExtra("is_connected", false) ?: false
                val networkType = intent?.getStringExtra("network_type") ?: "Unknown"
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
                val screenOn = intent?.getBooleanExtra("screen_on", true) ?: true
                Log.d(TAG, "Screen state changed: on=$screenOn")
                // Could implement wake-lock adjustments here
            }

            NetworkStateReceiver.ACTION_POWER_STATE_CHANGED -> {
                val powerConnected = intent?.getBooleanExtra("power_connected", false) ?: false
                Log.d(TAG, "Power state changed: connected=$powerConnected")
            }

            // M2: canonical (and legacy) restore actions — become foreground first
            // (5s rule on API 26+), then reconnect to the last-used server.
            ACTION_RESTORE,
            ACTION_START_VPN_LEGACY,
            ACTION_RESTORE_LEGACY -> {
                startForegroundCompat(ConnectionState.Idle)
                restoreLastConnection()
            }

            else -> {
                startForegroundCompat(ConnectionState.Idle)
                // M2: START_STICKY re-delivers a null intent when the process was
                // killed — restore the connection that was active before death.
                if (intent == null && isPersistedConnected()) {
                    restoreLastConnection()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "AeroVpnService destroying")

        // M1: never leak the wakelock.
        releaseWakelock()

        // H1 FIX: teardown must actually run. The old code launched the disconnect
        // on serviceScope and then called serviceScope.cancel() synchronously right
        // after — the coroutine never executed, so SSH sessions, UDP sockets,
        // subprocesses and tun fds leaked on destroy. We now run it synchronously,
        // bounded by withTimeoutOrNull so the main thread can never block forever
        // waiting on a stuck handler.
        val handler = activeProtocolHandler
        activeProtocolHandler = null
        if (handler != null) {
            try {
                runBlocking {
                    withTimeoutOrNull(3_000) {
                        lifecycleMutex.withLock { handler.disconnect() }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting handler during destroy", e)
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

        // M2: remember the last-used server so boot/update/restart can reconnect.
        persistLastConfig(config)

        serviceScope.launch {
            // H2: serialize with disconnect() — no concurrent handler mutations.
            lifecycleMutex.withLock {
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
                        markConnected(true)
                    } else {
                        markConnected(false)
                        _connectionState.value = ConnectionState.Error("Connection failed")
                        updateNotification(ConnectionState.Error("Connection failed"))
                    }
                } catch (e: Exception) {
                    markConnected(false)
                    Log.e(TAG, "Connection error", e)
                    val errorState = ConnectionState.Error(e.message ?: "Unknown error", e)
                    _connectionState.value = errorState
                    updateNotification(errorState)
                }
            }
        }
    }

    fun disconnect() {
        serviceScope.launch {
            // H2: serialize with connect() — no concurrent handler mutations.
            lifecycleMutex.withLock {
                _connectionState.value = ConnectionState.Disconnecting
                updateNotification(ConnectionState.Disconnecting)

                try {
                    activeProtocolHandler?.disconnect()
                    activeProtocolHandler = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error during disconnect", e)
                } finally {
                    // M1: drop the wakelock the moment the tunnel is gone.
                    releaseWakelock()
                    setWasConnected(false)
                    _connectionState.value = ConnectionState.Idle
                    updateNotification(ConnectionState.Idle)
                    stopSelf()
                }
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
    // M1: Wakelock while connected
    // -------------------------------------------------------------------------

    private fun acquireWakelock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AeroVPN:VpnConnection"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Partial wakelock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wakelock", e)
            wakeLock = null
        }
    }

    private fun releaseWakelock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                Log.d(TAG, "Wakelock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wakelock", e)
        } finally {
            wakeLock = null
        }
    }

    /** Shared success/failure bookkeeping for connect(). */
    private fun markConnected(connected: Boolean) {
        if (connected) {
            acquireWakelock()
            setWasConnected(true)
            _connectionState.value = ConnectionState.Connected
            updateNotification(ConnectionState.Connected)
        } else {
            releaseWakelock()
            setWasConnected(false)
        }
    }

    // -------------------------------------------------------------------------
    // M2: Persist / restore the last connection
    // -------------------------------------------------------------------------

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persist the full protocol config as JSON so it can be restored after a
     * reboot, app update or process death. Gson serializes the concrete subclass
     * (config.javaClass) so every protocol-specific field is preserved.
     *
     * Note: this intentionally mirrors the app's existing plain-prefs config
     * storage (see ExportImportTool / AeroVPNBackupAgent).
     */
    private fun persistLastConfig(config: ProtocolConfig) {
        try {
            val json = Gson().toJson(config, config.javaClass)
            prefs().edit()
                .putString(PREFS_LAST_CONFIG, json)
                .putString(PREFS_LAST_CONFIG_TYPE, config.javaClass.name)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist VPN config", e)
        }
    }

    private fun loadLastConfig(): ProtocolConfig? {
        return try {
            val p = prefs()
            val json = p.getString(PREFS_LAST_CONFIG, null) ?: return null
            val typeName = p.getString(PREFS_LAST_CONFIG_TYPE, null) ?: return null
            val clazz = Class.forName(typeName)
            Gson().fromJson(json, clazz) as? ProtocolConfig
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore VPN config", e)
            null
        }
    }

    /** Reconnect to the config persisted by the last successful connect(). */
    private fun restoreLastConnection() {
        val config = loadLastConfig() ?: run {
            Log.d(TAG, "No persisted VPN config to restore")
            return
        }
        Log.i(TAG, "Restoring last connection: ${config.name}")
        connect(config)
    }

    private fun isPersistedConnected(): Boolean =
        prefs().getBoolean(PREFS_WAS_CONNECTED, false)

    private fun setWasConnected(connected: Boolean) {
        prefs().edit().putBoolean(PREFS_WAS_CONNECTED, connected).apply()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getProtocolHandler(config: ProtocolConfig): ProtocolHandler {
        return when (config) {
            is WireGuardConfig -> WireGuardProtocol(this)
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
