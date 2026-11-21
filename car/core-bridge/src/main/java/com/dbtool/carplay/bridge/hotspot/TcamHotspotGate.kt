package com.dbtool.carplay.bridge.hotspot

/**
 * TCAM 复用前的"要不要拦"判定（纯函数，无 Android 依赖）。
 *
 * 背景：TCAM 平台下 `WifiManager.isWifiApEnabled()` 会假关闭（热点实际开着，
 * 手机已拿到 `192.168.15.x`）。旧逻辑据此直接抛 `The car TCAM hotspot is off`，
 * 把可复用的热点连根掐掉。现在只在**厂商 SDK 明确说关**且没有客户端时才拦。
 *
 * 判据顺序固定：
 * 1. 厂商说开着（state 13 或 enabled == true）→ 放行；
 * 2. 厂商状态是 10/11/14 且客户端为空 → 拦；
 * 3. 厂商快照为 null（SDK 读不到）→ 放行，交给后续链路超时报错；
 * 4. 其余（12 ENABLING、有客户端等）→ 放行；
 * 5. `legacyEnabled == false` 单独出现**不构成**拦截理由。
 */
object TcamHotspotGate {

    /** 厂商状态里明确"没开"的三态：10 DISABLING / 11 DISABLED / 14 FAILED。 */
    private val OFF_STATES = setOf(10, 11, 14)

    /**
     * 返回 null = 不拦（继续尝试附着）；返回非空 = 拦截原因。
     *
     * @param vendor 厂商快照；null 表示 SDK 读不到（或读取失败）。
     * @param legacyEnabled `WifiManager.isWifiApEnabled()` 的结果，仅作诊断参考；
     *        在 TCAM 平台上可能是假关闭，**不参与拦截判定**。
     */
    fun blockReason(vendor: EcarxWifiApSnapshot?, legacyEnabled: Boolean?): String? {
        if (vendor?.connected == true) return null
        val state = vendor?.state
        if (state != null && state in OFF_STATES && vendor.clients.isEmpty()) {
            return "TCAM hotspot is off (vendor state=$state)"
        }
        // vendor == null、state 12 ENABLING、或有客户端：一律放行。
        // legacyEnabled == false 也不再拦 —— 这正是本次修掉的误判。
        return null
    }
}
