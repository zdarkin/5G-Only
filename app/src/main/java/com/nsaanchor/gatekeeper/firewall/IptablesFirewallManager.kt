package com.nsaanchor.gatekeeper.firewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.model.LogLevel

object IptablesFirewallManager {

    const val CHAIN_NAME = "NSA_LOCKDOWN"
    const val DEFAULT_HEARTBEAT_IP = "1.1.1.1"

    val DEFAULT_INTERFACES: List<String> = listOf(
        "rmnet+",
        "ccmni+",
        "v4-rmnet+",
        "v4-ccmni+"
    )

    private val IPV4_REGEX = Regex("""^((25[0-5]|(2[0-4]|1\d|[1-9]|)\d)\.?\b){4}$""")
    private val IFACE_REGEX = Regex("""^[a-zA-Z0-9_+-]+$""")

    private val KNOWN_CELLULAR_PREFIXES = listOf(
        "rmnet",
        "ccmni",
        "v4-rmnet",
        "v4-ccmni",
        "pdp_ip",
        "seth_w",
        "usb"
    )

    fun isValidIpv4(ip: String): Boolean {
        val trimmed = ip.trim()
        return trimmed.count { it == '.' } == 3 &&
            !trimmed.endsWith(".") &&
            IPV4_REGEX.matches(trimmed)
    }

    fun sanitizeHeartbeatIp(rawIp: String): String {
        val trimmed = rawIp.trim()
        return if (isValidIpv4(trimmed)) trimmed else DEFAULT_HEARTBEAT_IP
    }

    fun isValidInterfaceName(iface: String): Boolean {
        val trimmed = iface.trim()
        return trimmed.isNotEmpty() && trimmed.length <= 15 && IFACE_REGEX.matches(trimmed)
    }

