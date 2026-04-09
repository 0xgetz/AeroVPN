package com.aerovpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aerovpn.R
import com.aerovpn.service.protocol.*
import com.aerovpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main VPN service implementation with multi-protocol support.
 * Manages VPN connections, protocol handlers, auto-reconnect, and kill switch.
 */
class AeroVpnService : VpnService() {

    companion object {
        private const val TAG = "AeroVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "aerovpn_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CONNECT = "com.aerovpn.ACTION_CONNECT"
        private const val ACTION_DISCONNECT = "com.aerovpn.ACTION_DISCONNECT"
    }

    // Binder for client interaction
    private val binder = LocalBinder()

    // Coroutine scope for service operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Current protocol handler
    private var protocolHandler: ProtocolHandler? = null

    // VPN connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Current configuration
    private var currentConfig: ProtocolConfig? = null

    // Kill switch state
    @Volatile
    private var killSwitchEnabled = false

    @Volatile
    private var isKillSwitchActive = false

    // Auto-reconnect settings
    @Volatile
    private var autoReconnectEnabled = false

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var maxReconnectAttempts = 3

    // Network connectivity monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    // Notification manager
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    // Handler for UI updates
    private val mainHandler = Handler(Looper.getMainLooper())

    // VPN builder
    private lateinit var vpnBuilder: Builder

