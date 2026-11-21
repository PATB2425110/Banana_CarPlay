// 移植自 DiPlay v0.2.16 / docs/KX11_ROUTED_HOTSPOT.md（GPL-3.0，carlito12345/DiPlay）。
// DBPlay 适配：仅补出处注释，判定语义保持原样。
package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure { HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION }

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
