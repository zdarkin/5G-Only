package com.nsaanchor.gatekeeper.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.firewall.ShizukuShellExecutor
import com.nsaanchor.gatekeeper.model.LogLevel
import com.nsaanchor.gatekeeper.model.SimSlotInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class CellularSignalSnapshot(
    val simSlotIndex: Int = 0,
    val subscriptionId: Int = -1,
    val networkType: Int,
    val overrideNetworkType: Int,
    val nrState: String = "NONE",
    val networkTypeName: String,
    val overrideTypeName: String,
    val is5gNsaOrBetter: Boolean,
    val isLteOnly: Boolean
)

/**
 * Monitors real-time 5G NSA (EN-DC) vs 4G LTE anchor states on Android 12+ (API 31+),
 * pinned strictly to a single user-selected SIM slot (SIM 1 or SIM 2) and enforcing
 * `NR State (NSA): CONNECTED` before considering 5G NSA active.
 */
class TelephonyNsaMonitor(
    private val context: Context,
    private val onSnapshotChanged: (CellularSignalSnapshot) -> Unit
) {
    private val baseTelephonyManager: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isShizukuProbeInFlight = AtomicBoolean(false)

    @Volatile
    private var activeTelephonyManager: TelephonyManager? = null

    @Volatile
    private var activeSimSlotIndex: Int = 0

    @Volatile
    private var activeSubscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    @Volatile
    private var isRegistered = false

    @Volatile
    private var hasReceivedDisplayInfo = false

    @Volatile
    private var lastNetworkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN

    @Volatile
    private var lastOverrideNetworkType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE

    @Volatile
    private var lastNrState: String = "NONE"

    @Volatile
    private var lastShizukuProbeElapsedMs: Long = 0L

    @Volatile
    private var pollingJob: Job? = null

    private val telephonyCallback = object :
        TelephonyCallback(),
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.ServiceStateListener {

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            hasReceivedDisplayInfo = true
            val netType = if (telephonyDisplayInfo.networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                telephonyDisplayInfo.networkType
            } else {
                getSafeDataNetworkType()
            }
            val overrideType = telephonyDisplayInfo.overrideNetworkType
            lastNetworkType = netType
            lastOverrideNetworkType = overrideType

            // Always refresh NR State from active TelephonyManager.serviceState so both
            // upgrades (NOT_RESTRICTED -> CONNECTED) and downgrades (CONNECTED -> NOT_RESTRICTED)
            // are reflected immediately even if onDisplayInfoChanged fires first.
            val polledNrState = getSafeServiceStateNrState()
            if (polledNrState != "NONE") {
                lastNrState = polledNrState
            } else if (!hasNsaOverride(overrideType) && netType != TelephonyManager.NETWORK_TYPE_NR) {
                lastNrState = "NONE"
            }

            emitSnapshot(netType, overrideType, lastNrState)

            if (lastNrState != "CONNECTED" &&
                (hasNsaOverride(overrideType) || netType == TelephonyManager.NETWORK_TYPE_LTE)
            ) {
                probeNrStateViaShizukuIfNeeded(activeSimSlotIndex, activeSubscriptionId)
            }
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            val currentDataNetType = getSafeDataNetworkType()
            val extractedNrState = extractNrState(serviceState)

            val isCapableOfNsaOrNr = currentDataNetType == TelephonyManager.NETWORK_TYPE_LTE ||
                currentDataNetType == TelephonyManager.NETWORK_TYPE_NR ||
                currentDataNetType == TelephonyManager.NETWORK_TYPE_UNKNOWN

            if (!isCapableOfNsaOrNr) {
                lastOverrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
                lastNrState = "NONE"
            } else {
                lastNrState = extractedNrState
            }

            if (currentDataNetType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                lastNetworkType = currentDataNetType
            }

            emitSnapshot(lastNetworkType, lastOverrideNetworkType, lastNrState)

            if (isCapableOfNsaOrNr && lastNrState != "CONNECTED") {
                probeNrStateViaShizukuIfNeeded(activeSimSlotIndex, activeSubscriptionId)
            }
        }
    }

    /**
     * Starts listening for 5G NSA / LTE display info and service state changes
     * exclusively on the specified [simSlotIndex] (0 = SIM 1, 1 = SIM 2).
     *
     * @return true if permission was granted and callback registration succeeded.
     */
    @SuppressLint("MissingPermission")
    fun start(simSlotIndex: Int = GatekeeperRepository.uiState.value.config.selectedSimSlot): Boolean {
        if (isRegistered) {
            stop()
        }
        val baseManager = baseTelephonyManager ?: return false

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return false
        }

        val normalizedSlot = simSlotIndex.coerceIn(0, 1)
        val sims = discoverSims(context)
        val simInfo = sims.firstOrNull { it.slotIndex == normalizedSlot }
        val resolvedSubId = resolveSubscriptionId(context, normalizedSlot, simInfo?.subscriptionId ?: -1)

        val pinnedManager = if (resolvedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && resolvedSubId >= 0) {
            baseManager.createForSubscriptionId(resolvedSubId)
        } else {
            baseManager
        }

        activeSimSlotIndex = normalizedSlot
        activeSubscriptionId = resolvedSubId
        activeTelephonyManager = pinnedManager

        return try {
            hasReceivedDisplayInfo = false
            lastNetworkType = getSafeDataNetworkType()
            lastOverrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
            lastNrState = getSafeServiceStateNrState()

            pinnedManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
            isRegistered = true

            val subLabel = if (resolvedSubId >= 0) "subId=$resolvedSubId" else "default-manager"
            GatekeeperRepository.log(
                LogLevel.INFO,
                "Pinned TelephonyCallback exclusively to SIM ${normalizedSlot + 1} (${simInfo?.carrierName ?: "Unknown"}, $subLabel)"
            )

            // Emit initial snapshot on mainExecutor and start background verification loop
            // so NR State transitions (NOT_RESTRICTED <-> CONNECTED) during bait pings are never missed.
            context.mainExecutor.execute {
                if (isRegistered && activeSimSlotIndex == normalizedSlot) {
                    emitSnapshot(lastNetworkType, lastOverrideNetworkType, lastNrState)
                    if (lastNrState != "CONNECTED") {
                        probeNrStateViaShizukuIfNeeded(normalizedSlot, resolvedSubId)
                    }
                }
            }
            startPollingLoop(normalizedSlot, resolvedSubId)
            true
        } catch (_: SecurityException) {
            isRegistered = false
            false
        } catch (_: Exception) {
            isRegistered = false
            false
        }
    }

    /**
     * Unregisters the [TelephonyCallback] from the active SIM's [TelephonyManager].
     */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        if (!isRegistered) return
        try {
            (activeTelephonyManager ?: baseTelephonyManager)?.unregisterTelephonyCallback(telephonyCallback)
        } catch (_: Exception) {
            // Ignore unregister errors if service is tearing down
        } finally {
            isRegistered = false
            hasReceivedDisplayInfo = false
            activeTelephonyManager = null
        }
    }

    private fun startPollingLoop(simSlotIndex: Int, subscriptionId: Int) {
        pollingJob?.cancel()
        pollingJob = monitorScope.launch {
            while (isActive && isRegistered && activeSimSlotIndex == simSlotIndex) {
                delay(POLL_INTERVAL_MS)
                if (!isRegistered || activeSimSlotIndex != simSlotIndex) break

                val polledNetType = getSafeDataNetworkType()
                val polledNrState = getSafeServiceStateNrState()

                val netChanged = polledNetType != TelephonyManager.NETWORK_TYPE_UNKNOWN &&
                    polledNetType != lastNetworkType
                val nrChanged = polledNrState != "NONE" && polledNrState != lastNrState

                if (netChanged || nrChanged) {
                    if (polledNetType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                        lastNetworkType = polledNetType
                    }
                    if (polledNrState != "NONE") {
                        lastNrState = polledNrState
                    }
                    context.mainExecutor.execute {
                        if (isRegistered && activeSimSlotIndex == simSlotIndex) {
                            emitSnapshot(lastNetworkType, lastOverrideNetworkType, lastNrState)
                        }
                    }
                }

                if (polledNrState == "NONE" &&
                    (lastNrState != "CONNECTED" || hasNsaOverride(lastOverrideNetworkType) || lastNetworkType == TelephonyManager.NETWORK_TYPE_LTE)
                ) {
                    probeNrStateViaShizukuIfNeeded(simSlotIndex, subscriptionId)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSafeDataNetworkType(): Int {
        val manager = activeTelephonyManager ?: baseTelephonyManager ?: return TelephonyManager.NETWORK_TYPE_UNKNOWN
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return TelephonyManager.NETWORK_TYPE_UNKNOWN

        return try {
            manager.dataNetworkType
        } catch (_: SecurityException) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        } catch (_: Exception) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSafeServiceStateNrState(): String {
        val manager = activeTelephonyManager ?: baseTelephonyManager ?: return "NONE"
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return "NONE"

        return try {
            extractNrState(manager.serviceState)
        } catch (_: SecurityException) {
            "NONE"
        } catch (_: Exception) {
            "NONE"
        }
    }

    private fun probeNrStateViaShizukuIfNeeded(simSlotIndex: Int, subscriptionId: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShizukuProbeElapsedMs < SHIZUKU_PROBE_THROTTLE_MS) {
            return
        }
        if (!ShizukuShellExecutor.isReady()) {
            return
        }
        if (!isShizukuProbeInFlight.compareAndSet(false, true)) {
            return
        }
        lastShizukuProbeElapsedMs = now

        monitorScope.launch {
            try {
                val result = ShizukuShellExecutor.execute(
                    command = "dumpsys telephony.registry",
                    timeoutMs = 2500L
                )
                if (!result.isSuccess || result.stdout.isBlank()) {
                    if (result.stderr.isNotBlank()) {
                        GatekeeperRepository.log(
                            LogLevel.WARN,
                            "Shizuku Dumpsys check: ${result.stderr.trim()}"
                        )
                    }
                    return@launch
                }

                val unredactedNrState = extractNrStateFromDumpsysOrNull(
                    dumpsysOutput = result.stdout,
                    simSlotIndex = simSlotIndex,
                    subscriptionId = subscriptionId
                )

                if (unredactedNrState != null) {
                    GatekeeperRepository.log(
                        LogLevel.INFO,
                        "Shizuku Dumpsys parsed NR State (SIM ${simSlotIndex + 1}): $unredactedNrState"
                    )
                }

                if (unredactedNrState != null &&
                    unredactedNrState != lastNrState &&
                    isRegistered &&
                    activeSimSlotIndex == simSlotIndex
                ) {
                    lastNrState = unredactedNrState
                    context.mainExecutor.execute {
                        if (isRegistered && activeSimSlotIndex == simSlotIndex) {
                            emitSnapshot(lastNetworkType, lastOverrideNetworkType, lastNrState)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore background probe errors
            } finally {
                isShizukuProbeInFlight.set(false)
            }
        }
    }

    private fun emitSnapshot(networkType: Int, overrideNetworkType: Int, nrState: String) {
        val requireStrict = GatekeeperRepository.uiState.value.config.requireStrictNrState
        val snapshot = evaluateSnapshot(
            simSlotIndex = activeSimSlotIndex,
            subscriptionId = activeSubscriptionId,
            networkType = networkType,
            overrideNetworkType = overrideNetworkType,
            nrState = nrState,
            requireStrictNrState = requireStrict
        )
        onSnapshotChanged(snapshot)
    }

    companion object {
        // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE is constant 4 (deprecated in API 31 in favor of NR_ADVANCED)
        @Suppress("DEPRECATION")
        private const val OVERRIDE_NR_NSA_MMWAVE = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE

        private const val SHIZUKU_PROBE_THROTTLE_MS = 1500L
        private const val POLL_INTERVAL_MS = 1500L

        private val NR_STATE_REGEX = Regex(
            """(?:mNrState|nrState|NR\s*State(?:\s*\(NSA\))?)\s*[=:]\s*([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Discovers SIM 1 (slot 0) and SIM 2 (slot 1) via [SubscriptionManager] and updates
         * [GatekeeperRepository] with the available SIMs and active SIM label.
         */
        @SuppressLint("MissingPermission")
        fun discoverSims(context: Context): List<SimSlotInfo> {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val subManager = context.getSystemService(SubscriptionManager::class.java)
            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()

            val activeSubs = if (hasPermission && subManager != null) {
                runCatching { subManager.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val simList = (0..1).map { slotIndex ->
                val subInfo = activeSubs.firstOrNull { it.simSlotIndex == slotIndex }
                val subId = subInfo?.subscriptionId
                    ?: resolveSubscriptionId(context, slotIndex, -1)

                val rawCarrier = subInfo?.carrierName?.toString()?.trim().orEmpty()
                val rawDisplay = subInfo?.displayName?.toString()?.trim().orEmpty()
                val carrierName = when {
                    rawCarrier.isNotEmpty() -> rawCarrier
                    rawDisplay.isNotEmpty() -> rawDisplay
                    hasPermission -> "No SIM"
                    else -> "Slot ${slotIndex + 1}"
                }

                val isActiveData = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                    subId >= 0 &&
                    defaultDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                    subId == defaultDataSubId

                SimSlotInfo(
                    slotIndex = slotIndex,
                    subscriptionId = if (subId >= 0) subId else -1,
                    displayName = "SIM ${slotIndex + 1}",
                    carrierName = carrierName,
                    isActiveDataSim = isActiveData
                )
            }

            GatekeeperRepository.updateState { current ->
                val selectedSlot = current.config.selectedSimSlot.coerceIn(0, 1)
                val selectedSim = simList.firstOrNull { it.slotIndex == selectedSlot } ?: simList.first()
                val label = if (selectedSim.carrierName.isNotBlank() &&
                    selectedSim.carrierName != "No SIM" &&
                    !selectedSim.carrierName.startsWith("Slot")
                ) {
                    "${selectedSim.displayName} (${selectedSim.carrierName})"
                } else {
                    selectedSim.displayName
                }
                current.copy(
                    availableSims = simList,
                    activeSimLabel = label
                )
            }

            return simList
        }

        @SuppressLint("MissingPermission")
        private fun resolveSubscriptionId(context: Context, slotIndex: Int, knownSubId: Int): Int {
            if (knownSubId >= 0 && knownSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return knownSubId
            }
            return try {
                val subManager = context.getSystemService(SubscriptionManager::class.java)
                val subInfo = subManager?.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                if (subInfo != null && subInfo.subscriptionId >= 0) {
                    return subInfo.subscriptionId
                }
                @Suppress("DEPRECATION")
                val subIds = subManager?.getSubscriptionIds(slotIndex)
                val firstId = subIds?.firstOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                if (firstId >= 0) firstId else SubscriptionManager.INVALID_SUBSCRIPTION_ID
            } catch (_: Throwable) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        }

        /**
         * Extracts the highest-ranked 5G NSA `nrState` across all registration entries in [ServiceState].
         *
         * Why highest-ranked: A phone has multiple `NetworkRegistrationInfo` entries (CS voice domain
         * and PS WWAN data domain). CS domain always reports `nrState=NONE`, whereas PS WWAN reports
         * `nrState=CONNECTED` when attached to a 5G NSA secondary cell.
         */
        fun extractNrState(serviceState: ServiceState?): String {
            if (serviceState == null) return "NONE"
            var bestState = "NONE"

            try {
                val regList = serviceState.networkRegistrationInfoList
                for (regInfo in regList) {
                    if (regInfo == null) continue

                    // 1. Try invoking getNrState() directly if accessible via reflection
                    val reflectedInt = runCatching {
                        regInfo.javaClass.getMethod("getNrState").invoke(regInfo) as? Int
                    }.getOrNull()

                    if (reflectedInt != null) {
                        val candidate = nrStateIntToString(reflectedInt)
                        if (nrStateRank(candidate) > nrStateRank(bestState)) {
                            bestState = candidate
                        }
                    }

                    // 2. Also inspect NetworkRegistrationInfo.toString()
                    val fromRegString = extractNrStateFromText(regInfo.toString())
                    if (nrStateRank(fromRegString) > nrStateRank(bestState)) {
                        bestState = fromRegString
                    }
                }
            } catch (_: Throwable) {
                // Fall back to ServiceState.toString() parsing below
            }

            // 3. Also inspect ServiceState.toString() across all registration records
            val fromServiceStateText = extractNrStateFromText(serviceState.toString())
            if (nrStateRank(fromServiceStateText) > nrStateRank(bestState)) {
                bestState = fromServiceStateText
            }

            return bestState
        }

        /**
         * Parses `ServiceState.toString()` or `NetworkRegistrationInfo.toString()` text and returns
         * the highest-ranked `nrState` (`CONNECTED` > `NOT_RESTRICTED` > `RESTRICTED` > `NONE`).
         */
        fun extractNrStateFromText(rawText: String?): String =
            extractNrStateFromTextOrNull(rawText) ?: "NONE"

        private fun extractNrStateFromTextOrNull(rawText: String?): String? {
            if (rawText.isNullOrBlank()) return null
            var foundAny = false
            var bestState = "NONE"

            for (match in NR_STATE_REGEX.findAll(rawText)) {
                foundAny = true
                val rawValue = match.groupValues.getOrNull(1).orEmpty()
                val normalized = normalizeNrState(rawValue)
                if (nrStateRank(normalized) > nrStateRank(bestState)) {
                    bestState = normalized
                }
            }
            return if (foundAny) bestState else null
        }

        /**
         * Extracts the unredacted `nrState` for a specific [simSlotIndex] from `dumpsys telephony.registry`.
         */
        fun extractNrStateFromDumpsys(
            dumpsysOutput: String,
            simSlotIndex: Int,
            subscriptionId: Int = -1
        ): String = extractNrStateFromDumpsysOrNull(dumpsysOutput, simSlotIndex, subscriptionId) ?: "NONE"

        fun extractNrStateFromDumpsysOrNull(
            dumpsysOutput: String,
            simSlotIndex: Int,
            subscriptionId: Int = -1
        ): String? {
            if (dumpsysOutput.isBlank()) return null

            // Locate "Phone Id=<simSlotIndex>" or "mPhoneId=<simSlotIndex>" or "slotId=<simSlotIndex>"
            val phoneMarker = Regex("""(?:Phone\s*Id|mPhoneId|slotId|mSlotIndex|mSimSlotIndex)\s*[=:]\s*$simSlotIndex\b""", RegexOption.IGNORE_CASE)
            val anyPhoneMarker = Regex("""(?:Phone\s*Id|mPhoneId|slotId|mSlotIndex|mSimSlotIndex)\s*[=:]\s*\d+\b""", RegexOption.IGNORE_CASE)

            val startMatch = phoneMarker.find(dumpsysOutput)
            if (startMatch != null) {
                val sectionStart = startMatch.range.first
                val nextMatch = anyPhoneMarker.find(dumpsysOutput, startMatch.range.last + 1)
                val sectionEnd = nextMatch?.range?.first ?: dumpsysOutput.length
                val slotSection = dumpsysOutput.substring(sectionStart, sectionEnd)
                val slotNrState = extractNrStateFromTextOrNull(slotSection)
                if (slotNrState != null) {
                    return slotNrState
                }
            }

            if (subscriptionId >= 0) {
                val subLineMatch = dumpsysOutput.lineSequence()
                    .filter {
                        (it.contains("mServiceState", ignoreCase = true) || it.contains("ServiceState", ignoreCase = true)) &&
                            (it.contains("$subscriptionId") || it.contains("subId=$subscriptionId", ignoreCase = true))
                    }
                    .joinToString("\n")
                val subNrState = extractNrStateFromTextOrNull(subLineMatch)
                if (subNrState != null) {
                    return subNrState
                }
            }

            // Only fall back to scanning the full output if no phone/slot sections exist at all
            if (anyPhoneMarker.find(dumpsysOutput) == null) {
                return extractNrStateFromTextOrNull(dumpsysOutput)
            }
            return null
        }

        fun normalizeNrState(raw: String?): String {
            val cleaned = raw?.trim()?.uppercase()?.removeSuffix(",")?.removeSuffix("}") ?: return "NONE"
            return when (cleaned) {
                "3", "CONNECTED", "NR_STATE_CONNECTED" -> "CONNECTED"
                "2", "NOT_RESTRICTED", "NR_STATE_NOT_RESTRICTED" -> "NOT_RESTRICTED"
                "1", "RESTRICTED", "NR_STATE_RESTRICTED" -> "RESTRICTED"
                "0", "-1", "NONE", "NR_STATE_NONE", "" -> "NONE"
                else -> "NONE"
            }
        }

        private fun nrStateIntToString(value: Int): String = when (value) {
            3 -> "CONNECTED"
            2 -> "NOT_RESTRICTED"
            1 -> "RESTRICTED"
            else -> "NONE"
        }

        private fun nrStateRank(state: String): Int = when (normalizeNrState(state)) {
            "CONNECTED" -> 3
            "NOT_RESTRICTED" -> 2
            "RESTRICTED" -> 1
            else -> 0
        }

        fun hasNsaOverride(overrideNetworkType: Int): Boolean =
            overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
                overrideNetworkType == OVERRIDE_NR_NSA_MMWAVE

        /**
         * Evaluates whether the cellular radio is considered 5G:
         * - 5G SA (`NETWORK_TYPE_NR`), OR
         * - If [requireStrictNrState] is true: requires `NR State (NSA) == CONNECTED`.
         * - If [requireStrictNrState] is false: allows whenever `NR State == CONNECTED` OR carrier shows 5G NSA (`NR_NSA` / `NR_ADVANCED`).
         */
        fun evaluateSnapshot(
            simSlotIndex: Int = 0,
            subscriptionId: Int = -1,
            networkType: Int,
            overrideNetworkType: Int,
            nrState: String = "NONE",
            requireStrictNrState: Boolean = false
        ): CellularSignalSnapshot {
            val normalizedNrState = normalizeNrState(nrState)
            val is5gSa = networkType == TelephonyManager.NETWORK_TYPE_NR
            val hasNsaOverride = hasNsaOverride(overrideNetworkType)
            val isAnchorCapable = networkType == TelephonyManager.NETWORK_TYPE_LTE ||
                networkType == TelephonyManager.NETWORK_TYPE_NR ||
                networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN

            val is5gNsaOrBetter = when {
                is5gSa -> true
                requireStrictNrState -> normalizedNrState == "CONNECTED" && isAnchorCapable
                else -> (normalizedNrState == "CONNECTED" || hasNsaOverride) && isAnchorCapable
            }
            val isLteOnly = !is5gNsaOrBetter

            return CellularSignalSnapshot(
                simSlotIndex = simSlotIndex,
                subscriptionId = subscriptionId,
                networkType = networkType,
                overrideNetworkType = overrideNetworkType,
                nrState = normalizedNrState,
                networkTypeName = networkTypeToName(networkType),
                overrideTypeName = overrideTypeToName(overrideNetworkType),
                is5gNsaOrBetter = is5gNsaOrBetter,
                isLteOnly = isLteOnly
            )
        }

            return CellularSignalSnapshot(
                simSlotIndex = simSlotIndex,
                subscriptionId = subscriptionId,
                networkType = networkType,
                overrideNetworkType = overrideNetworkType,
                nrState = normalizedNrState,
                networkTypeName = networkTypeToName(networkType),
                overrideTypeName = overrideTypeToName(overrideNetworkType),
                is5gNsaOrBetter = is5gNsaOrBetter,
                isLteOnly = isLteOnly
            )
        }

        fun networkTypeToName(networkType: Int): String = when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "NR (5G SA)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
            else -> "NET_TYPE_$networkType"
        }

        fun overrideTypeToName(overrideType: Int): String = when (overrideType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
            OVERRIDE_NR_NSA_MMWAVE -> "NR_NSA_MMWAVE"
            else -> "OVERRIDE_$overrideType"
        }
    }
}
