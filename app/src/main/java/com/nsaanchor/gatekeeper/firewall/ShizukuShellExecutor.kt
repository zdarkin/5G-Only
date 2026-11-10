package com.nsaanchor.gatekeeper.firewall

import android.content.pm.PackageManager
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import com.nsaanchor.gatekeeper.model.LogLevel
import com.nsaanchor.gatekeeper.model.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

object ShizukuShellExecutor {

    const val DEFAULT_PERMISSION_REQUEST_CODE = 1001

    @Volatile
    private var initialized = false

    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val status = refreshStatus()
        GatekeeperRepository.log(LogLevel.INFO, "Shizuku binder connected: ${status.label}")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        GatekeeperRepository.updateState {
            it.copy(
                shizukuStatus = ShizukuStatus.NOT_RUNNING,
                shizukuUid = -1
            )
        }
        GatekeeperRepository.log(LogLevel.WARN, "Shizuku binder died (service stopped)")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val status = refreshStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                GatekeeperRepository.log(
                    LogLevel.INFO,
                    "Shizuku permission granted (req=$requestCode): ${status.label}"
                )
            } else {
                GatekeeperRepository.log(
                    LogLevel.WARN,
                    "Shizuku permission denied (req=$requestCode)"
                )
            }
        }

    fun init() {
        if (initialized) {
            refreshStatus()
            return
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            initialized = true
        } catch (e: Throwable) {
            GatekeeperRepository.log(
                LogLevel.ERROR,
                "Failed to register Shizuku listeners: ${e.message}"
            )
        }
        refreshStatus()
    }

    fun dispose() {
        if (!initialized) return
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
            // Ignore cleanup errors
        } finally {
            initialized = false
        }
    }

    fun refreshStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                GatekeeperRepository.updateState {
                    it.copy(
                        shizukuStatus = ShizukuStatus.NOT_RUNNING,
                        shizukuUid = -1
                    )
                }
                return ShizukuStatus.NOT_RUNNING
            }

            if (Shizuku.isPreV11()) {
                GatekeeperRepository.updateState {
                    it.copy(
                        shizukuStatus = ShizukuStatus.ERROR,
                        shizukuUid = -1
                    )
                }
                return ShizukuStatus.ERROR
            }

            val perm = Shizuku.checkSelfPermission()
            if (perm != PackageManager.PERMISSION_GRANTED) {
                GatekeeperRepository.updateState {
                    it.copy(
                        shizukuStatus = ShizukuStatus.PERMISSION_REQUIRED,
                        shizukuUid = -1
                    )
                }
                return ShizukuStatus.PERMISSION_REQUIRED
            }

            val uid = Shizuku.getUid()
            val status = when {
                uid == 0 -> ShizukuStatus.READY_ROOT
                uid == 2000 || uid > 0 -> ShizukuStatus.READY_SHELL
                else -> ShizukuStatus.ERROR
            }
            GatekeeperRepository.updateState {
                it.copy(
                    shizukuStatus = status,
                    shizukuUid = uid
                )
            }
            status
        } catch (e: Throwable) {
            GatekeeperRepository.updateState {
                it.copy(
                    shizukuStatus = ShizukuStatus.ERROR,
                    shizukuUid = -1
                )
            }
            ShizukuStatus.ERROR
        }
    }

    fun isReady(): Boolean {
        val status = refreshStatus()
        return status == ShizukuStatus.READY_ROOT || status == ShizukuStatus.READY_SHELL
    }

    fun requestPermission(requestCode: Int = DEFAULT_PERMISSION_REQUEST_CODE) {
        try {
            if (!Shizuku.pingBinder()) {
                GatekeeperRepository.log(
                    LogLevel.WARN,
                    "Cannot request permission: Shizuku is not running."
                )
                refreshStatus()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshStatus()
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            GatekeeperRepository.log(
                LogLevel.ERROR,
                "Failed to request Shizuku permission: ${e.message}"
            )
        }
    }

    suspend fun execute(command: String, timeoutMs: Long = 6000L): ShellResult =
        withContext(Dispatchers.IO) {
            val status = refreshStatus()
            if (status != ShizukuStatus.READY_ROOT && status != ShizukuStatus.READY_SHELL) {
                return@withContext ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Shizuku not ready (${status.label})"
                )
            }

            val primaryResult = runShellProcess(
                cmdArray = arrayOf("sh", "-c", command),
                timeoutMs = timeoutMs
            )
            if (primaryResult.isSuccess || status != ShizukuStatus.READY_SHELL) {
                return@withContext primaryResult
            }

            // If Shizuku is running as ADB shell (UID 2000) and iptables/ip6tables failed due to
            // SELinux or UID restrictions, attempt a fallback via 'su -c' in case the device has root.
            val suFallbackResult = runShellProcess(
                cmdArray = arrayOf("su", "-c", command),
                timeoutMs = timeoutMs
            )
            if (suFallbackResult.isSuccess) {
                return@withContext suFallbackResult
            }

            primaryResult
        }

    private suspend fun runShellProcess(
        cmdArray: Array<String>,
        timeoutMs: Long
    ): ShellResult {
        var process: Process? = null
        return try {
            val createdProcess = newProcessMethod.invoke(
                null,
                cmdArray,
                null,
                null
            ) as Process
            process = createdProcess

            // Close stdin immediately since commands are non-interactive
            try {
                createdProcess.outputStream.close()
            } catch (_: Throwable) {
            }

            coroutineScope {
                val stdoutDeferred = async(Dispatchers.IO) {
                    try {
                        createdProcess.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Throwable) {
                        ""
                    }
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    try {
                        createdProcess.errorStream.bufferedReader().use { it.readText() }
                    } catch (e: Throwable) {
                        e.message ?: ""
                    }
                }

                // ShizukuRemoteProcess extends java.lang.Process but its exitValue() throws
                // IllegalArgumentException("process hasn't exited") instead of IllegalThreadStateException
                // when called before completion. Java's default waitFor(timeout, unit) only catches
                // IllegalThreadStateException, causing an unhandled IllegalArgumentException.
                // Using coroutine withTimeoutOrNull with blocking waitFor() cleanly avoids this bug.
                val exitCode = withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.IO) {
                        try {
                            createdProcess.waitFor()
                        } catch (_: InterruptedException) {
                            -1
                        }
                    }
                }

                if (exitCode == null) {
                    destroyProcessSafely(createdProcess)
                    val partialOut = withTimeoutOrNull(250L) {
                        runCatching { stdoutDeferred.await() }.getOrDefault("")
                    }.orEmpty()
                    val partialErr = withTimeoutOrNull(250L) {
                        runCatching { stderrDeferred.await() }.getOrDefault("")
                    }.orEmpty()
                    stdoutDeferred.cancel()
                    stderrDeferred.cancel()
                    return@coroutineScope ShellResult(
                        exitCode = -2,
                        stdout = partialOut,
                        stderr = buildString {
                            append("Command timed out after ${timeoutMs}ms")
                            if (partialErr.isNotBlank()) {
                                append(": ")
                                append(partialErr.trim())
                            }
                        }
                    )
                }

                val stdoutText = stdoutDeferred.await()
                val stderrText = stderrDeferred.await()
                ShellResult(
                    exitCode = exitCode,
                    stdout = stdoutText,
                    stderr = stderrText
                )
            }
        } catch (e: Throwable) {
            val targetError = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku exec exception: ${targetError.javaClass.simpleName}: ${targetError.message}"
            )
        } finally {
            process?.let { destroyProcessSafely(it) }
        }
    }

    private fun destroyProcessSafely(process: Process) {
        try {
            if (process is ShizukuRemoteProcess) {
                process.destroy()
            } else {
                process.destroyForcibly()
            }
        } catch (_: Throwable) {
        }
    }
}
