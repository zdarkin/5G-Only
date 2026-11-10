package com.nsaanchor.gatekeeper.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.firewall.IptablesFirewallManager
import com.nsaanchor.gatekeeper.firewall.ShizukuNetdFirewallBridge
import com.nsaanchor.gatekeeper.firewall.ShizukuShellExecutor
import com.nsaanchor.gatekeeper.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Implements C. The "Bait" (5G Resurrection Heartbeat).
 *
 * Because 5G Non-Standalone (NSA) relies on EN-DC (E-UTRAN New Radio - Dual Connectivity),
 * the cellular modem and base station will not attach the 5G NR secondary cell (SCell) while
 * the radio stays in RRC_IDLE or lacks active uplink RLC buffer demand.
 *
 * While the iptables firewall blocks all other hotspot (`FORWARD`) and local (`OUTPUT`) traffic,
 * it explicitly whitelists `HeartbeatBaitEngine`'s target IP (`-d <targetIp> -j ACCEPT`).
 * This engine sends lightweight periodic HTTP GET & ICMP probes over the cellular interface
 * to keep the LTE anchor in `RRC_CONNECTED` and trigger B1/A2 measurement reports so the
 * carrier re-attaches the 5G NSA carrier.
 */
class HeartbeatBaitEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var baitJob: Job? = null

    val isRunning: Boolean
        get() = baitJob?.isActive == true

    /**
     * Starts the periodic 5G resurrection bait loop if not already running.
     */
    @Synchronized
    fun startBaitLoop() {
        if (baitJob?.isActive == true) return

        baitJob = scope.launch(Dispatchers.IO) {
            GatekeeperRepository.log(
                LogLevel.HEARTBEAT,
                "5G Resurrection Heartbeat loop started (whitelisted EN-DC trigger)"
            )
            try {
                while (isActive) {
                    val config = GatekeeperRepository.uiState.value.config
                    val targetIp = sanitizeTargetIp(config.heartbeatIp)
                    val intervalMs = config.heartbeatIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

                    val detail = sendSingleBaitProbe(targetIp)
                    GatekeeperRepository.recordHeartbeat("$targetIp ($detail)")
                    GatekeeperRepository.log(
                        LogLevel.HEARTBEAT,
                        "Heartbeat Sent -> $targetIp ($detail)"
                    )

                    delay(intervalMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                GatekeeperRepository.log(
                    LogLevel.WARN,
                    "Heartbeat loop encountered error: ${e.message}"
                )
            }
        }
    }

    /**
     * Immediately stops the 5G resurrection bait loop (called as soon as 5G NSA attaches
     * or when the service shuts down).
     */
    @Synchronized
    fun stopBaitLoop() {
        val activeJob = baitJob
        baitJob = null
        if (activeJob != null && activeJob.isActive) {
            activeJob.cancel()
            GatekeeperRepository.updateState {
                it.copy(lastHeartbeatStatus = "Paused (5G Active / Stopped)")
            }
            GatekeeperRepository.log(
                LogLevel.INFO,
                "5G Resurrection Heartbeat loop stopped"
            )
        }
    }

    private suspend fun sendSingleBaitProbe(targetIp: String): String =
        ShizukuNetdFirewallBridge.withBaitPulseWindow {
            val cellularNetwork = findCellularNetwork()
            val udpSent = performCellularUdpBait(targetIp, cellularNetwork)
            val httpResult = performCellularHttpGet(targetIp, cellularNetwork)

            // If HTTP succeeded (or reached the target with a TCP handshake), return the result.
            // Otherwise, fall back to a single-packet ICMP ping via Shizuku shell to guarantee
            // uplink RLC buffer activity on the cellular modem.
            if (httpResult != null) {
                return@withBaitPulseWindow if (udpSent) "$httpResult + UDP:53" else httpResult
            }

            val icmpResult = performShizukuIcmpPing(targetIp)
            if (icmpResult != null) {
                return@withBaitPulseWindow if (udpSent) "$icmpResult + UDP:53" else icmpResult
            }

            if (udpSent) {
                "UDP:53 1B Sent (cellNet=${cellularNetwork != null})"
            } else {
                "TCP SYN / Probe sent (cellNet=${cellularNetwork != null})"
            }
        }

    private fun performCellularUdpBait(targetIp: String, cellularNetwork: Network?): Boolean {
        var socket: DatagramSocket? = null
        return try {
            val netSocket = DatagramSocket()
            socket = netSocket
            netSocket.soTimeout = 1000
            cellularNetwork?.bindSocket(netSocket)
            val payload = byteArrayOf(0x00)
            val address = InetAddress.getByName(targetIp)
            val packet = DatagramPacket(payload, payload.size, address, 53)
            netSocket.send(packet)
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun findCellularNetwork(): Network? {
        val cm = connectivityManager ?: return null
        return try {
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val activeCaps = cm.getNetworkCapabilities(activeNet)
                if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return activeNet
                }
            }
            @Suppress("DEPRECATION")
            val allNetworks = cm.allNetworks
            for (network in allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return network
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun performCellularHttpGet(targetIp: String, cellularNetwork: Network?): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("http://$targetIp/cdn-cgi/trace")
            val openedConn = if (cellularNetwork != null) {
                cellularNetwork.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            connection = openedConn
            openedConn.requestMethod = "GET"
            openedConn.connectTimeout = HTTP_TIMEOUT_MS
            openedConn.readTimeout = HTTP_TIMEOUT_MS
            openedConn.useCaches = false
            openedConn.instanceFollowRedirects = false
            openedConn.setRequestProperty("Connection", "close")
            openedConn.setRequestProperty("User-Agent", "NSA-Anchor-Bait/1.0")

            val responseCode = openedConn.responseCode
            try {
                openedConn.inputStream?.use { stream ->
                    val buffer = ByteArray(128)
                    stream.read(buffer)
                }
            } catch (_: Exception) {
                // Ignore body read exceptions; headers/handshake already stimulated RLC uplink/downlink
            }
            val bindTag = if (cellularNetwork != null) "CELL" else "DEF"
            "HTTP $responseCode [$bindTag]"
        } catch (_: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun performShizukuIcmpPing(targetIp: String): String? {
        if (!ShizukuShellExecutor.isReady()) return null
        return try {
            val result = ShizukuShellExecutor.execute(
                command = "ping -c 1 -W 1 $targetIp",
                timeoutMs = 2000L
            )
            if (result.isSuccess) {
                val rttMatch = Regex("time=([0-9.]+)\\s*ms").find(result.stdout)
                val rtt = rttMatch?.groupValues?.getOrNull(1)
                if (rtt != null) {
                    "ICMP OK (${rtt}ms)"
                } else {
                    "ICMP OK"
                }
            } else {
                "ICMP Probe Sent"
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 1500L
        private const val MAX_INTERVAL_MS = 10000L
        private const val HTTP_TIMEOUT_MS = 1500

        fun sanitizeTargetIp(rawIp: String): String {
            return IptablesFirewallManager.sanitizeHeartbeatIp(rawIp)
        }
    }
}