    suspend fun detectMobileInterfaces(
        context: Context? = null,
        customInterfacesCsv: String = ""
    ): List<String> {
        val interfaces = linkedSetOf<String>()
        interfaces.addAll(DEFAULT_INTERFACES)

        // 1. Inspect active cellular network LinkProperties via ConnectivityManager
        if (context != null) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    val linkProps = cm.getLinkProperties(activeNetwork)
                    val activeIface = linkProps?.interfaceName?.trim()
                    if (!activeIface.isNullOrEmpty() && isValidInterfaceName(activeIface)) {
                        interfaces.add(activeIface)
                    }
                }
            }
        }

        // 2. Inspect /sys/class/net via Shizuku shell if available
        if (ShizukuShellExecutor.isReady()) {
            val sysNetResult = ShizukuShellExecutor.execute("ls -1 /sys/class/net 2>/dev/null", timeoutMs = 3000L)
            if (sysNetResult.isSuccess && sysNetResult.stdout.isNotBlank()) {
                val hwIfaces = sysNetResult.stdout
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && isValidInterfaceName(it) }
                    .toList()

                val cellularHw = hwIfaces.filter { candidate ->
                    KNOWN_CELLULAR_PREFIXES.any { prefix -> candidate.startsWith(prefix) }
                }

                if (cellularHw.isNotEmpty()) {
                    for (hw in cellularHw) {
                        // Convert non-standard cellular prefixes (e.g., pdp_ip0 -> pdp_ip+) if not already covered
                        val coveredByDefault = hw.startsWith("rmnet") ||
                            hw.startsWith("ccmni") ||
                            hw.startsWith("v4-rmnet") ||
                            hw.startsWith("v4-ccmni")
                        if (!coveredByDefault) {
                            val wildcard = hw.trimEnd { it.isDigit() }.let { base ->
                                if (base.isNotEmpty()) "$base+" else hw
                            }
                            if (isValidInterfaceName(wildcard)) {
                                interfaces.add(wildcard)
                            }
                        }
                    }
                }
            }
        }

        // 3. Merge user-specified custom interfaces
        if (customInterfacesCsv.isNotBlank()) {
            customInterfacesCsv
                .split(",", ";", " ")
                .map { it.trim() }
                .filter { isValidInterfaceName(it) }
                .forEach { interfaces.add(it) }
        }

        val finalInterfaces = interfaces.toList()
        GatekeeperRepository.updateState { it.copy(detectedInterfaces = finalInterfaces) }
        return finalInterfaces
    }

    fun buildLockdownScript(
        heartbeatIp: String,
        interfaces: List<String> = DEFAULT_INTERFACES,
        blockIpv6: Boolean = true,
        appUid: Int? = null,
        staleInterfaces: List<String> = emptyList()
    ): String {
        val safeIp = sanitizeHeartbeatIp(heartbeatIp)
        val safeInterfaces = interfaces
            .map { it.trim() }
            .filter { isValidInterfaceName(it) }
            .distinct()
            .ifEmpty { DEFAULT_INTERFACES }

        val safeStaleInterfaces = staleInterfaces
            .map { it.trim() }
            .filter { isValidInterfaceName(it) && it !in safeInterfaces }
            .distinct()

        return buildString {
            appendLine("set -e")
            // 0. Remove hooks for any custom interfaces that were removed since last apply
            for (stale in safeStaleInterfaces) {
                appendLine("while iptables -w -D OUTPUT -o $stale -j $CHAIN_NAME 2>/dev/null; do :; done")
                appendLine("while iptables -w -D FORWARD -o $stale -j $CHAIN_NAME 2>/dev/null; do :; done")
                appendLine("while ip6tables -w -D OUTPUT -o $stale -j $CHAIN_NAME 2>/dev/null; do :; done")
                appendLine("while ip6tables -w -D FORWARD -o $stale -j $CHAIN_NAME 2>/dev/null; do :; done")
            }
            // 1. Create or flush the custom chain so rules never duplicate
            appendLine("iptables -w -N $CHAIN_NAME 2>/dev/null || iptables -w -F $CHAIN_NAME")
            // 2. Allow the Heartbeat (Bait) IP
            appendLine("iptables -w -A $CHAIN_NAME -d $safeIp -j ACCEPT")
            // 3. Drop everything else inside NSA_LOCKDOWN
            appendLine("iptables -w -A $CHAIN_NAME -j DROP")

            // 4. Hook OUTPUT (Phone apps) and FORWARD (Hotspot clients) idempotently on mobile interfaces
            for (iface in safeInterfaces) {
                appendLine(
                    "iptables -w -C OUTPUT -o $iface -j $CHAIN_NAME 2>/dev/null || " +
                        "iptables -w -I OUTPUT 1 -o $iface -j $CHAIN_NAME"
                )
                appendLine(
                    "iptables -w -C FORWARD -o $iface -j $CHAIN_NAME 2>/dev/null || " +
                        "iptables -w -I FORWARD 1 -o $iface -j $CHAIN_NAME"
                )
            }

            // 5. Optional IPv6 lockdown to prevent dual-stack LTE bypass while preserving CLAT & RA/ND
            if (blockIpv6) {
                appendLine("{")
                appendLine("  ip6tables -w -N $CHAIN_NAME 2>/dev/null || ip6tables -w -F $CHAIN_NAME")
                appendLine("  ip6tables -w -A $CHAIN_NAME -p ipv6-icmp -j ACCEPT")
                if (appUid != null && appUid > 0) {
                    appendLine("  ip6tables -w -A $CHAIN_NAME -m owner --uid-owner $appUid -j ACCEPT")
                }
                appendLine("  ip6tables -w -A $CHAIN_NAME -j DROP")
                for (iface in safeInterfaces.filterNot { it.startsWith("v4-") }) {
                    appendLine(
                        "  ip6tables -w -C OUTPUT -o $iface -j $CHAIN_NAME 2>/dev/null || " +
                            "ip6tables -w -I OUTPUT 1 -o $iface -j $CHAIN_NAME"
                    )
                    appendLine(
                        "  ip6tables -w -C FORWARD -o $iface -j $CHAIN_NAME 2>/dev/null || " +
                            "ip6tables -w -I FORWARD 1 -o $iface -j $CHAIN_NAME"
                    )
                }
                appendLine("} 2>/dev/null || true")
            } else {
                appendLine("{")
                for (iface in safeInterfaces.filterNot { it.startsWith("v4-") }) {
                    appendLine("  while ip6tables -w -D OUTPUT -o $iface -j $CHAIN_NAME 2>/dev/null; do :; done")
                    appendLine("  while ip6tables -w -D FORWARD -o $iface -j $CHAIN_NAME 2>/dev/null; do :; done")
                }
                appendLine("  ip6tables -w -F $CHAIN_NAME 2>/dev/null || true")
                appendLine("  ip6tables -w -X $CHAIN_NAME 2>/dev/null || true")
                appendLine("} 2>/dev/null || true")
            }
        }.trim()
    }

    fun buildCleanupScript(interfaces: List<String> = DEFAULT_INTERFACES): String {
        val allInterfaces = (DEFAULT_INTERFACES + interfaces)
            .map { it.trim() }
            .filter { isValidInterfaceName(it) }
            .distinct()

        return buildString {
            for (iface in allInterfaces) {
                appendLine("while iptables -w -D OUTPUT -o $iface -j $CHAIN_NAME 2>/dev/null; do :; done")
                appendLine("while iptables -w -D FORWARD -o $iface -j $CHAIN_NAME 2>/dev/null; do :; done")
                appendLine("while ip6tables -w -D OUTPUT -o $iface -j $CHAIN_NAME 2>/dev/null; do :; done")
                appendLine("while ip6tables -w -D FORWARD -o $iface -j $CHAIN_NAME 2>/dev/null; do :; done")
            }
            appendLine("iptables -w -F $CHAIN_NAME 2>/dev/null || true")
            appendLine("iptables -w -X $CHAIN_NAME 2>/dev/null || true")
            appendLine("ip6tables -w -F $CHAIN_NAME 2>/dev/null || true")
            appendLine("ip6tables -w -X $CHAIN_NAME 2>/dev/null || true")
        }.trim()
    }

    suspend fun applyLockdown(
        context: Context? = null,
        heartbeatIp: String = GatekeeperRepository.uiState.value.config.heartbeatIp,
        blockIpv6: Boolean = GatekeeperRepository.uiState.value.config.blockIpv6,
        customInterfaces: String = GatekeeperRepository.uiState.value.config.customInterfaces
    ): Boolean {
        val safeIp = sanitizeHeartbeatIp(heartbeatIp)
        if (safeIp != heartbeatIp.trim()) {
            GatekeeperRepository.log(
                LogLevel.WARN,
                "Invalid Heartbeat IP '$heartbeatIp' sanitized to '$safeIp'"
            )
        }

        val previousInterfaces = GatekeeperRepository.uiState.value.detectedInterfaces
        val interfaces = detectMobileInterfaces(context, customInterfaces)
        val staleInterfaces = previousInterfaces.filterNot { it in interfaces }
        val appUid = context?.applicationInfo?.uid
        val script = buildLockdownScript(
            heartbeatIp = safeIp,
            interfaces = interfaces,
            blockIpv6 = blockIpv6,
            appUid = appUid,
            staleInterfaces = staleInterfaces
        )

        val result = ShizukuShellExecutor.execute(script)
        return if (result.isSuccess) {
            GatekeeperRepository.updateState { it.copy(isFirewallActive = true) }
            GatekeeperRepository.log(
                LogLevel.BLOCK,
                "Dropped to LTE -> Rules Applied (Chain: $CHAIN_NAME, Allow: $safeIp, Interfaces: ${interfaces.joinToString(",")})"
            )
            true
        } else {
            val uid = GatekeeperRepository.uiState.value.shizukuUid
            val hint = if (uid == 2000) {
                " (ADB Shell UID 2000 may be restricted by SELinux from modifying netfilter on this ROM; Root Shizuku UID 0 recommended)"
            } else {
                ""
            }
            GatekeeperRepository.log(
                LogLevel.ERROR,
                "Failed to apply $CHAIN_NAME rules (exit=${result.exitCode}): ${result.combinedOutput}$hint"
            )
            false
        }
    }

    suspend fun clearLockdown(
        context: Context? = null,
        customInterfaces: String = GatekeeperRepository.uiState.value.config.customInterfaces
    ): Boolean {
        val knownInterfaces = (
            GatekeeperRepository.uiState.value.detectedInterfaces +
                DEFAULT_INTERFACES +
                customInterfaces.split(",", ";", " ").map { it.trim() }
            ).filter { isValidInterfaceName(it) }.distinct()

        val script = buildCleanupScript(knownInterfaces)
        val result = ShizukuShellExecutor.execute(script)
        return if (result.isSuccess) {
            GatekeeperRepository.updateState { it.copy(isFirewallActive = false) }
            GatekeeperRepository.log(
                LogLevel.UNBLOCK,
                "5G Restored -> Rules Cleared ($CHAIN_NAME flushed & removed)"
            )
            true
        } else {
            GatekeeperRepository.log(
                LogLevel.ERROR,
                "Failed to clear $CHAIN_NAME rules (exit=${result.exitCode}): ${result.combinedOutput}"
            )
            false
        }
    }

    suspend fun verifyChainActive(): Boolean {
        val result = ShizukuShellExecutor.execute("iptables -w -L $CHAIN_NAME -n 2>/dev/null")
        val active = result.isSuccess
        GatekeeperRepository.updateState { it.copy(isFirewallActive = active) }
        return active
    }
}
