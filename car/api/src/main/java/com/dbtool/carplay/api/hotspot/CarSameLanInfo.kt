package com.dbtool.carplay.api.hotspot

/**
 * 车机**作为客户端**所在的局域网信息（PATCH-0010，`CarHotspotMode.SAME_LAN`）。
 *
 * ## 与 [CarNativeHotspotInfo] 的分工
 *
 * 两者都是在描述"车机现在的网络环境"，但**方向相反**：
 *
 * | | [CarNativeHotspotInfo] | 本类 |
 * |---|---|---|
 * | 车机的角色 | **AP**（它给别人发网） | **STA**（它连着别人的网） |
 * | 关心什么 | 广播的 SSID、密码、信道、DHCP 池 | 它自己拿到的 IPv4、网关、网卡名 |
 * | 用途 | 复用车机热点（`MANUAL`） | 不建热点、直接同网直连（`SAME_LAN`） |
 *
 * ⇒ 判据也相反：[CarNativeHotspotInfo] 要的是"这张网卡在当 AP"，
 * 本类要的是"这张网卡连着别人的 AP"。
 *
 * ## 字段为什么几乎都可空
 *
 * 沿用 [CarNativeHotspotInfo] 同一条纪律：**读不到就留 null，绝不用默认值填坑**。
 * 填坑会让日志把"没读到"伪装成"读到了 192.168.1.5"，排障时反而更慢。
 */
data class CarSameLanInfo(
    /**
     * 车机当前有没有一张可用的局域网（拿到非 link-local 的 IPv4）。
     *
     * ⚠️ 这只是**车机侧**的判据。iPhone 是否连在同一张网上，
     * 我们无从验证——那要靠用户自己确认，且路由器不能开客户端隔离。
     */
    val connected: Boolean = false,

    /**
     * 通告给 iPhone 的地址（车机在这张网里的 IPv4）。
     *
     * ⚠️ **绝不能是 link-local（169.254.x.x）或回环地址**：那种地址在
     * iPhone 那一侧不可达，通告出去等于让 iPhone 去敲一扇不存在的门。
     * 读取侧必须显式排除（见 `CarSameLanReader`）。
     */
    val hostAddress: String? = null,

    /** 网卡名（如 `wlan0`）。只用于诊断。 */
    val interfaceName: String? = null,

    /** 默认网关。只用于诊断——现场"车机连的是不是我说的那个路由器"一眼可见。 */
    val gateway: String? = null,

    /** 子网前缀长度（如 24）。用于诊断与文案。 */
    val prefixLength: Int = 0,

    /**
     * 车机当前所连 Wi-Fi 的 **SSID**（iAP2 `0x5703` / `0x4301` 要用）。
     *
     * ## 为什么必须带上它（2026-10-07 真机修复）
     *
     * 实测（诊断包 `1791342910937`）：SAME_LAN 下 `0x5703` 里填的是热点名 `DBPlay`，
     * 而车机实际连的是另一张网 ⇒ iPhone 去关联不存在的 `DBPlay` ⇒ 永远不切 Wi-Fi
     * ⇒ 卡在「无线通道就绪」。**iPhone 是照 `0x5703` 里的 SSID 去连网的**，
     * 所以这里必须是**真实所连网的名字**。
     *
     * 读不到时留 null（如 ROM 返回 `<unknown ssid>`）；调用方应回落到设置页手填值。
     */
    val ssid: String? = null,

    /**
     * 车机所连 Wi-Fi 的密码。**通常为 null**——Android 读不到已保存网络的明文密码。
     *
     * iPhone 自己有该网密码，这个值对它只是冗余；读不到就如实留 null，
     * **绝不要拿热点密码来顶替**（那正是本次 bug 的成因）。
     */
    val wifiPassphrase: String? = null,

    /**
     * 结论式短标签，直接做设置页「本次将使用」的值列。
     *
     * 与 [summary] 的分工同 `CarHotspotPlan`：这里是"到底用哪个地址"，
     * [summary] 是"为什么 / 从哪读到的"。
     */
    val targetLabel: String = "",

    /** 人类可读的一句话，直接进设置页提示与诊断日志。 */
    val summary: String = "",
) {
    companion object {
        /** 读取失败或未连网时的空结果（[summary] 说明原因，供界面直接显示）。 */
        fun unavailable(reason: String): CarSameLanInfo = CarSameLanInfo(
            connected = false,
            targetLabel = "未连上局域网",
            summary = reason,
        )
    }
}
