package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import java.net.Inet4Address
import java.net.InetAddress

/** A local CarPlay/AirPlay endpoint selected from a verified Ecarx client route. */
internal data class EcarxHotspotEndpoint(
    val interfaceName: String,
    val address: String,
)

internal enum class EcarxHotspotAttachmentReason {
    VERIFIED_CLIENT_ROUTE,
    UNSUPPORTED_PLATFORM,
    SDK_UNAVAILABLE,
    SDK_DISABLED,
    SDK_STATE_CONFLICT,
    NO_CURRENT_CLIENT,
    NO_VERIFIED_ROUTE,
    AMBIGUOUS_ROUTE,
    ROUTE_NOT_ECARX_SOURCE,
    INTERFACE_MISSING,
    INTERFACE_DOWN,
    SOURCE_ADDRESS_NOT_ON_INTERFACE,
}

internal data class EcarxHotspotAttachmentDecision(
    val endpoint: EcarxHotspotEndpoint? = null,
    val reason: EcarxHotspotAttachmentReason,
)

/**
 * Resolves whether an Ecarx client route is safe to use as this session's advertised local endpoint.
 * The AP gateway shown in T-Box settings is not an address on the Android/Linux host and is not
 * interchangeable with the local source address selected for the verified client route.
 */
internal object EcarxHotspotAttachmentPolicy {
    /**
     * 车机原厂热点上**还没有任何客户端**（iPhone 尚未入网）时，用户填的本机 endpoint 能不能照样通告。
     *
     * ## 为什么需要它（日志 7，CS11，2026-10-13）
     *
     * 复用的本意是：车机把热点 SSID/密码经蓝牙 0x5703 交给 iPhone，**由 iPhone 自己入网**。
     * 可入网之前 SDK 看不到客户端 ⇒ route 是 [EcarxHotspotAttachmentReason.NO_CURRENT_CLIENT]
     * ⇒ 没有 verified 网卡 ⇒ 「只挂有线口」闸门（`CarManualEndpointGate`）拦下整次连接，
     * 0x5703 一个字节都没发出去（14:16:50 `precheck blocked … gate=WIRED_ONLY`）。
     * 用户只好先手动连车机热点，等 route 变 VERIFIED（14:17:14）才放行 —— 先有鸡还是先有蛋。
     *
     * 而闸门"有线口 ⇒ 不是 Wi-Fi"的前提在这台车上不成立：放行后的 verified route 用的
     * 恰恰是有线口 `eth0.11` 上的同一个 `198.18.34.15`。该地址是 T-Box 内网的固定本机 endpoint，
     * 入网前后不变。
     *
     * ## 判据（刻意窄）
     *
     * - 决策原因必须正是 NO_CURRENT_CLIENT（SDK 没报"热点关闭"，也没报冲突/歧义）；
     * - 地址是 IPv4 字面量，且挂在某张 **UP** 的网卡上，该网卡与地址满足既有的
     *   [EcarxClientRoute.kx11Source]（Ecarx 平台 + `eth*` + `198.18/15`）。
     *   T-Box 网关（`192.168.15.1`）这类不在本机网卡上的地址、非 Ecarx 平台，都不满足 ⇒ 仍拦。
     */
    fun endpointAwaitingClient(
        decision: EcarxHotspotAttachmentDecision?,
        address: String,
        interfaces: List<CarHotspotApInterface.Iface>,
        sdkInt: Int,
        model: String,
        device: String,
    ): Boolean {
        if (decision?.reason != EcarxHotspotAttachmentReason.NO_CURRENT_CLIENT) return false
        val target = address.trim()
        // 先判字面量：getByName 对主机名会走 DNS，这里只接受 a.b.c.d。
        if (!CarHotspotApInterface.isIpv4Literal(target)) return false
        val source = runCatching { InetAddress.getByName(target) }.getOrNull() as? Inet4Address
            ?: return false
        return interfaces.any { iface ->
            iface.isUp &&
                iface.addresses.any { it.trim() == target } &&
                EcarxClientRoute.kx11Source(sdkInt, model, device, iface.name, source)
        }
    }

