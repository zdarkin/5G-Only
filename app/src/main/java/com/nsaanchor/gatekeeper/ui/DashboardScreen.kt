package com.nsaanchor.gatekeeper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nsaanchor.gatekeeper.firewall.IptablesFirewallManager
import com.nsaanchor.gatekeeper.model.GatekeeperUiState
import com.nsaanchor.gatekeeper.model.LogEntry
import com.nsaanchor.gatekeeper.model.LogLevel
import com.nsaanchor.gatekeeper.model.NetworkAnchorState
import com.nsaanchor.gatekeeper.model.ShizukuStatus
import com.nsaanchor.gatekeeper.model.SimSlotInfo
import com.nsaanchor.gatekeeper.ui.theme.AmberWarning
import com.nsaanchor.gatekeeper.ui.theme.AmberWarningDim
import com.nsaanchor.gatekeeper.ui.theme.CrimsonRed
import com.nsaanchor.gatekeeper.ui.theme.CrimsonRedDim
import com.nsaanchor.gatekeeper.ui.theme.CyanAccent
import com.nsaanchor.gatekeeper.ui.theme.CyanAccentDim
import com.nsaanchor.gatekeeper.ui.theme.DeepSlateBackground
import com.nsaanchor.gatekeeper.ui.theme.EmeraldGreen
import com.nsaanchor.gatekeeper.ui.theme.EmeraldGreenDim
import com.nsaanchor.gatekeeper.ui.theme.SlateBorder
import com.nsaanchor.gatekeeper.ui.theme.SlateCardElevated
import com.nsaanchor.gatekeeper.ui.theme.SlateCardSurface
import com.nsaanchor.gatekeeper.ui.theme.TerminalBg
import com.nsaanchor.gatekeeper.ui.theme.TextMuted
import com.nsaanchor.gatekeeper.ui.theme.TextPrimary
import com.nsaanchor.gatekeeper.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun DashboardScreen(
    viewModel: GatekeeperViewModel,
    hasTelephonyPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    DashboardContent(
        uiState = uiState,
        hasTelephonyPermissions = hasTelephonyPermissions,
        onToggleService = { viewModel.toggleService(context) },
        onForceFlushFirewall = { viewModel.forceFlushFirewall(context) },
        onRefreshShizuku = { viewModel.refreshShizuku() },
        onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
        onRequestAndroidPermissions = onRequestPermissions,
        onSelectSimSlot = { slotIndex -> viewModel.selectSimSlot(context, slotIndex) },
        onUpdateRequireStrictNrState = { viewModel.updateRequireStrictNrState(it) },
        onUpdateHeartbeatIp = { viewModel.updateHeartbeatIp(it) },
        onUpdateGracePeriodMs = { viewModel.updateGracePeriodMs(it) },
        onUpdateHeartbeatIntervalMs = { viewModel.updateHeartbeatIntervalMs(it) },
        onUpdateBlockIpv6 = { viewModel.updateBlockIpv6(it) },
        onUpdateCustomInterfaces = { viewModel.updateCustomInterfaces(it) },
        onClearLogs = { viewModel.clearLogs() }
    )
}

