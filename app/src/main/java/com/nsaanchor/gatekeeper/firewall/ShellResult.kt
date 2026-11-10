package com.nsaanchor.gatekeeper.firewall

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append(stderr.trim())
            }
        }
}
