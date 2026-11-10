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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.material.icons.filled.Shield
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
    onUpdateHeartbeatIp: (String) -> Unit,
    onUpdateGracePeriodMs: (Long) -> Unit,
    onUpdateHeartbeatIntervalMs: (Long) -> Unit,
    onUpdateBlockIpv6: (Boolean) -> Unit,
    onUpdateCustomInterfaces: (String) -> Unit,
    onClearLogs: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = DeepSlateBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopHeaderBar(isServiceRunning = uiState.isServiceRunning)

            LiveStatusHeroCard(uiState = uiState)

            ServiceControlCard(
                isServiceRunning = uiState.isServiceRunning,
                isFirewallActive = uiState.isFirewallActive,
                onToggleService = onToggleService,
                onForceFlushFirewall = onForceFlushFirewall
            )

            PermissionsAndShizukuCard(
                shizukuStatus = uiState.shizukuStatus,
                shizukuUid = uiState.shizukuUid,
                hasTelephonyPermissions = hasTelephonyPermissions,
                detectedInterfaces = uiState.detectedInterfaces,
                onRefreshShizuku = onRefreshShizuku,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onRequestAndroidPermissions = onRequestAndroidPermissions
            )

            ConfigurationCard(
                uiState = uiState,
                onUpdateHeartbeatIp = onUpdateHeartbeatIp,
                onUpdateGracePeriodMs = onUpdateGracePeriodMs,
                onUpdateHeartbeatIntervalMs = onUpdateHeartbeatIntervalMs,
                onUpdateBlockIpv6 = onUpdateBlockIpv6,
                onUpdateCustomInterfaces = onUpdateCustomInterfaces
            )

            TerminalLogCard(
                logs = uiState.logs,
                onClearLogs = onClearLogs
            )
        }
    }
}

@Composable
private fun TopHeaderBar(isServiceRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background( if (isServiceRunning) EmeraldGreenDim else SlateCardElevated )
                    .border(
                        width = 1.dp,
                        color = if (isServiceRunning) EmeraldGreen else SlateBorder,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CellTower,
                    contentDescription = "5G Anchor",
                    tint = if (isServiceRunning) EmeraldGreen else CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "5G NSA ANCHOR & GATEKEEPER",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = "EN-DC Heartbeat Bait + Shizuku iptables Firewall",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveStatusHeroCard(uiState: GatekeeperUiState) {
    val accentColor by animateColorAsState(
        targetValue = when (uiState.anchorState) {
            NetworkAnchorState.CONNECTED_5G_NSA -> EmeraldGreen
            NetworkAnchorState.LTE_LOCKDOWN_SEARCHING -> CrimsonRed
            NetworkAnchorState.HANDOVER_GRACE_PERIOD,
            NetworkAnchorState.STARTING_FAIL_CLOSED -> AmberWarning
            NetworkAnchorState.ERROR -> CrimsonRed
            NetworkAnchorState.STOPPED -> TextMuted
        },
        animationSpec = tween(350),
        label = "HeroAccentColor"
    )

    val containerBg by animateColorAsState(
        targetValue = when (uiState.anchorState) {
            NetworkAnchorState.CONNECTED_5G_NSA -> EmeraldGreenDim.copy(alpha = 0.28f)
            NetworkAnchorState.LTE_LOCKDOWN_SEARCHING -> CrimsonRedDim.copy(alpha = 0.30f)
            NetworkAnchorState.HANDOVER_GRACE_PERIOD,
            NetworkAnchorState.STARTING_FAIL_CLOSED -> AmberWarningDim.copy(alpha = 0.28f)
            NetworkAnchorState.ERROR -> CrimsonRedDim.copy(alpha = 0.25f)
            NetworkAnchorState.STOPPED -> SlateCardSurface
        },
        animationSpec = tween(350),
        label = "HeroContainerBg"
    )

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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) {
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
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = uiState.anchorState.displayTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                }

                // Firewall Lockdown Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
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
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (uiState.isFirewallActive) "NSA_LOCKDOWN ACTIVE" else "UNBLOCKED",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (uiState.isFirewallActive) Color(0xFFFECACA) else Color(0xFFA7F3D0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Prominent Required State Headline
            Text(
                text = uiState.anchorState.statusSummary,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontSize = 19.sp
            )

            if (uiState.anchorState == NetworkAnchorState.HANDOVER_GRACE_PERIOD && uiState.graceRemainingMs > 0) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Grace countdown: %.1fs remaining before iptables lockdown",
                        uiState.graceRemainingMs / 1000f
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberWarning,
                    fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider(color = SlateBorder.copy(alpha = 0.6f))

            // Telemetry Badges Row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryBadge(
                    label = "RAW NET",
                    value = uiState.rawNetworkType,
                    accent = CyanAccent
                )
                TelemetryBadge(
                    label = "OVERRIDE",
                    value = uiState.overrideNetworkType,
                    accent = if (uiState.overrideNetworkType.startsWith("NR_")) EmeraldGreen else AmberWarning
                )
                TelemetryBadge(
                    label = "EN-DC BAIT",
                    value = "Sent: ${uiState.heartbeatCount} | Last: ${uiState.lastHeartbeatStatus}",
                    accent = CyanAccent
                )
            }
        }
    }
}

