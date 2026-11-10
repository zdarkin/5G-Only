package com.nsaanchor.gatekeeper.model

enum class NetworkAnchorState(val displayTitle: String, val statusSummary: String) {
    STOPPED("STOPPED", "Service Stopped (Traffic Unblocked)"),
    STARTING_FAIL_CLOSED("INITIALIZING", "Initializing (Fail-Closed Active)"),
    CONNECTED_5G_NSA("5G NSA ACTIVE", "5G NSA Connected"),
    HANDOVER_GRACE_PERIOD("HANDOVER GRACE", "LTE Handover Grace Period"),
    LTE_LOCKDOWN_SEARCHING("LTE LOCKDOWN", "LTE (Blocking & Searching)"),
    ERROR("ERROR", "Error (Check Shizuku / Permissions)")
}

enum class ShizukuStatus(val label: String) {
    NOT_RUNNING("Shizuku Not Running"),
    PERMISSION_REQUIRED("Permission Required"),
    READY_SHELL("Connected (ADB Shell UID 2000)"),
    READY_ROOT("Connected (Root UID 0)"),
    ERROR("Shizuku Error")
}

enum class LogLevel(val tag: String) {
    INFO("INFO"),
    WARN("WARN"),
    BLOCK("BLOCK"),
    UNBLOCK("5G-OK"),
    HEARTBEAT("BAIT"),
    ERROR("ERR")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

data class SimSlotInfo(
    val slotIndex: Int,          // 0 = SIM 1, 1 = SIM 2
    val subscriptionId: Int,     // Android subId (-1 if unresolvable)
    val displayName: String,     // e.g. "SIM 1"
    val carrierName: String,     // e.g. "Smart" / "Globe" / "Slot 1"
    val isActiveDataSim: Boolean = false
)

data class GatekeeperConfig(
    val selectedSimSlot: Int = 0, // 0 = SIM 1, 1 = SIM 2
    val requireStrictNrState: Boolean = false, // true = require NR State: CONNECTED; false = allow when 5G NSA (NR_NSA) is active
    val heartbeatIp: String = "1.1.1.1",
    val gracePeriodMs: Long = 2500L,
    val heartbeatIntervalMs: Long = 3000L,
    val blockIpv6: Boolean = true,
    val customInterfaces: String = ""
)

data class GatekeeperUiState(
    val isServiceRunning: Boolean = false,
    val anchorState: NetworkAnchorState = NetworkAnchorState.STOPPED,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val shizukuUid: Int = -1,
    val availableSims: List<SimSlotInfo> = listOf(
        SimSlotInfo(slotIndex = 0, subscriptionId = -1, displayName = "SIM 1", carrierName = "Slot 1"),
        SimSlotInfo(slotIndex = 1, subscriptionId = -1, displayName = "SIM 2", carrierName = "Slot 2")
    ),
    val activeSimLabel: String = "SIM 1",
    val rawNetworkType: String = "Unknown",
    val overrideNetworkType: String = "NONE",
    val nrState: String = "NONE",
    val detectedInterfaces: List<String> = listOf("rmnet+", "ccmni+", "v4-rmnet+", "v4-ccmni+"),
    val isFirewallActive: Boolean = false,
    val heartbeatCount: Long = 0L,
    val lastHeartbeatStatus: String = "Idle",
    val graceRemainingMs: Long = 0L,
    val config: GatekeeperConfig = GatekeeperConfig(),
    val logs: List<LogEntry> = emptyList()
)
