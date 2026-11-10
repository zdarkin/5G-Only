package com.nsaanchor.gatekeeper.data

import android.content.Context
import android.content.SharedPreferences
import com.nsaanchor.gatekeeper.model.GatekeeperConfig
import com.nsaanchor.gatekeeper.model.GatekeeperUiState
import com.nsaanchor.gatekeeper.model.LogEntry
import com.nsaanchor.gatekeeper.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

object GatekeeperRepository {

    private const val PREFS_NAME = "nsa_anchor_gatekeeper_prefs"
    private const val KEY_SELECTED_SIM_SLOT = "selected_sim_slot"
    private const val KEY_HEARTBEAT_IP = "heartbeat_ip"
    private const val KEY_GRACE_PERIOD_MS = "grace_period_ms"
    private const val KEY_HEARTBEAT_INTERVAL_MS = "heartbeat_interval_ms"
    private const val KEY_BLOCK_IPV6 = "block_ipv6"
    private const val KEY_CUSTOM_INTERFACES = "custom_interfaces"

    private const val MAX_LOG_ENTRIES = 400

    private val logIdGenerator = AtomicLong(1L)
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private var prefs: SharedPreferences? = null

    private val _uiState = MutableStateFlow(GatekeeperUiState())
    val uiState: StateFlow<GatekeeperUiState> = _uiState.asStateFlow()

    fun init(context: Context) {
        val sharedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sharedPrefs
        val selectedSlot = sharedPrefs.getInt(KEY_SELECTED_SIM_SLOT, 0).coerceIn(0, 1)
        val loadedConfig = GatekeeperConfig(
            selectedSimSlot = selectedSlot,
            heartbeatIp = sharedPrefs.getString(KEY_HEARTBEAT_IP, "1.1.1.1") ?: "1.1.1.1",
            gracePeriodMs = sharedPrefs.getLong(KEY_GRACE_PERIOD_MS, 2500L),
            heartbeatIntervalMs = sharedPrefs.getLong(KEY_HEARTBEAT_INTERVAL_MS, 3000L),
            blockIpv6 = sharedPrefs.getBoolean(KEY_BLOCK_IPV6, true),
            customInterfaces = sharedPrefs.getString(KEY_CUSTOM_INTERFACES, "") ?: ""
        )
        _uiState.update {
            it.copy(
                config = loadedConfig,
                activeSimLabel = "SIM ${selectedSlot + 1}"
            )
        }
        log(
            LogLevel.INFO,
            "GatekeeperRepository initialized (SIM=${selectedSlot + 1}, target=${loadedConfig.heartbeatIp}, grace=${loadedConfig.gracePeriodMs}ms)"
        )
    }

    fun updateState(transform: (GatekeeperUiState) -> GatekeeperUiState) {
        _uiState.update(transform)
    }

    fun updateConfig(transform: (GatekeeperConfig) -> GatekeeperConfig) {
        var updatedConfig: GatekeeperConfig? = null
        _uiState.update { current ->
            val newConfig = transform(current.config).let {
                it.copy(selectedSimSlot = it.selectedSimSlot.coerceIn(0, 1))
            }
            updatedConfig = newConfig
            current.copy(config = newConfig)
        }
        updatedConfig?.let { cfg ->
            prefs?.edit()?.apply {
                putInt(KEY_SELECTED_SIM_SLOT, cfg.selectedSimSlot.coerceIn(0, 1))
                putString(KEY_HEARTBEAT_IP, cfg.heartbeatIp)
                putLong(KEY_GRACE_PERIOD_MS, cfg.gracePeriodMs)
                putLong(KEY_HEARTBEAT_INTERVAL_MS, cfg.heartbeatIntervalMs)
                putBoolean(KEY_BLOCK_IPV6, cfg.blockIpv6)
                putString(KEY_CUSTOM_INTERFACES, cfg.customInterfaces)
                apply()
            }
        }
    }

    fun log(level: LogLevel, message: String) {
        val timestamp = try {
            LocalTime.now().format(timeFormatter)
        } catch (e: Exception) {
            "00:00:00.000"
        }
        val entry = LogEntry(
            id = logIdGenerator.getAndIncrement(),
            timestamp = timestamp,
            level = level,
            message = message
        )
        _uiState.update { current ->
            val updatedLogs = (current.logs + entry).takeLast(MAX_LOG_ENTRIES)
            current.copy(logs = updatedLogs)
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun recordHeartbeat(statusText: String) {
        _uiState.update { current ->
            current.copy(
                heartbeatCount = current.heartbeatCount + 1L,
                lastHeartbeatStatus = statusText
            )
        }
    }
}
