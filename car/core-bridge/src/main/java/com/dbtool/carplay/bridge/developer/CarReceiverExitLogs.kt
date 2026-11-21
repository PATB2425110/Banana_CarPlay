package com.dbtool.carplay.bridge.developer

/** Read-only receiver-exit evidence through the existing, authorized ADB shell. */
internal object CarReceiverExitLogs {
    fun collect(shell: CarSystemShell?, timeoutMillis: () -> Long): Map<String, String> {
        if (shell == null) return mapOf("receiver_exit_capture.txt" to "ADB shell unavailable; no system crash evidence collected.\n")
        val files = linkedMapOf<String, String>()
        val status = StringBuilder("Source: existing ADB shell; read-only; buffers not cleared.\n")
        for ((name, command) in listOf(
            "logcat_crash.txt" to "logcat -d -v threadtime -b crash",
            "logcat_process_events.txt" to "logcat -d -v threadtime -b events -t 2000",
        )) {
            val remaining = timeoutMillis()
            if (remaining <= 0) {
                status.append("$name: skipped (deadline exhausted)\n")
                continue
            }
            val output = runCatching { shell.run(command, minOf(remaining, 5000L)) }.getOrNull()
            when {
                output == null -> status.append("$name: failed or unavailable\n")
                output.isBlank() -> status.append("$name: command returned empty output; not proof of no crash\n")
                else -> {
                    files[name] = output
                    status.append("$name: collected; inspect timestamps and process identity\n")
                }
            }
        }
        files["receiver_exit_capture.txt"] = status.toString()
        return files
    }
}
