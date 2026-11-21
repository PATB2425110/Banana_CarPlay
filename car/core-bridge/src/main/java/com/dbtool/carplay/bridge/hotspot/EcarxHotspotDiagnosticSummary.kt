package com.dbtool.carplay.bridge.hotspot

/** Pure renderer for address-role diagnostics; client IPv4 is opt-in and MAC/secrets are never emitted. */
internal object EcarxHotspotDiagnosticSummary {
    fun render(
        snapshot: EcarxHotspotReader.Snapshot,
        observation: EcarxHotspotSessionObservation.Observation?,
        configuredGateway: String?,
        androidWifiApState: Int?,
        includeClientIpv4: Boolean = false,
        recentClientEvidence: CarHotspotClientWatcher.RecentClientEvidence? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("Configured T-Box/AP gateway (user setting): ${configuredGateway?.trim()?.takeIf { it.isNotEmpty() } ?: "(unset)"}")
        appendLine("Android WifiManager.getWifiApState: ${androidWifiApState ?: "(unavailable)"} (${stateLabel(androidWifiApState)})")
        appendLine("Ecarx SDK enabled: ${snapshot.enabled ?: "(unknown)"}")
        appendLine("Ecarx SDK Wi-Fi6 enabled: ${snapshot.wifi6Enabled ?: "(unknown)"}")
        appendLine("Ecarx route status: ${snapshot.routeStatus}")
        appendLine("Ecarx clients=${snapshot.clientCount} clientAddresses=${snapshot.addressCount} routes=${snapshot.routeCount}")
        val sources = snapshot.routeSources.entries.joinToString { (name, address) ->
            "$name=${address.hostAddress} (local route source; not AP gateway)"
        }.ifEmpty { "(none)" }
        appendLine("Verified local route source(s): $sources")
        appendLine("Client side network subnet: ${snapshot.clientSubnet ?: "(unavailable)"}")
        val clientIpv4 = when {
            !includeClientIpv4 -> "(withheld; per-package opt-in is off)"
            snapshot.clientIpv4Addresses.isEmpty() -> "(unavailable from Ecarx SDK at package time)"
            else -> snapshot.clientIpv4Addresses.joinToString(",")
        }
        appendLine("Ecarx current client IPv4 (not DHCP lease history): $clientIpv4")
        val recent = recentClientEvidence?.takeIf {
            nowEpochMillis >= it.observedAtEpochMillis &&
                nowEpochMillis - it.observedAtEpochMillis <= CarHotspotClientWatcher.EVIDENCE_TTL_MILLIS
        }
        if (!includeClientIpv4) {
            appendLine("Session-window client IPv4: (withheld; per-package opt-in is off)")
        } else if (recent == null) {
            appendLine("Session-window client evidence: (unavailable or older than 600 seconds)")
        } else {
            val addresses = recent.ipv4Addresses.distinct().joinToString(",").ifEmpty { "(none reported)" }
            appendLine("Session-window client evidence ageSeconds=${(nowEpochMillis - recent.observedAtEpochMillis) / 1000} clients=${recent.clientCount} ipv4=$addresses routeStatus=${recent.routeStatus} interfaces=${recent.interfaces}")
        }
        appendLine("DHCP lease details (lease time/options): (not collected; no supported read-only OEM lease API)")
        appendLine("Client MAC: (never collected into diagnostic output)")
        appendLine("Local kernel route probe (this car only): ${snapshot.routeProbe ?: "(unavailable)"}")
        appendLine("Ecarx band: ${snapshot.band ?: "(unknown)"}")
        if (observation == null) {
            appendLine("Last MANUAL connection decision: (not recorded in this process)")
        } else {
            appendLine("Last MANUAL connection decision at epochMillis=${observation.observedAtEpochMillis}:")
            appendLine("  reason=${observation.decisionReason} routeStatus=${observation.routeStatus} routeCount=${observation.routeCount}")
            appendLine("  configuredGateway=${observation.configuredHost ?: "(unset)"}")
            appendLine("  selectedInterface=${observation.selectedInterface ?: "(none)"}")
            appendLine("  routeSource=${observation.routeSourceAddress ?: "(none)"}")
            appendLine("  selectedAdvertisedEndpoint=${observation.advertisedEndpoint ?: "(none)"}")
        }
        appendLine("Address roles: configured gateway is an external T-Box/AP address; local route source is the Android host address used to reach the SDK client; selected advertised endpoint is the address sent to iPhone.")
    }.trimEnd()

    private fun stateLabel(value: Int?): String = when (value) {
        10 -> "DISABLING"
        11 -> "DISABLED"
        12 -> "ENABLING"
        13 -> "ENABLED"
        14 -> "FAILED"
        null -> "unknown"
        else -> "unrecognized"
    }
}