    fun resolve(
        snapshot: EcarxHotspotReader.Snapshot,
        sdkInt: Int,
        model: String,
        device: String,
        interfaces: List<CarHotspotApInterface.Iface>,
    ): EcarxHotspotAttachmentDecision {
        if (!EcarxClientRoute.isEcarxPlatform(sdkInt, model, device)) {
            return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.UNSUPPORTED_PLATFORM)
        }
        if (snapshot.routeStatus == EcarxHotspotReader.RouteStatus.UNAVAILABLE) {
            return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.SDK_UNAVAILABLE)
        }
        if (snapshot.enabled == false) {
            return EcarxHotspotAttachmentDecision(
                reason = if (snapshot.routeStatus == EcarxHotspotReader.RouteStatus.VERIFIED) {
                    EcarxHotspotAttachmentReason.SDK_STATE_CONFLICT
                } else {
                    EcarxHotspotAttachmentReason.SDK_DISABLED
                },
            )
        }
        when (snapshot.routeStatus) {
            EcarxHotspotReader.RouteStatus.UNAVAILABLE ->
                return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.SDK_UNAVAILABLE)
            EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT ->
                return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.NO_CURRENT_CLIENT)
            EcarxHotspotReader.RouteStatus.NO_VERIFIED_LOCAL_ROUTE ->
                return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.NO_VERIFIED_ROUTE)
            EcarxHotspotReader.RouteStatus.AMBIGUOUS ->
                return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.AMBIGUOUS_ROUTE)
            EcarxHotspotReader.RouteStatus.VERIFIED -> Unit
        }
        val (interfaceName, source) = snapshot.routeSources.entries.singleOrNull()
            ?.let { it.key to it.value }
            ?: return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.AMBIGUOUS_ROUTE)
        val sourceAddress = source.hostAddress
            ?: return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.ROUTE_NOT_ECARX_SOURCE)
        if (source !is Inet4Address ||
            !EcarxClientRoute.kx11Source(sdkInt, model, device, interfaceName, source)
        ) {
            return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.ROUTE_NOT_ECARX_SOURCE)
        }
        val iface = interfaces.singleOrNull { it.name == interfaceName }
            ?: return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.INTERFACE_MISSING)
        if (!iface.isUp) {
            return EcarxHotspotAttachmentDecision(reason = EcarxHotspotAttachmentReason.INTERFACE_DOWN)
        }
        if (iface.addresses.none { it.trim() == source.hostAddress }) {
            return EcarxHotspotAttachmentDecision(
                reason = EcarxHotspotAttachmentReason.SOURCE_ADDRESS_NOT_ON_INTERFACE,
            )
        }
        return EcarxHotspotAttachmentDecision(
            endpoint = EcarxHotspotEndpoint(interfaceName, sourceAddress),
            reason = EcarxHotspotAttachmentReason.VERIFIED_CLIENT_ROUTE,
        )
    }
}

/** In-process record of the last connection-time decision, distinct from a later diagnostic read. */
internal object EcarxHotspotSessionObservation {
    data class Observation(
        val observedAtEpochMillis: Long,
        val sdkEnabled: Boolean?,
        val routeStatus: EcarxHotspotReader.RouteStatus,
        val routeCount: Int,
        val configuredHost: String?,
        val decisionReason: EcarxHotspotAttachmentReason,
        val selectedInterface: String?,
        val routeSourceAddress: String?,
        val advertisedEndpoint: String?,
    )

    @Volatile
    var latest: Observation? = null
        private set

    fun record(
        snapshot: EcarxHotspotReader.Snapshot?,
        configuredHost: String?,
        decision: EcarxHotspotAttachmentDecision,
    ) {
        latest = Observation(
            observedAtEpochMillis = System.currentTimeMillis(),
            sdkEnabled = snapshot?.enabled,
            routeStatus = snapshot?.routeStatus ?: EcarxHotspotReader.RouteStatus.UNAVAILABLE,
            routeCount = snapshot?.routeCount ?: 0,
            configuredHost = configuredHost?.trim()?.takeIf { it.isNotEmpty() },
            decisionReason = decision.reason,
            selectedInterface = decision.endpoint?.interfaceName,
            routeSourceAddress = decision.endpoint?.address,
            advertisedEndpoint = decision.endpoint?.address,
        )
    }
}
