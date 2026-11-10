package com.nsaanchor.gatekeeper.firewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Layer 2 Lockdown: Netd & Tethering Binder Bridge via Shizuku (UID 2000).
 *
 * On unrooted Android devices, `/system/bin/iptables` fails with `exit=3 (Permission denied)`.
 * However, Shizuku (ADB Shell `UID 2000`) possesses `NETWORK_STACK`, `CONNECTIVITY_INTERNAL`,
 * and `TETHER_PRIVILEGED` system permissions.
 *
 * This bridge communicates with Android's `system_server` (`UID 1000`) and `netd` (`UID 0`)
 * through the hidden [android.os.INetworkManagementService] Binder interface:
 * 1. Cellular Quota Lockdown: calls `setInterfaceQuota(cellIface, 1L)`. Netd's BandwidthController
 *    installs `quota2 ! --quota 1 -j REJECT` on chain `bw_costly_<cellIface>`, blocking all
 *    forwarded hotspot traffic (`bw_FORWARD`) and tearing down hardware tether offload!
 * 2. Hotspot NAT & IP Forwarding Cut: calls `disableNat(hotspotIface, cellIface)` and
 *    `setIpForwardingEnabled(false)` to drop cross-interface packet forwarding.
 * 3. Bait Pulse: allows [HeartbeatBaitEngine] to temporarily pulse quota open for 150ms
 *    to fire its EN-DC resurrection probe while keeping other apps sinkholed by [GatekeeperVpnService].
 */
object ShizukuNetdFirewallBridge {

    private const val RESTRICTED_QUOTA_BYTES = 1L
    private const val UNLIMITED_QUOTA_BYTES = Long.MAX_VALUE

    private val pulseMutex = Mutex()

    @Volatile
    private var isQuotaLockdownActive = false

    @Volatile
    private var lockedCellularInterfaces = setOf<String>()

    @Volatile
    private var lockedHotspotInterfaces = setOf<String>()

    // Reflection cache for INetworkManagementService
    private var nmsInstance: Any? = null
    private var setInterfaceQuotaMethod: Method? = null
    private var setIpForwardingEnabledMethod: Method? = null
    private var disableNatMethod: Method? = null
    private var enableNatMethod: Method? = null

    @Synchronized
    private fun getOrInitNms(): Any? {
        if (nmsInstance != null) return nmsInstance
        if (!ShizukuShellExecutor.isReady()) return null

        try {
            val rawBinder = SystemServiceHelper.getSystemService("network_management") ?: return null
            val wrappedBinder: IBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.os.INetworkManagementService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val nms = asInterfaceMethod.invoke(null, wrappedBinder) ?: return null

            val nmsClass = nms.javaClass
            setInterfaceQuotaMethod = nmsClass.methods.firstOrNull {
                it.name == "setInterfaceQuota" && it.parameterTypes.size == 2
            }
            setIpForwardingEnabledMethod = nmsClass.methods.firstOrNull {
                it.name == "setIpForwardingEnabled" && it.parameterTypes.size == 1
            }
            disableNatMethod = nmsClass.methods.firstOrNull {
                it.name == "disableNat" && it.parameterTypes.size == 2
            }
            enableNatMethod = nmsClass.methods.firstOrNull {
                it.name == "enableNat" && it.parameterTypes.size == 2
            }

            nmsInstance = nms
            GatekeeperRepository.log(
                LogLevel.INFO,
                "Initialized Shizuku INetworkManagementService Binder bridge successfully"
            )
            return nms
        } catch (e: Throwable) {
            GatekeeperRepository.log(
                LogLevel.WARN,
                "Could not bind INetworkManagementService via ShizukuBinderWrapper: ${e.message}; using ndc fallback"
            )
            return null
        }
    }

