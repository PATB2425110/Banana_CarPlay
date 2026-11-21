package com.shilapi.xcertplay.network

/** All cleanup steps run even if one resource throws. TUN must always be released first. */
internal fun closeCarPlayTransportResources(
    closeTun: () -> Unit,
    closeServer: () -> Unit,
    closeSessions: () -> Unit,
    closeBridge: () -> Unit,
    onFailure: (String, Exception) -> Unit,
) {
    for ((name, close) in listOf(
        "VPN tunnel" to closeTun,
        "AirPlay listener" to closeServer,
        "AirPlay sessions" to closeSessions,
        "NCM bridge" to closeBridge,
    )) {
        try {
            close()
        } catch (error: Exception) {
            onFailure(name, error)
        }
    }
}
