package com.nsaanchor.gatekeeper.firewall

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.model.LogLevel
import com.nsaanchor.gatekeeper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException

/**
 * Layer 1 Lockdown: Local Sinkhole VpnService for the Base Phone.
 *
 * When LTE Lockdown engages on an unrooted device, this service routes all IPv4 (`0.0.0.0/0`)
 * and IPv6 (`::/0`) traffic from on-device apps into a blackhole TUN interface, discarding
 * all packets and returning dummy unreachable DNS servers.
 *
 * Crucially, [addDisallowedApplication] exempts our own app (`com.nsaanchor.gatekeeper`),
 * allowing [com.nsaanchor.gatekeeper.network.HeartbeatBaitEngine] to bind directly to the
 * cellular modem and transmit the 5G Resurrection Bait to the cell tower unhindered.
 */
class GatekeeperVpnService : VpnService() {

    private val vpnScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SINKHOLE -> {
                establishSinkhole()
                return START_STICKY
            }
            ACTION_STOP_SINKHOLE -> {
                tearDownSinkhole()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }

    private fun establishSinkhole() {
        if (vpnInterface != null) return

        try {
            val configureIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingConfigure = PendingIntent.getActivity(
                this,
                0,
                configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = Builder()
                .setSession("5G NSA Gatekeeper Sinkhole")
                .setConfigureIntent(pendingConfigure)
                .setMtu(1500)
                // Blackhole IPv4
                .addAddress("10.255.255.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.255.255.2")
                // Blackhole IPv6
                .addAddress("fd00:255:255::1", 128)
                .addRoute("::", 0)
                .addDnsServer("fd00:255:255::2")
                // EXEMPT our own app package so HeartbeatBaitEngine can ping 1.1.1.1 on cellular!
                .addDisallowedApplication(packageName)
                .setBlocking(true)

            val pfd = builder.establish()
            if (pfd != null) {
                vpnInterface = pfd
                isSinkholeActive = true
                GatekeeperRepository.log(
                    LogLevel.BLOCK,
                    "Phone Sinkhole VPN established: all base phone apps blackholed (Bait exempted)"
                )

                // Drain and discard any packets arriving at the TUN interface so the kernel buffer never stalls
                drainJob = vpnScope.launch {
                    val buffer = ByteArray(32768)
                    try {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            while (isActive) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                // Packets are discarded into the sinkhole
                            }
                        }
                    } catch (_: IOException) {
                        // Expected when vpnInterface is closed
                    } catch (_: Exception) {
                        // Ignore drain loop termination
                    }
                }
            } else {
                GatekeeperRepository.log(
                    LogLevel.WARN,
                    "Failed to establish VPN interface: prepare() not approved or revoked"
                )
            }
        } catch (e: Exception) {
            GatekeeperRepository.log(
                LogLevel.ERROR,
                "Error starting Phone Sinkhole VPN: ${e.message}"
            )
        }
    }

    private fun tearDownSinkhole() {
        drainJob?.cancel()
        drainJob = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        } finally {
            vpnInterface = null
            isSinkholeActive = false
        }
        GatekeeperRepository.log(
            LogLevel.INFO,
            "Phone Sinkhole VPN torn down (normal app internet restored)"
        )
    }

    override fun onDestroy() {
        tearDownSinkhole()
        vpnScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SINKHOLE = "com.nsaanchor.gatekeeper.action.START_SINKHOLE"
        const val ACTION_STOP_SINKHOLE = "com.nsaanchor.gatekeeper.action.STOP_SINKHOLE"

        @Volatile
        var isSinkholeActive: Boolean = false
            private set

        /**
         * Ensures VPN permission is granted without user intervention by applying
         * appops via Shizuku shell if available.
         */
        suspend fun grantVpnAppOpsViaShizuku(packageName: String): Boolean {
            if (!ShizukuShellExecutor.isReady()) return false
            return try {
                ShizukuShellExecutor.execute("appops set $packageName ACTIVATE_VPN allow", timeoutMs = 2000L)
                ShizukuShellExecutor.execute("appops set $packageName ACTIVATE_PLATFORM_VPN allow", timeoutMs = 2000L)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun startSinkhole(context: Context) {
            val intent = Intent(context, GatekeeperVpnService::class.java).apply {
                action = ACTION_START_SINKHOLE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                GatekeeperRepository.log(
                    LogLevel.ERROR,
                    "Failed to start GatekeeperVpnService: ${e.message}"
                )
            }
        }

        fun stopSinkhole(context: Context) {
            val intent = Intent(context, GatekeeperVpnService::class.java).apply {
                action = ACTION_STOP_SINKHOLE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