    /**
     * Resolves all active concrete cellular interface names (e.g., `rmnet_data0`, `ccmni0`).
     */
    suspend fun resolveConcreteCellularInterfaces(context: Context?): List<String> = withContext(Dispatchers.IO) {
        val ifaces = linkedSetOf<String>()

        // 1. From ConnectivityManager active network LinkProperties
        if (context != null) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val active = cm?.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                    val linkProps = cm.getLinkProperties(active)
                    val name = linkProps?.interfaceName?.trim()
                    if (!name.isNullOrEmpty()) ifaces.add(name)
                }
                @Suppress("DEPRECATION")
                val allNetworks = cm?.allNetworks.orEmpty()
                for (net in allNetworks) {
                    val netCaps = cm?.getNetworkCapabilities(net) ?: continue
                    if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        val lp = cm.getLinkProperties(net)
                        val ifName = lp?.interfaceName?.trim()
                        if (!ifName.isNullOrEmpty()) ifaces.add(ifName)
                    }
                }
            }
        }

        // 2. From /sys/class/net via Shizuku
        if (ShizukuShellExecutor.isReady()) {
            val res = ShizukuShellExecutor.execute("ls -1 /sys/class/net 2>/dev/null", timeoutMs = 2000L)
            if (res.isSuccess) {
                for (line in res.stdout.lineSequence()) {
                    val trimmed = line.trim()
                    if (isCellularIfaceName(trimmed)) {
                        ifaces.add(trimmed)
                    }
                }
            }
        }

        // 3. Fallback defaults if none detected
        if (ifaces.isEmpty()) {
            ifaces.addAll(listOf("rmnet_data0", "rmnet_data1", "ccmni0", "ccmni1"))
        }

        ifaces.toList()
    }

    /**
     * Resolves all active downstream hotspot / tethering interface names (e.g., `ap0`, `wlan1`, `swlan0`, `rndis0`).
     */
    suspend fun resolveConcreteHotspotInterfaces(): List<String> = withContext(Dispatchers.IO) {
        val ifaces = linkedSetOf<String>()

        if (ShizukuShellExecutor.isReady()) {
            // Find interfaces configured with private IP subnets typical of tethering (192.168.*, 172.20.*, 10.*)
            val ipRes = ShizukuShellExecutor.execute("ip -o addr show 2>/dev/null", timeoutMs = 2000L)
            if (ipRes.isSuccess) {
                for (line in ipRes.stdout.lineSequence()) {
                    val parts = line.split("\\s+".toRegex())
                    val ifName = parts.getOrNull(1)?.trim() ?: continue
                    val isHotspotCandidate = ifName.startsWith("ap") ||
                        ifName.startsWith("swlan") ||
                        ifName.startsWith("wlan1") ||
                        ifName.startsWith("rndis") ||
                        ifName.startsWith("usb")
                    if (isHotspotCandidate && !ifName.contains("@")) {
                        ifaces.add(ifName)
                    }
                }
            }

            // Also check /sys/class/net for ap* or swlan*
            val netRes = ShizukuShellExecutor.execute("ls -1 /sys/class/net 2>/dev/null", timeoutMs = 2000L)
            if (netRes.isSuccess) {
                for (line in netRes.stdout.lineSequence()) {
                    val name = line.trim()
                    if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1")) {
                        ifaces.add(name)
                    }
                }
            }
        }

        if (ifaces.isEmpty()) {
            ifaces.addAll(listOf("ap0", "wlan1", "swlan0"))
        }

        ifaces.toList()
    }

    private fun isCellularIfaceName(name: String): Boolean {
        return name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("v4-rmnet") ||
            name.startsWith("v4-ccmni") ||
            name.startsWith("pdp_ip")
    }

    /**
     * Applies Layer 2 Lockdown:
     * - Restricts cellular quota to 1 Byte (blocks bw_FORWARD and tether offload)
     * - Cuts NAT and IP forwarding for hotspot clients
     */
    suspend fun applyLockdown(
        cellInterfaces: List<String>,
        hotspotInterfaces: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        pulseMutex.withLock {
            val nms = getOrInitNms()
            val cellIfaces = cellInterfaces.distinct()
            val hotspotIfaces = hotspotInterfaces.distinct()

            lockedCellularInterfaces = cellIfaces.toSet()
            lockedHotspotInterfaces = hotspotIfaces.toSet()

            var anySuccess = false

            // 1. Enforce Cellular Quota via INetworkManagementService
            for (cell in cellIfaces) {
                val quotaOk = invokeSetInterfaceQuota(nms, cell, RESTRICTED_QUOTA_BYTES)
                if (quotaOk) {
                    anySuccess = true
                } else {
                    // Fallback to ndc shell command
                    ShizukuShellExecutor.execute("ndc bandwidth setiquota $cell $RESTRICTED_QUOTA_BYTES", timeoutMs = 1500L)
                }
            }

            // 2. Disable NAT and IP Forwarding for each (hotspot, cellular) pair
            invokeSetIpForwardingEnabled(nms, false)
            ShizukuShellExecutor.execute("ndc ipfwd disable", timeoutMs = 1500L)
            ShizukuShellExecutor.execute("echo 0 > /proc/sys/net/ipv4/ip_forward 2>/dev/null", timeoutMs = 1500L)

            for (hotspot in hotspotIfaces) {
                for (cell in cellIfaces) {
                    val natOk = invokeDisableNat(nms, hotspot, cell)
                    if (!natOk) {
                        ShizukuShellExecutor.execute("ndc nat disable $hotspot $cell", timeoutMs = 1500L)
                    }
                }
            }

            isQuotaLockdownActive = true
            GatekeeperRepository.log(
                LogLevel.BLOCK,
                "Hotspot & Cellular Netd Lockdown applied (Quota: 1B on [${cellIfaces.joinToString(", ")}], NAT/Forwarding cut for [${hotspotIfaces.joinToString(", ")}])"
            )
            anySuccess
        }
    }

    /**
     * Restores full bandwidth quotas, enables NAT, and restores IP forwarding on 5G NSA restoration.
     */
    suspend fun clearLockdown(): Boolean = withContext(Dispatchers.IO) {
        pulseMutex.withLock {
            val nms = getOrInitNms()
            val cellIfaces = lockedCellularInterfaces
            val hotspotIfaces = lockedHotspotInterfaces

            // 1. Restore unlimited quota
            for (cell in cellIfaces) {
                val ok = invokeSetInterfaceQuota(nms, cell, UNLIMITED_QUOTA_BYTES)
                if (!ok) {
                    ShizukuShellExecutor.execute("ndc bandwidth removeiquota $cell", timeoutMs = 1500L)
                }
            }

            // 2. Re-enable IP forwarding and NAT
            invokeSetIpForwardingEnabled(nms, true)
            ShizukuShellExecutor.execute("ndc ipfwd enable", timeoutMs = 1500L)
            ShizukuShellExecutor.execute("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null", timeoutMs = 1500L)

            for (hotspot in hotspotIfaces) {
                for (cell in cellIfaces) {
                    val ok = invokeEnableNat(nms, hotspot, cell)
                    if (!ok) {
                        ShizukuShellExecutor.execute("ndc nat enable $hotspot $cell", timeoutMs = 1500L)
                    }
                }
            }

            isQuotaLockdownActive = false
            GatekeeperRepository.log(
                LogLevel.UNBLOCK,
                "Hotspot & Cellular Netd Lockdown cleared (Unlimited quota restored, NAT/Forwarding re-enabled)"
            )
            true
        }
    }

    /**
     * Temporarily opens a brief pulse window (e.g. 150ms) to allow the 5G Resurrection Bait
     * packet to transmit to the cellular tower without getting rejected by the 1-byte quota.
     */
    suspend fun <T> withBaitPulseWindow(action: suspend () -> T): T {
        if (!isQuotaLockdownActive) {
            return action()
        }

        pulseMutex.withLock {
            val nms = getOrInitNms()
            val cellIfaces = lockedCellularInterfaces

            // Briefly pulse quota open to 64KB for the bait packet
            for (cell in cellIfaces) {
                invokeSetInterfaceQuota(nms, cell, 65536L)
            }
        }

        return try {
            action()
        } finally {
            pulseMutex.withLock {
                if (isQuotaLockdownActive) {
                    val nms = getOrInitNms()
                    for (cell in lockedCellularInterfaces) {
                        invokeSetInterfaceQuota(nms, cell, RESTRICTED_QUOTA_BYTES)
                    }
                }
            }
        }
    }

    private fun invokeSetInterfaceQuota(nms: Any?, iface: String, bytes: Long): Boolean {
        if (nms == null || setInterfaceQuotaMethod == null) return false
        return try {
            setInterfaceQuotaMethod?.invoke(nms, iface, bytes)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeSetIpForwardingEnabled(nms: Any?, enabled: Boolean): Boolean {
        if (nms == null || setIpForwardingEnabledMethod == null) return false
        return try {
            setIpForwardingEnabledMethod?.invoke(nms, enabled)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeDisableNat(nms: Any?, internalIface: String, externalIface: String): Boolean {
        if (nms == null || disableNatMethod == null) return false
        return try {
            disableNatMethod?.invoke(nms, internalIface, externalIface)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeEnableNat(nms: Any?, internalIface: String, externalIface: String): Boolean {
        if (nms == null || enableNatMethod == null) return false
        return try {
            enableNatMethod?.invoke(nms, internalIface, externalIface)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
