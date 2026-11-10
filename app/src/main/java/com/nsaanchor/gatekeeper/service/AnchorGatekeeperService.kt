package com.nsaanchor.gatekeeper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nsaanchor.gatekeeper.R
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.firewall.IptablesFirewallManager
import com.nsaanchor.gatekeeper.firewall.ShizukuShellExecutor
import com.nsaanchor.gatekeeper.model.LogLevel
import com.nsaanchor.gatekeeper.model.NetworkAnchorState
import com.nsaanchor.gatekeeper.network.CellularSignalSnapshot
import com.nsaanchor.gatekeeper.network.HeartbeatBaitEngine
import com.nsaanchor.gatekeeper.network.TelephonyNsaMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistent Foreground Service orchestrating the 5G NSA "Anchor" & Hotspot Gatekeeper:
 * 1. Fail-Closed Startup: Immediately locks down cellular `OUTPUT` and `FORWARD` traffic until
 *    5G NSA (`OVERRIDE_NETWORK_TYPE_NR_NSA` / `NR_ADVANCED`) is confirmed.
 * 2. Real-time TelephonyCallback monitoring of 5G NSA vs 4G LTE anchor state.
 * 3. Configurable Handover Grace Period countdown before locking down when dropping from 5G -> LTE.
 * 4. 5G Resurrection Heartbeat ("Bait") loop while in LTE lockdown to force the cellular modem
 *    into `RRC_CONNECTED` and trigger EN-DC 5G secondary cell re-attachment.
 * 5. Guaranteed cleanup of `NSA_LOCKDOWN` iptables/ip6tables rules on stop/destroy.
 */
