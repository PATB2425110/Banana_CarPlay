package com.shilapi.xcertplay.network

/**
 * DiPlay KX11 原厂热点方案移植的**总开关**（2026-10-21）。
 *
 * 依据：DiPlay v0.2.16 / `docs/KX11_ROUTED_HOTSPOT.md`。
 *
 * - `true`（默认）：启用移植行为 ——
 *   1. `ManualHotspotManager` 在“无显式地址、非自建”时探测 eth* + 198.18/15 原厂网关；
 *   2. `TcamHotspotManager` 对严格匹配的原厂网关**绕过** `bindProcessToNetwork`；
 *   3. 无线 AirPlay 监听绑定到本次选定的主地址（不再 bind `::`）。
 * - `false`：逐字节回到 DBPlay 1.1.42 的行为（三项全部关闭），用于现场 A/B 与回滚。
 *
 * 只影响上述三处；USB / 蓝牙 / 自建热点（ADB_HOSTAPD）/ iAP2 报文结构均不在开关范围内。
 */
object Kx11FactoryRoutePort {
    const val ENABLED = true
}