@Composable
fun DashboardContent(
    uiState: GatekeeperUiState,
    hasTelephonyPermissions: Boolean,
    onToggleService: () -> Unit,
    onForceFlushFirewall: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRequestAndroidPermissions: () -> Unit,
    onSelectSimSlot: (Int) -> Unit,
    onUpdateRequireStrictNrState: (Boolean) -> Unit,
    onUpdateHeartbeatIp: (String) -> Unit,
    onUpdateGracePeriodMs: (Long) -> Unit,
    onUpdateHeartbeatIntervalMs: (Long) -> Unit,
    onUpdateBlockIpv6: (Boolean) -> Unit,
    onUpdateCustomInterfaces: (String) -> Unit,
    onClearLogs: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isShizukuReady =
        uiState.shizukuStatus == ShizukuStatus.READY_SHELL ||
            uiState.shizukuStatus == ShizukuStatus.READY_ROOT
    val isSetupComplete = isShizukuReady && hasTelephonyPermissions

    Scaffold(
        containerColor = DeepSlateBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Compact Header Row
            CompactHeaderRow(
                isServiceRunning = uiState.isServiceRunning,
                isSetupComplete = isSetupComplete,
                shizukuStatus = uiState.shizukuStatus
            )

            // 2. Auto-Hiding Setup Banner (only shown when setup is incomplete)
            if (!isSetupComplete) {
                SetupBannerCard(
                    shizukuStatus = uiState.shizukuStatus,
                    shizukuUid = uiState.shizukuUid,
                    hasTelephonyPermissions = hasTelephonyPermissions,
                    onRefreshShizuku = onRefreshShizuku,
                    onRequestShizukuPermission = onRequestShizukuPermission,
                    onRequestAndroidPermissions = onRequestAndroidPermissions
                )
            }

            // 3. Clean SIM Selector Bar (Target SIM for Hotspot)
            SimSelectorBar(
                availableSims = uiState.availableSims,
                selectedSimSlot = uiState.config.selectedSimSlot,
                onSelectSimSlot = onSelectSimSlot
            )

            // 4. Unified Status & Control Hero Card
            UnifiedStatusControlCard(
                uiState = uiState,
                onToggleService = onToggleService,
                onForceFlushFirewall = onForceFlushFirewall
            )

            // 5. Settings Card (Collapsed by default!)
            SettingsCard(
                uiState = uiState,
                onUpdateRequireStrictNrState = onUpdateRequireStrictNrState,
                onUpdateHeartbeatIp = onUpdateHeartbeatIp,
                onUpdateGracePeriodMs = onUpdateGracePeriodMs,
                onUpdateHeartbeatIntervalMs = onUpdateHeartbeatIntervalMs,
                onUpdateBlockIpv6 = onUpdateBlockIpv6,
                onUpdateCustomInterfaces = onUpdateCustomInterfaces
            )

            // 6. Live Log Card
            TerminalLogCard(
                logs = uiState.logs,
                onClearLogs = onClearLogs
            )
        }
    }
}

