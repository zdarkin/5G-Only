package com.nsaanchor.gatekeeper.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.firewall.ShizukuShellExecutor
import com.nsaanchor.gatekeeper.model.GatekeeperUiState
import com.nsaanchor.gatekeeper.service.AnchorGatekeeperService
import kotlinx.coroutines.flow.StateFlow

class GatekeeperViewModel : ViewModel() {

    val uiState: StateFlow<GatekeeperUiState> = GatekeeperRepository.uiState

    fun toggleService(context: Context) {
        if (uiState.value.isServiceRunning) {
            AnchorGatekeeperService.stopService(context)
        } else {
            AnchorGatekeeperService.startService(context)
        }
    }

    fun forceFlushFirewall(context: Context) {
        AnchorGatekeeperService.forceFlushFirewall(context)
    }

    fun refreshShizuku() {
        ShizukuShellExecutor.refreshStatus()
    }

    fun requestShizukuPermission() {
        ShizukuShellExecutor.requestPermission()
    }

    fun updateHeartbeatIp(ip: String) {
        GatekeeperRepository.updateConfig { it.copy(heartbeatIp = ip) }
    }

    fun updateGracePeriodMs(ms: Long) {
        GatekeeperRepository.updateConfig { it.copy(gracePeriodMs = ms) }
    }

    fun updateHeartbeatIntervalMs(ms: Long) {
        GatekeeperRepository.updateConfig { it.copy(heartbeatIntervalMs = ms) }
    }

    fun updateBlockIpv6(enabled: Boolean) {
        GatekeeperRepository.updateConfig { it.copy(blockIpv6 = enabled) }
    }

    fun updateCustomInterfaces(interfaces: String) {
        GatekeeperRepository.updateConfig { it.copy(customInterfaces = interfaces) }
    }

    fun clearLogs() {
        GatekeeperRepository.clearLogs()
    }
}
