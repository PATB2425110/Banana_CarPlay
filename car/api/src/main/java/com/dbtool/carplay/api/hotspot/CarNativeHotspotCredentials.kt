package com.dbtool.carplay.api.hotspot

/**
 * 车机自带热点的**真实凭据**（设置页「一键填入」用，2026-10-13）。
 *
 * ## 它解决的是什么
 *
 * 「复用车机热点」要求用户手填车机热点的 SSID / 密码，而这两个值：
 *
 * - 凭据在车机侧，用户在本 App 里**看不到**；
 * - 手填极易出错（真车日志 4：填 `Lynk&Co`，真名是 `Lynk & Co` ⇒
 *   通告了一张没人广播的网，iPhone 静默连不上）。
 *
 * 探测链路（`CarNativeHotspotProbe`）本来就能读到这两个值，把它直接递给设置页，
 * 让"填对"变成点一下，而不是照着一个看不见的值默写。
 *
 * ## 密码读不到时只填 SSID
 *
 * 厂商可能把 `getPassphrase()` 置空（或 WPA3-SAE 根本没有静态密码），
 * 此时 [passphrase] 为 null —— 调用方**只填名称、不动密码框**，
 * 不能拿空串去清掉用户已填的密码。
 *
 * ## 为什么放在 `:car:api`
 *
 * 与 `CarHotspotEndpointCandidate` 同一条理由：它要同时出现在
 * `:car:core-bridge`（简报）与 `:car:ui`（model）的公共契约里，
 * 而 UI 不依赖 core-bridge。
 */
data class CarNativeHotspotCredentials(
    /** 车机热点**存储配置**里的 SSID（上游 `ManualHotspotManager` 拿去校验的那个名字）。 */
    val ssid: String,
    /** 热点密码；null = 车机侧读不到（调用方只填 SSID，不动密码框）。 */
    val passphrase: String?,
) {
    companion object {
        /**
         * 从探测结果里取凭据；探测不到 / 没读到名字时返回 null。
         *
         * 用存储配置名 [CarNativeHotspotInfo.ssid] 而不是 [CarNativeHotspotInfo.runningSsid]：
         * 填入的值是要交给上游校验与通告的，校验的正是存储配置名。
         * 空密码（开放网络）与读不到都归一为 null —— 两种情况都不该动密码框。
         */
        fun of(info: CarNativeHotspotInfo?): CarNativeHotspotCredentials? {
            if (info == null || !info.detected) return null
            val ssid = info.ssid?.takeIf { it.isNotBlank() } ?: return null
            return CarNativeHotspotCredentials(
                ssid = ssid,
                passphrase = info.passphrase?.takeIf { it.isNotBlank() },
            )
        }
    }
}
