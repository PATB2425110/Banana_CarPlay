// 移植自 DiPlay v0.2.16 / docs/KX11_ROUTED_HOTSPOT.md（GPL-3.0，carlito12345/DiPlay）。
// DBPlay 适配：仅补出处注释，判定语义保持原样。
package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object HotspotAddressPolicy {
    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}
