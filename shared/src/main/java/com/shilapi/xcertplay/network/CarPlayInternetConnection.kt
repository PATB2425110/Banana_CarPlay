package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URL
import java.net.URLConnection

/**
 * Per-request DNS and sockets, never process-wide binding. Enabled by our USB VPN service.
 * Applies only to explicit Internet HTTP callers; CarPlay/local MFi sockets are unchanged.
 */
object CarPlayInternetConnection {
    @Volatile private var context: Context? = null
    @Volatile internal var usbVpnActive: Boolean = false

    fun initialize(context: Context) { this.context = context.applicationContext }

    fun open(url: URL): URLConnection {
        if (!usbVpnActive) return url.openConnection()
        val app = context ?: return url.openConnection()
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return url.openConnection()
        val active = cm.activeNetwork
        val caps = active?.let(cm::getNetworkCapabilities)
        // Leave the existing default path untouched when no VPN is selected.
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return url.openConnection()
        val underlying = cm.allNetworks.mapNotNull { network ->
            val c = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            network to c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.sortedByDescending { it.second }.firstOrNull()?.first
        // Network.openConnection uses that network for both DNS and the connection.
        // No retry after a POST: avoid duplicate diagnostic/profile submissions.
        return underlying?.openConnection(url) ?: url.openConnection()
    }
}
