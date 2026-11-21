// 移植自 carlito12345/DiPlay（GPL-3.0）network/HotspotAddressPolicy.kt
// DBPlay 适配：原样保留判定语义，只改包名与注释。
package com.dbtool.carplay.bridge.hotspot

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 热点地址家族与可用性判定。
 *
 * 只做纯函数判定：不读系统状态、不碰网卡。IPv4 优先，IPv6 只收 link-local。
 */
internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    /** IPv4 优先返回；没有可用 IPv4 时返回首个 link-local IPv6；都没有则 null。 */
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

    /** 指定偏好家族时优先返回该家族；IPv6 偏好下仍允许回落到 IPv4。 */
    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily != FAMILY_IPV6) {
            return select(addresses)
        }
        for (address in addresses) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                return address
            }
        }
        return select(addresses)
    }

    /** 已发布地址是 IPv6 时找一个可用 IPv4；反之找 link-local IPv6。 */
    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? {
        val publishedIsIpv6 = published is Inet6Address
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address) && publishedIsIpv6) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && !publishedIsIpv6 && linkLocal == null) {
                linkLocal = address
            }
        }
        return if (publishedIsIpv6) null else linkLocal
    }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}
