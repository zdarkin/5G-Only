package com.nsaanchor.gatekeeper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.firewall.ShizukuShellExecutor
import com.nsaanchor.gatekeeper.model.LogLevel
import com.nsaanchor.gatekeeper.model.ShizukuStatus
import com.nsaanchor.gatekeeper.ui.theme.NSA5GAnchorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GatekeeperViewModel by viewModels()

    private var hasTelephonyPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasTelephonyPermissions = checkRequiredTelephonyPermissions()
        viewModel.refreshSims(this)
        val allGranted = results.values.all { it }
        if (allGranted) {
            GatekeeperRepository.log(
                LogLevel.INFO,
                "All runtime permissions granted (Phone State, Fine Location, Notifications)"
            )
        } else {
            GatekeeperRepository.log(
                LogLevel.WARN,
                "Some runtime permissions were denied; 5G NSA OverrideNetworkType detection requires READ_PHONE_STATE & ACCESS_FINE_LOCATION"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Shizuku binder listeners and check permission on launch
        ShizukuShellExecutor.init()
        if (ShizukuShellExecutor.refreshStatus() == ShizukuStatus.PERMISSION_REQUIRED) {
            ShizukuShellExecutor.requestPermission()
        }

        hasTelephonyPermissions = checkRequiredTelephonyPermissions()
        viewModel.refreshSims(this)
        if (!hasTelephonyPermissions) {
            requestRequiredRuntimePermissions()
        }

        setContent {
            NSA5GAnchorTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    hasTelephonyPermissions = hasTelephonyPermissions,
                    onRequestPermissions = { requestRequiredRuntimePermissions() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasTelephonyPermissions = checkRequiredTelephonyPermissions()
        ShizukuShellExecutor.refreshStatus()
        viewModel.refreshSims(this)
    }

    private fun checkRequiredTelephonyPermissions(): Boolean {
        val readPhoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return readPhoneGranted && fineLocationGranted
    }

    private fun requestRequiredRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
