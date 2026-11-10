package com.nsaanchor.gatekeeper.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class CellularSignalSnapshot(
    val networkType: Int,
    val overrideNetworkType: Int,
    val networkTypeName: String,
    val overrideTypeName: String,
    val is5gNsaOrBetter: Boolean,
    val isLteOnly: Boolean
)

/**
 * Monitors real-time 5G NSA (EN-DC) vs 4G LTE anchor states on Android 12+ (API 31+).
 *
 * Uses [TelephonyCallback.DisplayInfoListener] and [TelephonyCallback.ServiceStateListener]
 * to distinguish true 5G NSA / 5G NR Advanced secondary carrier attachment from plain 4G LTE
 * or LTE+ Carrier Aggregation.
 */
class TelephonyNsaMonitor(
    private val context: Context,
    private val onSnapshotChanged: (CellularSignalSnapshot) -> Unit
) {
    private val telephonyManager: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Volatile
    private var isRegistered = false

    @Volatile
    private var hasReceivedDisplayInfo = false

    @Volatile
    private var lastNetworkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN

    @Volatile
    private var lastOverrideNetworkType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE

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
            emitSnapshot(netType, overrideType)
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            val currentDataNetType = getSafeDataNetworkType()
            // If data network drops to 3G/2G/non-LTE/non-NR, clear any stale 5G NSA override.
            // If data network is LTE or NR, preserve lastOverrideNetworkType from DisplayInfoListener
            // so ServiceStateListener never overwrites a valid NR_NSA override with NONE.
            val isCapableOfNsaOrNr = currentDataNetType == TelephonyManager.NETWORK_TYPE_LTE ||
                currentDataNetType == TelephonyManager.NETWORK_TYPE_NR ||
                currentDataNetType == TelephonyManager.NETWORK_TYPE_UNKNOWN

            if (!isCapableOfNsaOrNr) {
                lastOverrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
            }

            if (!hasReceivedDisplayInfo) {
                lastNetworkType = currentDataNetType
                // Wait for DisplayInfoListener if currently on LTE so we don't emit a false LTE-only
                // snapshot milliseconds before DisplayInfoListener reports OVERRIDE_NETWORK_TYPE_NR_NSA.
                if (currentDataNetType != TelephonyManager.NETWORK_TYPE_LTE) {
                    emitSnapshot(currentDataNetType, lastOverrideNetworkType)
                }
            } else if (currentDataNetType != TelephonyManager.NETWORK_TYPE_UNKNOWN &&
                currentDataNetType != lastNetworkType
            ) {
                lastNetworkType = currentDataNetType
                emitSnapshot(lastNetworkType, lastOverrideNetworkType)
            }
        }
    }

    /**
     * Starts listening for 5G NSA / LTE display info and service state changes.
     * @return true if permission was granted and callback registration succeeded.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRegistered) return true
        val manager = telephonyManager ?: return false

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return false
        }

        return try {
            hasReceivedDisplayInfo = false
            lastNetworkType = getSafeDataNetworkType()
            lastOverrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
            manager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
            isRegistered = true
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
     * Unregisters the [TelephonyCallback].
     */
    fun stop() {
        if (!isRegistered) return
        try {
            telephonyManager?.unregisterTelephonyCallback(telephonyCallback)
        } catch (_: Exception) {
            // Ignore unregister errors if service is tearing down
        } finally {
            isRegistered = false
            hasReceivedDisplayInfo = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSafeDataNetworkType(): Int {
        val manager = telephonyManager ?: return TelephonyManager.NETWORK_TYPE_UNKNOWN
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

    private fun emitSnapshot(networkType: Int, overrideNetworkType: Int) {
        val snapshot = evaluateSnapshot(networkType, overrideNetworkType)
        onSnapshotChanged(snapshot)
    }

    companion object {
        // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE is constant 4 (deprecated in API 31 in favor of NR_ADVANCED)
        @Suppress("DEPRECATION")
        private const val OVERRIDE_NR_NSA_MMWAVE = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE

        fun evaluateSnapshot(networkType: Int, overrideNetworkType: Int): CellularSignalSnapshot {
            val hasNsaOverride =
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
                    overrideNetworkType == OVERRIDE_NR_NSA_MMWAVE

            val is5gNsaOrBetter =
                networkType == TelephonyManager.NETWORK_TYPE_NR ||
                    (hasNsaOverride && (
                        networkType == TelephonyManager.NETWORK_TYPE_LTE ||
                            networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN
                        ))

            val isLteOnly = !is5gNsaOrBetter

            return CellularSignalSnapshot(
                networkType = networkType,
                overrideNetworkType = overrideNetworkType,
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