@Composable
private fun CompactHeaderRow(
    isServiceRunning: Boolean,
    isSetupComplete: Boolean,
    shizukuStatus: ShizukuStatus
) {
    val pillColor = when {
        isSetupComplete -> EmeraldGreen
        shizukuStatus == ShizukuStatus.NOT_RUNNING || shizukuStatus == ShizukuStatus.ERROR -> CrimsonRed
        else -> AmberWarning
    }
    val pillBg = when {
        isSetupComplete -> EmeraldGreenDim.copy(alpha = 0.55f)
        shizukuStatus == ShizukuStatus.NOT_RUNNING || shizukuStatus == ShizukuStatus.ERROR -> CrimsonRedDim.copy(alpha = 0.55f)
        else -> AmberWarningDim.copy(alpha = 0.55f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isServiceRunning) EmeraldGreenDim else SlateCardElevated)
                    .border(
                        width = 1.dp,
                        color = if (isServiceRunning) EmeraldGreen else SlateBorder,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CellTower,
                    contentDescription = "5G NSA Gatekeeper",
                    tint = if (isServiceRunning) EmeraldGreen else CyanAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "5G NSA Gatekeeper",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = pillBg,
            border = BorderStroke(1.dp, pillColor.copy(alpha = 0.8f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isSetupComplete) Icons.Default.VerifiedUser else Icons.Default.Warning,
                    contentDescription = null,
                    tint = pillColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isSetupComplete) "Shizuku Ready" else "Setup Needed",
                    style = MaterialTheme.typography.labelMedium,
                    color = pillColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SetupBannerCard(
    shizukuStatus: ShizukuStatus,
    shizukuUid: Int,
    hasTelephonyPermissions: Boolean,
    onRefreshShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRequestAndroidPermissions: () -> Unit
) {
    val isShizukuReady =
        shizukuStatus == ShizukuStatus.READY_SHELL || shizukuStatus == ShizukuStatus.READY_ROOT
    val shizukuColor = when (shizukuStatus) {
        ShizukuStatus.READY_ROOT, ShizukuStatus.READY_SHELL -> EmeraldGreen
        ShizukuStatus.PERMISSION_REQUIRED -> AmberWarning
        ShizukuStatus.NOT_RUNNING, ShizukuStatus.ERROR -> CrimsonRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Setup Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                IconButton(
                    onClick = onRefreshShizuku,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Shizuku",
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Shizuku status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isShizukuReady) Icons.Default.VerifiedUser else Icons.Default.Warning,
                        contentDescription = null,
                        tint = shizukuColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (shizukuUid >= 0) {
                            "Shizuku: ${shizukuStatus.label} (UID $shizukuUid)"
                        } else {
                            "Shizuku: ${shizukuStatus.label}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = shizukuColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }

                if (!isShizukuReady) {
                    OutlinedButton(
                        onClick = if (shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED) {
                            onRequestShizukuPermission
                        } else {
                            onRefreshShizuku
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        border = BorderStroke(1.dp, shizukuColor)
                    ) {
                        Text(
                            text = if (shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED) "Grant" else "Retry",
                            style = MaterialTheme.typography.labelMedium,
                            color = shizukuColor
                        )
                    }
                }
            }

            // Telephony / Fine Location permissions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (hasTelephonyPermissions) Icons.Default.GppGood else Icons.Default.GppBad,
                        contentDescription = null,
                        tint = if (hasTelephonyPermissions) EmeraldGreen else AmberWarning,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasTelephonyPermissions) {
                            "Phone & Location: Granted"
                        } else {
                            "Phone & Location: Needed for 5G NSA"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasTelephonyPermissions) TextPrimary else AmberWarning,
                        fontSize = 12.sp
                    )
                }

                if (!hasTelephonyPermissions) {
                    OutlinedButton(
                        onClick = onRequestAndroidPermissions,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        border = BorderStroke(1.dp, AmberWarning)
                    ) {
                        Text(
                            text = "Grant",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberWarning
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimSelectorBar(
    availableSims: List<SimSlotInfo>,
    selectedSimSlot: Int,
    onSelectSimSlot: (Int) -> Unit
) {
    val sim1 = availableSims.find { it.slotIndex == 0 }
        ?: SimSlotInfo(slotIndex = 0, subscriptionId = -1, displayName = "SIM 1", carrierName = "Slot 1")
    val sim2 = availableSims.find { it.slotIndex == 1 }
        ?: SimSlotInfo(slotIndex = 1, subscriptionId = -1, displayName = "SIM 2", carrierName = "Slot 2")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SimCard,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Target SIM for Hotspot",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(sim1, sim2).forEach { sim ->
                    val isSelected = selectedSimSlot == sim.slotIndex
                    val pillBorderColor = if (isSelected) CyanAccent else SlateBorder
                    val pillBgColor = if (isSelected) CyanAccentDim.copy(alpha = 0.55f) else SlateCardElevated

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectSimSlot(sim.slotIndex) },
                        shape = RoundedCornerShape(10.dp),
                        color = pillBgColor,
                        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, pillBorderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SIM ${sim.slotIndex + 1} • ${sim.carrierName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (sim.isActiveDataSim) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = EmeraldGreenDim,
                                    border = BorderStroke(0.8.dp, EmeraldGreen)
                                ) {
                                    Text(
                                        text = "DATA",
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EmeraldGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedStatusControlCard(
    uiState: GatekeeperUiState,
    onToggleService: () -> Unit,
    onForceFlushFirewall: () -> Unit
) {
    val accentColor by animateColorAsState(
        targetValue = when (uiState.anchorState) {
            NetworkAnchorState.CONNECTED_5G_NSA -> EmeraldGreen
            NetworkAnchorState.LTE_LOCKDOWN_SEARCHING -> CrimsonRed
            NetworkAnchorState.HANDOVER_GRACE_PERIOD,
            NetworkAnchorState.STARTING_FAIL_CLOSED -> AmberWarning
            NetworkAnchorState.ERROR -> CrimsonRed
            NetworkAnchorState.STOPPED -> TextMuted
        },
        animationSpec = tween(300),
        label = "HeroAccentColor"
    )

    val containerBg by animateColorAsState(
        targetValue = when (uiState.anchorState) {
            NetworkAnchorState.CONNECTED_5G_NSA -> EmeraldGreenDim.copy(alpha = 0.24f)
            NetworkAnchorState.LTE_LOCKDOWN_SEARCHING -> CrimsonRedDim.copy(alpha = 0.26f)
            NetworkAnchorState.HANDOVER_GRACE_PERIOD,
            NetworkAnchorState.STARTING_FAIL_CLOSED -> AmberWarningDim.copy(alpha = 0.24f)
            NetworkAnchorState.ERROR -> CrimsonRedDim.copy(alpha = 0.22f)
            NetworkAnchorState.STOPPED -> SlateCardSurface
        },
        animationSpec = tween(300),
        label = "HeroContainerBg"
    )

    val stateTitle = when (uiState.anchorState) {
        NetworkAnchorState.CONNECTED_5G_NSA -> "5G NSA Connected"
        NetworkAnchorState.LTE_LOCKDOWN_SEARCHING -> "LTE — Blocking & Searching"
        NetworkAnchorState.HANDOVER_GRACE_PERIOD -> {
            if (uiState.graceRemainingMs > 0L) {
                String.format(Locale.US, "Grace Period (%.1fs)", uiState.graceRemainingMs / 1000f)
            } else {
                "Grace Period"
            }
        }
        NetworkAnchorState.STARTING_FAIL_CLOSED -> "Starting (Fail-Closed)"
        NetworkAnchorState.ERROR -> "Error (Check Setup)"
        NetworkAnchorState.STOPPED -> "Stopped"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Row: Status dot + State title + Firewall pill ("LOCKED" vs "OPEN")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
                        if (uiState.isServiceRunning) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.35f))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stateTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (uiState.anchorState == NetworkAnchorState.STOPPED) TextSecondary else accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Firewall Pill ("LOCKED" vs "OPEN")
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (uiState.isFirewallActive) CrimsonRedDim else EmeraldGreenDim.copy(alpha = 0.6f),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isFirewallActive) CrimsonRed else EmeraldGreen
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (uiState.isFirewallActive) Icons.Default.Lock else Icons.Default.GppGood,
                            contentDescription = null,
                            tint = if (uiState.isFirewallActive) CrimsonRed else EmeraldGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (uiState.isFirewallActive) "LOCKED" else "OPEN",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (uiState.isFirewallActive) Color(0xFFFECACA) else Color(0xFFA7F3D0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Middle Row: Clean 3-column metric boxes
            val nrStateColor = when (uiState.nrState) {
                "CONNECTED" -> EmeraldGreen
                "NOT_RESTRICTED", "RESTRICTED" -> AmberWarning
                else -> if (uiState.isServiceRunning) CrimsonRed else TextMuted
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricBox(
                    modifier = Modifier.weight(1f),
                    label = "NR State (NSA)",
                    value = uiState.nrState,
                    valueColor = nrStateColor
                )
                MetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Network",
                    value = "${uiState.rawNetworkType} (${uiState.overrideNetworkType})",
                    valueColor = CyanAccent
                )
                MetricBox(
                    modifier = Modifier.weight(0.75f),
                    label = "Bait Pings",
                    value = "${uiState.heartbeatCount}",
                    valueColor = TextPrimary
                )
            }

            // Bottom Row: Primary Start / Stop button + compact Flush button beside it
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onToggleService,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isServiceRunning) CrimsonRed else EmeraldGreen,
                        contentColor = if (uiState.isServiceRunning) TextPrimary else Color(0xFF022C22)
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (uiState.isServiceRunning) "Stop & Unblock" else "Start Gatekeeper",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onForceFlushFirewall,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isFirewallActive) AmberWarning else SlateBorder
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (uiState.isFirewallActive) AmberWarning else TextSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CleaningServices,
                        contentDescription = "Flush Firewall",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Flush",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = SlateCardElevated.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, SlateBorder.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsCard(
    uiState: GatekeeperUiState,
    onUpdateRequireStrictNrState: (Boolean) -> Unit,
    onUpdateHeartbeatIp: (String) -> Unit,
    onUpdateGracePeriodMs: (Long) -> Unit,
    onUpdateHeartbeatIntervalMs: (Long) -> Unit,
    onUpdateBlockIpv6: (Boolean) -> Unit,
    onUpdateCustomInterfaces: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customIpText by remember(uiState.config.heartbeatIp) {
        mutableStateOf(uiState.config.heartbeatIp)
    }

    val graceSec = String.format(Locale.US, "%.1f", uiState.config.gracePeriodMs / 1000f)
    val baitSec = String.format(Locale.US, "%.1f", uiState.config.heartbeatIntervalMs / 1000f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings • ${uiState.config.heartbeatIp} • ${graceSec}s grace • ${baitSec}s bait",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle Settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = SlateBorder.copy(alpha = 0.6f))

                    // 0. Strict NR State Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Require NR State: CONNECTED",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                            Text(
                                text = if (uiState.config.requireStrictNrState) {
                                    "Strict: data allowed only when NR State is CONNECTED (*#*#4636#*#*)"
                                } else {
                                    "Standard: data allowed whenever 5G NSA (NR_NSA) is active"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = uiState.config.requireStrictNrState,
                            onCheckedChange = onUpdateRequireStrictNrState,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = EmeraldGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SlateCardElevated
                            )
                        )
                    }

                    // 1. Heartbeat IP (1.1.1.1 / 8.8.8.8 / Custom)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "UDP Heartbeat Bait IP (Port 53)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = uiState.config.heartbeatIp == "1.1.1.1",
                                onClick = {
                                    customIpText = "1.1.1.1"
                                    onUpdateHeartbeatIp("1.1.1.1")
                                },
                                label = {
                                    Text(
                                        text = "1.1.1.1 (Cloudflare)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanAccentDim,
                                    selectedLabelColor = CyanAccent
                                )
                            )
                            FilterChip(
                                selected = uiState.config.heartbeatIp == "8.8.8.8",
                                onClick = {
                                    customIpText = "8.8.8.8"
                                    onUpdateHeartbeatIp("8.8.8.8")
                                },
                                label = {
                                    Text(
                                        text = "8.8.8.8 (Google)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanAccentDim,
                                    selectedLabelColor = CyanAccent
                                )
                            )
                        }

                        val isCustomIpValid = IptablesFirewallManager.isValidIpv4(customIpText.trim())
                        OutlinedTextField(
                            value = customIpText,
                            onValueChange = { newIp ->
                                customIpText = newIp
                                val trimmed = newIp.trim()
                                if (IptablesFirewallManager.isValidIpv4(trimmed)) {
                                    onUpdateHeartbeatIp(trimmed)
                                }
                            },
                            isError = customIpText.trim().isNotEmpty() && !isCustomIpValid,
                            label = {
                                Text(
                                    if (customIpText.trim().isNotEmpty() && !isCustomIpValid) {
                                        "Custom IPv4 (Invalid format)"
                                    } else {
                                        "Custom Heartbeat IPv4"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = SlateBorder,
                                focusedLabelColor = CyanAccent,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                    }

                    // 2. Grace Period Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Handover Grace Period",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${graceSec}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberWarning,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = uiState.config.gracePeriodMs.toFloat(),
                            onValueChange = { raw ->
                                val stepped = ((raw / 100f).roundToLong() * 100L).coerceIn(500L, 5000L)
                                onUpdateGracePeriodMs(stepped)
                            },
                            valueRange = 500f..5000f,
                            steps = 44,
                            colors = SliderDefaults.colors(
                                thumbColor = AmberWarning,
                                activeTrackColor = AmberWarning,
                                inactiveTrackColor = SlateCardElevated
                            )
                        )
                    }

                    // 3. Bait Interval Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Bait Ping Interval",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${baitSec}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = uiState.config.heartbeatIntervalMs.toFloat(),
                            onValueChange = { raw ->
                                val stepped = ((raw / 500f).roundToLong() * 500L).coerceIn(2000L, 5000L)
                                onUpdateHeartbeatIntervalMs(stepped)
                            },
                            valueRange = 2000f..5000f,
                            steps = 5,
                            colors = SliderDefaults.colors(
                                thumbColor = CyanAccent,
                                activeTrackColor = CyanAccent,
                                inactiveTrackColor = SlateCardElevated
                            )
                        )
                    }

                    // 4. IPv6 Lockdown Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "IPv6 Leak Lockdown (ip6tables)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Block IPv6 cellular traffic during LTE lockdown",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = uiState.config.blockIpv6,
                            onCheckedChange = onUpdateBlockIpv6,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = EmeraldGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SlateCardElevated
                            )
                        )
                    }

                    // 5. Optional Extra Interfaces
                    OutlinedTextField(
                        value = uiState.config.customInterfaces,
                        onValueChange = onUpdateCustomInterfaces,
                        label = {
                            Text(
                                "Extra Interfaces (optional, e.g. rmnet_data+)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.labelMedium.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = SlateBorder,
                            focusedLabelColor = CyanAccent,
                            unfocusedLabelColor = TextSecondary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalLogCard(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Live Log (${logs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Logs",
                        tint = TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TerminalBg)
                    .border(1.dp, SlateBorder.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No events logged yet.",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs, key = { it.id }) { entry ->
                            TerminalLogRow(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLogRow(entry: LogEntry) {
    val badgeColor = when (entry.level) {
        LogLevel.BLOCK -> CrimsonRed
        LogLevel.UNBLOCK -> EmeraldGreen
        LogLevel.HEARTBEAT -> CyanAccent
        LogLevel.WARN -> AmberWarning
        LogLevel.ERROR -> CrimsonRed
        LogLevel.INFO -> TextSecondary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.timestamp,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(5.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.16f),
            border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.6f))
        ) {
            Text(
                text = entry.level.tag,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            overflow = TextOverflow.Visible
        )
    }
}
