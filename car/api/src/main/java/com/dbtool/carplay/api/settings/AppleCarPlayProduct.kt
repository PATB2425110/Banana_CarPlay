package com.dbtool.carplay.api.settings

/** Product boundary also applies to old preferences/imports, not just hidden UI. */
fun CarSessionSettings.forAppleCarPlay(): CarSessionSettings = copy(
    deviceName = "Lynk&Co", manufacturer = "Lynk&Co", model = "Lynk&Co", oemLabel = "Lynk&Co",
    hotspotMode = CarHotspotMode.GEELY_ROUTED,
    bluetoothHandoffPolicy = CarBluetoothHandoffPolicy.DISCONNECT_LINK,
    manualHotspotSecurity = if (manualHotspotSecurity == CarHotspotSecurity.OPEN) CarHotspotSecurity.OPEN else CarHotspotSecurity.WPA2,
    clusterFlip180 = false,
    mediaAudioChannel = 3,
    navigationAudioChannel = 15,
    advancedAudioChannelMapping = false,
    debugTunnelEnabled = false,
    enableBuiltInAdb = false,
    startupPerfCompile = false,
    reportMediaCardToCar = true,
)

/** Product credential autofill only; does not select the TCAM address/backend path. */
val CarHotspotMode.readsAppleCarPlayHotspotCredentials: Boolean
    get() = reusesCarNativeHotspot || this == CarHotspotMode.GEELY_ROUTED

/** Null means unavailable, not OPEN. Do not overwrite a saved password on read failure. */
fun CarSessionSettings.withReadCarHotspot(ssid: String?, passphrase: String?): CarSessionSettings {
    if (ssid.isNullOrBlank()) return this
    val validPassword = passphrase?.takeIf { it.isEmpty() || it.length in 8..63 }
    return copy(
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = validPassword ?: manualHotspotPassphrase,
        manualHotspotSecurity = when {
            validPassword == null -> manualHotspotSecurity
            validPassword.isEmpty() -> CarHotspotSecurity.OPEN
            else -> CarHotspotSecurity.WPA2
        },
    ).forAppleCarPlay()
}