class AnchorGatekeeperService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val stateMutex = Mutex()

    private val firewallManager = IptablesFirewallManager
    private lateinit var heartbeatEngine: HeartbeatBaitEngine
    private lateinit var telephonyMonitor: TelephonyNsaMonitor

    private var graceJob: Job? = null

    @Volatile
    private var latestSnapshot: CellularSignalSnapshot? = null

    @Volatile
    private var isShuttingDown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        heartbeatEngine = HeartbeatBaitEngine(applicationContext, serviceScope)
        telephonyMonitor = TelephonyNsaMonitor(applicationContext) { snapshot ->
            onSnapshotChanged(snapshot)
        }
        observeFirewallConfigChanges()
    }

    private fun observeFirewallConfigChanges() {
        serviceScope.launch {
            GatekeeperRepository.uiState
                .map { state ->
                    Triple(
                        IptablesFirewallManager.sanitizeHeartbeatIp(state.config.heartbeatIp),
                        state.config.blockIpv6,
                        state.config.customInterfaces.trim()
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { (safeIp, blockIpv6, customIfaces) ->
                    if (isShuttingDown || !GatekeeperRepository.uiState.value.isServiceRunning) {
                        return@collect
                    }
                    stateMutex.withLock {
                        val currentState = GatekeeperRepository.uiState.value
                        val shouldUpdateLockdown = !isShuttingDown &&
                            currentState.isServiceRunning &&
                            (
                                currentState.isFirewallActive ||
                                    currentState.anchorState == NetworkAnchorState.LTE_LOCKDOWN_SEARCHING ||
                                    currentState.anchorState == NetworkAnchorState.STARTING_FAIL_CLOSED
                                )
                        if (shouldUpdateLockdown) {
                            GatekeeperRepository.log(
                                LogLevel.INFO,
                                "Configuration updated while locked down -> Refreshing ${IptablesFirewallManager.CHAIN_NAME} (allow=$safeIp, ipv6=$blockIpv6)"
                            )
                            firewallManager.applyLockdown(
                                context = this@AnchorGatekeeperService,
                                heartbeatIp = safeIp,
                                blockIpv6 = blockIpv6,
                                customInterfaces = customIfaces
                            )
                            updateNotificationForState(GatekeeperRepository.uiState.value.anchorState)
                        }
                    }
                }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                initiateCleanShutdown()
                return START_NOT_STICKY
            }
            ACTION_FORCE_FLUSH -> {
                handleForceFlush()
                return if (GatekeeperRepository.uiState.value.isServiceRunning) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }
            ACTION_START, null -> {
                startAnchorGatekeeper()
                return START_STICKY
            }
            else -> {
                return START_STICKY
            }
        }
    }

    private fun startAnchorGatekeeper() {
        isShuttingDown = false

        // 1. Start foreground immediately
        val initialTitle = "5G NSA Anchor: Initializing..."
        val initialText = "Applying Fail-Closed firewall protection..."
        promoteToForeground(buildNotification(initialTitle, initialText))

        GatekeeperRepository.updateState {
            it.copy(
                isServiceRunning = true,
                anchorState = NetworkAnchorState.STARTING_FAIL_CLOSED,
                graceRemainingMs = 0L
            )
        }
        GatekeeperRepository.log(
            LogLevel.INFO,
            "AnchorGatekeeperService starting in FAIL-CLOSED mode"
        )

        serviceScope.launch {
            stateMutex.withLock {
                if (isShuttingDown) return@withLock

                // 2. Refresh Shizuku status
                ShizukuShellExecutor.init()
                val shizukuStatus = ShizukuShellExecutor.refreshStatus()
                GatekeeperRepository.log(
                    LogLevel.INFO,
                    "Shizuku status check: ${shizukuStatus.label}"
                )

                // 3. Apply Fail-Closed lockdown immediately until 5G NSA is confirmed
                val config = GatekeeperRepository.uiState.value.config
                firewallManager.applyLockdown(
                    context = this@AnchorGatekeeperService,
                    heartbeatIp = config.heartbeatIp,
                    blockIpv6 = config.blockIpv6,
                    customInterfaces = config.customInterfaces
                )

                // 4. Start 5G Resurrection Heartbeat ("Bait") while in Fail-Closed state
                heartbeatEngine.startBaitLoop()

                // 5. Start TelephonyCallback monitor
                val monitorStarted = telephonyMonitor.start()
                if (!monitorStarted) {
                    GatekeeperRepository.updateState {
                        it.copy(anchorState = NetworkAnchorState.ERROR)
                    }
                    GatekeeperRepository.log(
                        LogLevel.ERROR,
                        "READ_PHONE_STATE permission missing or TelephonyCallback failed to register. Traffic remains Fail-Closed."
                    )
                    updateNotificationForState(NetworkAnchorState.ERROR)
                } else {
                    GatekeeperRepository.log(
                        LogLevel.INFO,
                        "TelephonyCallback registered (DisplayInfoListener + ServiceStateListener)"
                    )
                    updateNotificationForState(GatekeeperRepository.uiState.value.anchorState)
                }
            }
        }
    }

    private fun onSnapshotChanged(snapshot: CellularSignalSnapshot) {
        if (isShuttingDown) return
        latestSnapshot = snapshot

        GatekeeperRepository.updateState {
            it.copy(
                rawNetworkType = snapshot.networkTypeName,
                overrideNetworkType = snapshot.overrideTypeName
            )
        }

        if (snapshot.is5gNsaOrBetter) {
            handle5gNsaRestored(snapshot)
        } else {
            handleLteDetected(snapshot)
        }
    }

    private fun handle5gNsaRestored(snapshot: CellularSignalSnapshot) {
        // Cancel any pending handover grace countdown immediately
        graceJob?.let { job ->
            if (job.isActive) {
                job.cancel()
                GatekeeperRepository.log(
                    LogLevel.INFO,
                    "5G NSA re-attached during grace period (${snapshot.overrideTypeName}) -> Handover grace cancelled"
                )
            }
        }
        graceJob = null

        // Stop heartbeat immediately so zero extra bait traffic is sent on 5G
        heartbeatEngine.stopBaitLoop()

        serviceScope.launch {
            stateMutex.withLock {
                if (isShuttingDown) return@withLock
                val currentSnapshot = latestSnapshot ?: snapshot
                if (!currentSnapshot.is5gNsaOrBetter) return@withLock

                // Ensure bait loop is stopped inside the mutex in case an earlier queued coroutine started it
                heartbeatEngine.stopBaitLoop()

                val previousState = GatekeeperRepository.uiState.value.anchorState
                val wasFirewallActive = GatekeeperRepository.uiState.value.isFirewallActive

                if (wasFirewallActive || previousState == NetworkAnchorState.STARTING_FAIL_CLOSED) {
                    val config = GatekeeperRepository.uiState.value.config
                    firewallManager.clearLockdown(
                        context = this@AnchorGatekeeperService,
                        customInterfaces = config.customInterfaces
                    )
                }

                if (previousState != NetworkAnchorState.CONNECTED_5G_NSA) {
                    GatekeeperRepository.log(
                        LogLevel.UNBLOCK,
                        "5G NSA Active (${currentSnapshot.networkTypeName} / ${currentSnapshot.overrideTypeName}) -> Hotspot & Phone Traffic Allowed"
                    )
                }

                GatekeeperRepository.updateState {
                    it.copy(
                        anchorState = NetworkAnchorState.CONNECTED_5G_NSA,
                        graceRemainingMs = 0L
                    )
                }
                updateNotificationForState(NetworkAnchorState.CONNECTED_5G_NSA)
            }
        }
    }

    private fun handleLteDetected(snapshot: CellularSignalSnapshot) {
        val currentState = GatekeeperRepository.uiState.value.anchorState

        // If already counting down grace period, let the active grace countdown continue without resetting
        if (currentState == NetworkAnchorState.HANDOVER_GRACE_PERIOD) {
            return
        }

        // If already in LTE_LOCKDOWN_SEARCHING, ensure firewall and heartbeat loop are active
        if (currentState == NetworkAnchorState.LTE_LOCKDOWN_SEARCHING) {
            if (!heartbeatEngine.isRunning) {
                heartbeatEngine.startBaitLoop()
            }
            if (!GatekeeperRepository.uiState.value.isFirewallActive) {
                serviceScope.launch {
                    stateMutex.withLock {
                        if (isShuttingDown) return@withLock
                        if (latestSnapshot?.is5gNsaOrBetter == true) return@withLock
                        val config = GatekeeperRepository.uiState.value.config
                        firewallManager.applyLockdown(
                            context = this@AnchorGatekeeperService,
                            heartbeatIp = config.heartbeatIp,
                            blockIpv6 = config.blockIpv6,
                            customInterfaces = config.customInterfaces
                        )
                    }
                }
            }
            return
        }

        // If transitioning from CONNECTED_5G_NSA -> LTE: apply Handover Grace Period
        if (currentState == NetworkAnchorState.CONNECTED_5G_NSA) {
            val gracePeriodMs = GatekeeperRepository.uiState.value.config.gracePeriodMs.coerceAtLeast(0L)
            if (gracePeriodMs > 0L) {
                startHandoverGracePeriod(snapshot, gracePeriodMs)
                return
            }
        }

        // Otherwise (e.g., initial STARTING_FAIL_CLOSED -> LTE, or 0ms grace): lock down immediately
        serviceScope.launch {
            applyLteLockdownState(snapshot)
        }
    }

    private fun startHandoverGracePeriod(snapshot: CellularSignalSnapshot, gracePeriodMs: Long) {
        graceJob?.cancel()
        GatekeeperRepository.updateState {
            it.copy(
                anchorState = NetworkAnchorState.HANDOVER_GRACE_PERIOD,
                graceRemainingMs = gracePeriodMs
            )
        }
        updateNotificationForState(NetworkAnchorState.HANDOVER_GRACE_PERIOD)
        GatekeeperRepository.log(
            LogLevel.WARN,
            "LTE detected (${snapshot.networkTypeName} / ${snapshot.overrideTypeName}) -> Starting ${gracePeriodMs}ms handover grace period"
        )

        graceJob = serviceScope.launch {
            var remaining = gracePeriodMs
            val stepMs = 250L
            while (isActive && remaining > 0L) {
                val sleepTime = minOf(stepMs, remaining)
                delay(sleepTime)
                remaining = (remaining - sleepTime).coerceAtLeast(0L)
                GatekeeperRepository.updateState {
                    it.copy(graceRemainingMs = remaining)
                }
            }

            if (isActive && !isShuttingDown) {
                applyLteLockdownState(latestSnapshot ?: snapshot)
            }
        }
    }

    private suspend fun applyLteLockdownState(snapshot: CellularSignalSnapshot) {
        stateMutex.withLock {
            if (isShuttingDown) return@withLock
            val currentSnapshot = latestSnapshot ?: snapshot
            if (currentSnapshot.is5gNsaOrBetter) return@withLock

            val config = GatekeeperRepository.uiState.value.config
            GatekeeperRepository.updateState {
                it.copy(
                    anchorState = NetworkAnchorState.LTE_LOCKDOWN_SEARCHING,
                    graceRemainingMs = 0L
                )
            }

            GatekeeperRepository.log(
                LogLevel.BLOCK,
                "Enforcing LTE Lockdown (${currentSnapshot.networkTypeName} / ${currentSnapshot.overrideTypeName}) -> Blocking traffic & engaging 5G Bait"
            )

            firewallManager.applyLockdown(
                context = this@AnchorGatekeeperService,
                heartbeatIp = config.heartbeatIp,
                blockIpv6 = config.blockIpv6,
                customInterfaces = config.customInterfaces
            )

            heartbeatEngine.startBaitLoop()
            updateNotificationForState(NetworkAnchorState.LTE_LOCKDOWN_SEARCHING)
        }
    }

    private fun handleForceFlush() {
        serviceScope.launch {
            stateMutex.withLock {
                ShizukuShellExecutor.init()
                val config = GatekeeperRepository.uiState.value.config
                GatekeeperRepository.log(
                    LogLevel.WARN,
                    "Emergency Force Flush requested by user..."
                )
                firewallManager.clearLockdown(
                    context = this@AnchorGatekeeperService,
                    customInterfaces = config.customInterfaces
                )
                if (!GatekeeperRepository.uiState.value.isServiceRunning) {
                    stopSelf()
                }
            }
        }
    }

    private fun initiateCleanShutdown() {
        if (isShuttingDown) return
        isShuttingDown = true

        graceJob?.cancel()
        graceJob = null
        heartbeatEngine.stopBaitLoop()
        telephonyMonitor.stop()

        serviceScope.launch {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    val config = GatekeeperRepository.uiState.value.config
                    firewallManager.clearLockdown(
                        context = this@AnchorGatekeeperService,
                        customInterfaces = config.customInterfaces
                    )
                    GatekeeperRepository.updateState {
                        it.copy(
                            isServiceRunning = false,
                            anchorState = NetworkAnchorState.STOPPED,
                            isFirewallActive = false,
                            graceRemainingMs = 0L,
                            lastHeartbeatStatus = "Stopped"
                        )
                    }
                    GatekeeperRepository.log(
                        LogLevel.INFO,
                        "AnchorGatekeeperService stopped cleanly (all firewall rules flushed)"
                    )
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        graceJob?.cancel()
        graceJob = null
        if (::heartbeatEngine.isInitialized) {
            heartbeatEngine.stopBaitLoop()
        }
        if (::telephonyMonitor.isInitialized) {
            telephonyMonitor.stop()
        }

        if (!isShuttingDown) {
            isShuttingDown = true
            val config = GatekeeperRepository.uiState.value.config
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                firewallManager.clearLockdown(
                    context = applicationContext,
                    customInterfaces = config.customInterfaces
                )
                GatekeeperRepository.updateState {
                    it.copy(
                        isServiceRunning = false,
                        anchorState = NetworkAnchorState.STOPPED,
                        isFirewallActive = false,
                        graceRemainingMs = 0L,
                        lastHeartbeatStatus = "Stopped"
                    )
                }
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationForState(state: NetworkAnchorState) {
        if (isShuttingDown) return
        val uiState = GatekeeperRepository.uiState.value
        val (title, body) = when (state) {
            NetworkAnchorState.CONNECTED_5G_NSA -> {
                "5G NSA (Connected - Traffic Allowed)" to
                    "Signal: ${uiState.rawNetworkType} / ${uiState.overrideNetworkType} | Hotspot & Phone Unblocked"
            }
            NetworkAnchorState.HANDOVER_GRACE_PERIOD -> {
                "LTE Handover Grace Period..." to
                    "Waiting ${uiState.config.gracePeriodMs}ms before locking down cellular traffic"
            }
            NetworkAnchorState.LTE_LOCKDOWN_SEARCHING -> {
                "LTE (Blocking & Searching for 5G NSA...)" to
                    "NSA_LOCKDOWN Active | Sending EN-DC Bait to ${uiState.config.heartbeatIp}"
            }
            NetworkAnchorState.STARTING_FAIL_CLOSED -> {
                "Initializing (Fail-Closed Active)" to
                    "Blocking LTE leaks while verifying 5G NSA state..."
            }
            NetworkAnchorState.ERROR -> {
                "Error (Fail-Closed Active)" to
                    "Check Shizuku & Phone Permissions in app"
            }
            NetworkAnchorState.STOPPED -> {
                "5G NSA Anchor Stopped" to
                    "All traffic unblocked"
            }
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    private fun buildNotification(title: String, contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        val stopIntent = Intent(this, AnchorGatekeeperService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anchor_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_anchor_notification,
                "Stop & Unblock",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.nsaanchor.gatekeeper.action.START"
        const val ACTION_STOP = "com.nsaanchor.gatekeeper.action.STOP"
        const val ACTION_FORCE_FLUSH = "com.nsaanchor.gatekeeper.action.FORCE_FLUSH"

        private const val CHANNEL_ID = "nsa_anchor_channel"
        private const val NOTIFICATION_ID = 5001

        fun startService(context: Context) {
            val intent = Intent(context, AnchorGatekeeperService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AnchorGatekeeperService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun forceFlushFirewall(context: Context) {
            val intent = Intent(context, AnchorGatekeeperService::class.java).apply {
                action = ACTION_FORCE_FLUSH
            }
            runCatching { context.startService(intent) }
        }
    }
}