@Composable
private fun TelemetryBadge(
    label: String,
    value: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SlateCardElevated.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label: ",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ServiceControlCard(
    isServiceRunning: Boolean,
    isFirewallActive: Boolean,
    onToggleService: () -> Unit,
    onForceFlushFirewall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onToggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) CrimsonRed else EmeraldGreen,
                    contentColor = if (isServiceRunning) TextPrimary else Color(0xFF022C22)
                )
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceRunning) {
                        "Stop Service & Unblock All"
                    } else {
                        "Start 5G Anchor & Gatekeeper"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onForceFlushFirewall,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (isFirewallActive) AmberWarning else SlateBorder
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isFirewallActive) AmberWarning else TextSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Emergency Flush iptables / ip6tables (NSA_LOCKDOWN)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PermissionsAndShizukuCard(
    shizukuStatus: ShizukuStatus,
    shizukuUid: Int,
    hasTelephonyPermissions: Boolean,
    detectedInterfaces: List<String>,
    onRefreshShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRequestAndroidPermissions: () -> Unit
) {
    val isShizukuReady = shizukuStatus == ShizukuStatus.READY_SHELL || shizukuStatus == ShizukuStatus.READY_ROOT
    val shizukuColor = when (shizukuStatus) {
        ShizukuStatus.READY_ROOT, ShizukuStatus.READY_SHELL -> EmeraldGreen
        ShizukuStatus.PERMISSION_REQUIRED -> AmberWarning
        ShizukuStatus.NOT_RUNNING, ShizukuStatus.ERROR -> CrimsonRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SHIZUKU & PERMISSIONS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                }

                IconButton(
                    onClick = onRefreshShizuku,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Shizuku",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
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
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Shizuku: ${shizukuStatus.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = shizukuColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (shizukuUid >= 0) {
                            Text(
                                text = "Privileged Binder UID: $shizukuUid",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (!isShizukuReady) {
                    OutlinedButton(
                        onClick = if (shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED) {
                            onRequestShizukuPermission
                        } else {
                            onRefreshShizuku
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        border = BorderStroke(1.dp, shizukuColor)
                    ) {
                        Text(
                            text = if (shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED) "Grant Shizuku" else "Retry",
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
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasTelephonyPermissions) {
                            "Phone State & Fine Location: Granted"
                        } else {
                            "Phone State & Fine Location: Required for 5G NSA"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasTelephonyPermissions) TextPrimary else AmberWarning
                    )
                }

                if (!hasTelephonyPermissions) {
                    OutlinedButton(
                        onClick = onRequestAndroidPermissions,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        border = BorderStroke(1.dp, AmberWarning)
                    ) {
                        Text(
                            text = "Grant Permissions",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberWarning
                        )
                    }
                }
            }

            // Detected Cellular Interfaces
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Target Cellular Interfaces: ${detectedInterfaces.joinToString(", ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ConfigurationCard(
    uiState: GatekeeperUiState,
    onUpdateHeartbeatIp: (String) -> Unit,
    onUpdateGracePeriodMs: (Long) -> Unit,
    onUpdateHeartbeatIntervalMs: (Long) -> Unit,
    onUpdateBlockIpv6: (Boolean) -> Unit,
    onUpdateCustomInterfaces: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var customIpText by remember(uiState.config.heartbeatIp) {
        mutableStateOf(uiState.config.heartbeatIp)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "ANCHOR & FIREWALL PARAMETERS",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Target: ${uiState.config.heartbeatIp}:53 | Grace: ${
                                String.format(Locale.US, "%.1fs", uiState.config.gracePeriodMs / 1000f)
                            } | Bait: ${
                                String.format(Locale.US, "%.1fs", uiState.config.heartbeatIntervalMs / 1000f)
                            }",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle Configuration",
                    tint = TextSecondary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = SlateBorder.copy(alpha = 0.6f))

                    // 1. Allowed Heartbeat IP Selector / Input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Allowed UDP Heartbeat IP (Whitelisted on Port 53 during LTE Lockdown)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
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
                                        "Heartbeat Target IPv4 (Enter valid IPv4, e.g. 1.1.1.1)"
                                    } else {
                                        "Heartbeat Target IPv4"
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

                    // 2. Handover Grace Period Slider (0.5s to 5.0s, default 2.5s)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Handover Grace Period",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f sec", uiState.config.gracePeriodMs / 1000f),
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberWarning,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Delays iptables DROP on brief inter-tower 5G -> LTE handovers",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
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

                    // 3. Heartbeat ("Bait") Interval Slider (2.0s to 5.0s, default 3.0s)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "EN-DC Heartbeat (\"Bait\") Interval",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f sec", uiState.config.heartbeatIntervalMs / 1000f),
                                style = MaterialTheme.typography.labelMedium,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Sends 1-byte UDP packet to port 53 while locked down to trigger 5G RRC upgrade",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
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

                    // 4. IPv6 Leak Lockdown Switch
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
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Drop all IPv6 OUTPUT & FORWARD on cellular interfaces during LTE lockdown",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
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

                    // 5. Optional Custom Interface Override
                    OutlinedTextField(
                        value = uiState.config.customInterfaces,
                        onValueChange = onUpdateCustomInterfaces,
                        label = {
                            Text(
                                "Extra Interfaces (optional, comma-separated, e.g. rmnet_data+)",
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCardSurface),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LIVE GATEKEEPER EVENT LOG",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${logs.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }

                OutlinedButton(
                    onClick = onClearLogs,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Logs",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TerminalBg)
                    .border(1.dp, SlateBorder.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No events logged yet. Start 5G Anchor & Gatekeeper to monitor EN-DC transitions.",
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
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.16f),
            border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.6f))
        ) {
            Text(
                text = entry.level.tag,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Visible
        )
    }
}