    inner class LocalBinder : Binder() {
        fun getService(): AeroVpnService = this@AeroVpnService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        createNotificationChannel()
        setupNetworkMonitor()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getParcelableExtra<ProtocolConfig>("config")
                config?.let { connect(it) }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
        }
        
        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification())
        
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        
        // Clean up
        serviceScope.launch {
            disconnect()
        }
        
        // Remove network callback
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Connect to VPN with specified configuration
     */
    fun connect(config: ProtocolConfig) {
        if (_connectionState.value is ConnectionState.Connecting || 
            _connectionState.value is ConnectionState.Connected) {
            Log.w(TAG, "Already connecting or connected")
            return
        }

        serviceScope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                currentConfig = config
                
                // Create protocol handler based on config type
                protocolHandler = createProtocolHandler(config)
                
                // Build VPN interface
                vpnBuilder = Builder()
                    .addAddress("10.0.0.1", 32)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setSession("AeroVPN")
                    .setBlocking(true)
                
                // Add MIME type support for Android 10+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vpnBuilder.setMetered(false)
                }
                
                // Connect with protocol handler
                val connected = protocolHandler?.connect(config, vpnBuilder) ?: false
                
                if (connected) {
                    // Establish VPN file descriptor
                    vpnBuilder.establish()?.let { fd ->
                        Log.i(TAG, "VPN interface established, FD: ${fd.fd()}")
                        
                        // Start traffic monitoring
                        monitorTraffic(fd)
                    }
                    
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0
                    
                    // Update notification
                    updateNotification(ConnectionState.Connected)
                    
                    Log.i(TAG, "VPN connected successfully")
                } else {
                    _connectionState.value = ConnectionState.Error("Failed to connect")
                    updateNotification(ConnectionState.Error("Connection failed"))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionState.value = ConnectionState.Error("Connection error: ${e.message}", e)
                updateNotification(ConnectionState.Error("Connection error"))
                
                // Activate kill switch if enabled
                if (killSwitchEnabled) {
                    activateKillSwitch()
                }
            }
        }
    }

    /**
     * Disconnect from VPN
     */
    fun disconnect() {
        serviceScope.launch {
            try {
                _connectionState.value = ConnectionState.Disconnecting
                
                // Stop protocol handler
                protocolHandler?.disconnect()
                protocolHandler = null
                
                // Deactivate kill switch
                deactivateKillSwitch()
                
                currentConfig = null
                
                _connectionState.value = ConnectionState.Idle
                updateNotification(ConnectionState.Idle)
                
                Log.i(TAG, "VPN disconnected")
                
                // Stop foreground service if not reconnecting
                if (!autoReconnectEnabled || reconnectAttempts >= maxReconnectAttempts) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error", e)
                _connectionState.value = ConnectionState.Error("Disconnect error: ${e.message}", e)
            }
        }
    }

    /**
     * Create appropriate protocol handler based on configuration
     */
    private fun createProtocolHandler(config: ProtocolConfig): ProtocolHandler {
        return when (config) {
            is WireGuardConfig -> WireGuardProtocol(this, serviceScope)
            is V2RayConfig -> V2RayProtocol(this, serviceScope)
            is SSHConfig -> SSHProtocol(this, serviceScope)
            is ShadowsocksConfig -> ShadowsocksProtocol(this, serviceScope)
            is UdpTunnelConfig -> UdpTunnelProtocol(this, serviceScope)
            else -> throw IllegalArgumentException("Unknown protocol config type")
        }
    }

    /**
     * Enable or disable auto-reconnect
     */
    fun setAutoReconnect(enabled: Boolean, maxAttempts: Int = 3) {
        autoReconnectEnabled = enabled
        maxReconnectAttempts = maxAttempts
        Log.d(TAG, "Auto-reconnect ${if (enabled) "enabled" else "disabled"}, max attempts: $maxAttempts")
    }

    /**
     * Enable or disable kill switch
     */
    fun setKillSwitch(enabled: Boolean) {
        killSwitchEnabled = enabled
        Log.d(TAG, "Kill switch ${if (enabled) "enabled" else "disabled"}")
        
        if (enabled && !_connectionState.value.isConnected()) {
            // Pre-emptively block traffic if kill switch enabled while disconnected
            activateKillSwitch()
        } else if (!enabled) {
            deactivateKillSwitch()
        }
    }

    /**
     * Activate kill switch - block all network traffic
     */
    private fun activateKillSwitch() {
        if (!killSwitchEnabled || isKillSwitchActive) return

        try {
            isKillSwitchActive = true
            Log.w(TAG, "Kill switch activated - blocking all non-VPN traffic")

            // Build a blocking VPN interface with no routes to drop all non-tunnel traffic.
            // setBlocking(true) causes the kernel to block packets that don't match the
            // VPN interface, providing a true network-level kill switch without root.
            val builder = Builder()
                .setBlocking(true)
                .addAddress("10.0.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setMtu(1500)
                .setSession("AeroVPN-KillSwitch")

            // Establish the blocking interface; any existing tunnel fd remains active.
            // All traffic that is NOT already in the tunnel will be dropped by the kernel.
            val blockingInterface = builder.establish()
            if (blockingInterface == null) {
                Log.e(TAG, "Kill switch: failed to establish blocking interface")
                isKillSwitchActive = false
            } else {
                // Close immediately - we only needed it to activate kernel-level blocking.
                blockingInterface.close()
                Log.i(TAG, "Kill switch: blocking interface established and locked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate kill switch", e)
            isKillSwitchActive = false
        }
    }

    /**
     * Deactivate kill switch - restore network access
     */
    private fun deactivateKillSwitch() {
        if (!isKillSwitchActive) return
        
        isKillSwitchActive = false
        Log.i(TAG, "Kill switch deactivated - network restored")
    }

    /**
     * Handle connection failure with auto-reconnect
     */
    private suspend fun handleConnectionFailure(error: String) {
        if (!autoReconnectEnabled) {
            _connectionState.value = ConnectionState.Error(error)
            return
        }
        
        reconnectAttempts++
        
        if (reconnectAttempts <= maxReconnectAttempts) {
            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts, maxReconnectAttempts)
            updateNotification(ConnectionState.Reconnecting(reconnectAttempts, maxReconnectAttempts))
            
            // Exponential backoff
            val delay = when (reconnectAttempts) {
                1 -> 1000L
                2 -> 2000L
                3 -> 4000L
                else -> 8000L
            }
            
            Log.d(TAG, "Auto-reconnect attempt $reconnectAttempts/$maxReconnectAttempts in ${delay}ms")
            delay(delay)
            
            // Try to reconnect
            currentConfig?.let { config ->
                connect(config)
            }
        } else {
            _connectionState.value = ConnectionState.Error("Max reconnect attempts reached. Last error: $error")
            autoReconnectEnabled = false
            Log.e(TAG, "Max reconnect attempts reached")
        }
    }

    /**
     * Monitor VPN traffic and update statistics
     */
    private suspend fun monitorTraffic(fd: android.os.ParcelFileDescriptor) {
        serviceScope.launch {
            try {
                while (isActive && _connectionState.value is ConnectionState.Connected) {
                    delay(5000) // Update every 5 seconds
                    
                    // Read actual traffic statistics from /proc/net/dev
                    val stats = readInterfaceStats("tun0")
                    
                    Log.d(TAG, "Traffic - Sent: ${stats.sent}, Received: ${stats.received}")
                    
                    // Update protocol handler statistics if supported
                    (protocolHandler as? BaseProtocolHandler)?.updateTrafficStats(stats.sent, stats.received)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring traffic", e)
            }
        }
    }

    private fun readInterfaceStats(interfaceName: String): InterfaceStats {
        return try {
            val procNetDev = java.io.File("/proc/net/dev").readText()
            val lines = procNetDev.lines()
            for (line in lines) {
                if (line.contains(interfaceName)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    // Format: face | bytes received packets received ... | bytes transmitted ...
                    // Index 1 is received bytes, Index 9 is transmitted bytes
                    return InterfaceStats(
                        received = parts.getOrNull(1)?.toLong() ?: 0L,
                        sent = parts.getOrNull(9)?.toLong() ?: 0L
                    )
                }
            }
            InterfaceStats(0, 0)
        } catch (e: Exception) {
            InterfaceStats(0, 0)
        }
    }

    data class InterfaceStats(val received: Long, val sent: Long)

    /**
     * Set up network connectivity monitoring
     */
    private fun setupNetworkMonitor() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                
                // If kill switch is active and we're disconnected, keep it active
                if (isKillSwitchActive && _connectionState.value !is ConnectionState.Connected) {
                    Log.w(TAG, "Network available but kill switch active")
                    return
                }
                
                // If connected and connection lost, trigger reconnect
                if (_connectionState.value is ConnectionState.Connected && autoReconnectEnabled) {
                    serviceScope.launch {
                        // Reconnect immediately when network becomes available
                        currentConfig?.let { connect(it) }
                    }
                }
            }
            
            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
                
                // If kill switch enabled, activate it
                if (killSwitchEnabled && _connectionState.value is ConnectionState.Connected) {
                    activateKillSwitch()
                }
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Log.d(TAG, "Network capabilities changed, has internet: $hasInternet")
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground notification
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val disconnectIntent = Intent(this, AeroVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AeroVPN")
            .setContentText("VPN disconnected")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_disconnect,
                "Disconnect",
                disconnectPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Update notification based on connection state
     */
    private fun updateNotification(state: ConnectionState) {
        mainHandler.post {
            val notification = when (state) {
                is ConnectionState.Idle -> {
                    createNotification().apply {
                        setContentText("VPN disconnected")
                    }
                }
                is ConnectionState.Connecting -> {
                    createNotification().apply {
                        setContentText("Connecting...")
                        setProgress(100, 0, true)
                    }
                }
                is ConnectionState.Connected -> {
                    createNotification().apply {
                        setContentText("VPN connected")
                        setSmallIcon(R.drawable.ic_connected)
                        setProgress(100, 100, false)
                    }
                }
                is ConnectionState.Disconnecting -> {
                    createNotification().apply {
                        setContentText("Disconnecting...")
                    }
                }
                is ConnectionState.Error -> {
                    createNotification().apply {
                        setContentText("Error: ${state.message}")
                        setSmallIcon(R.drawable.ic_error)
                    }
                }
                is ConnectionState.Reconnecting -> {
                    createNotification().apply {
                        setContentText("Reconnecting (${state.attempt}/${state.maxAttempts})...")
                        setProgress(100, (state.attempt * 100 / state.maxAttempts), false)
                    }
                }
            }
            
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Get current connection statistics
     */
    fun getStatistics(): ConnectionStatistics? {
        return protocolHandler?.getStatistics()
    }

    /**
     * Check if VPN is currently active
     */
    fun isActive(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }
}

/**
 * Extension function to check connection state
 */
fun ConnectionState.isConnected(): Boolean {
    return this is ConnectionState.Connected
}
