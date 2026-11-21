package com.shilapi.xcertplay.network

/**
 * Android 9（SDK < 29）上「Wi-Fi Direct 建组」的**信道计划**（2026-10-11）。
 *
 * ## 来源：DiPlay 的 Android 9 路（用户指定参考 `carlito12345/DiPlay`）
 *
 * DiPlay 在 `WifiP2pGroupManager` 里对 `SDK_INT < Q` 走一条专门的分支：
 *
 * 1. **`createGroup` 之前先 pin 工作信道** —— 走隐藏 API
 *    `WifiP2pManager.setWifiP2pChannels(channel, operatingChannel, listener)`
 *    （`WifiP2pLegacyChannels.apply`，`LISTEN_CHANNEL = 6`）。
 *    Android 9 没有公开的 `WifiP2pConfig.setGroupOperatingBand/Frequency`（API 29+ 才有），
 *    所以这一步是那代系统上**唯一**能指定信道的办法；
 * 2. **`createGroup` 用经典重载**（不带 config）：`createGroup(channel, listener)`；
 * 3. **把 `BUSY` 当"这个信道被拒"，不是"Wi-Fi Direct 不可用"**：
 *    DiPlay 原文 —— `BUSY is a per-channel outcome, not a verdict on Wi-Fi Direct:
 *    several firmwares reject every 5 GHz request while 2.4 GHz or the system default
 *    configuration still succeeds.` ⇒ 沿信道计划往下走，别在第一个被拒的信道上放弃。
 *
 * ## 真车为什么需要它（本工程 CS11，设备 `67b3c24af6b5`）
 *
 * 旧实现是"同一个请求重试 4 次"：日记里 4 次全部 **5ms 内回 BUSY**，然后就判死。
 * 按 DiPlay 的口径，那只说明**当前那个信道**被拒，完全没试过 2.4GHz 与系统默认重载。
 *
 * ## 计划里的信道
 *
 * 5GHz 只取**非 DFS** 的两个常用信道（5180/5745），2.4GHz 取 6/1/11，最后一步 `null`
 * = 不指定信道（系统默认重载，要先清掉我们 pin 过的限制，见调用方）。
 * 顺序刻意"先 5GHz"：车机 Wi-Fi 客户端若不在 5GHz，5GHz 组不会与它抢同一段频谱（DiPlay 原文）。
 */
internal object LegacyWifiP2pChannels {

    /** 监听信道（与 DiPlay `WifiP2pLegacyChannels.LISTEN_CHANNEL` 同值）。 */
    const val LISTEN_CHANNEL = 6

    /** pin 信道的回调等待上限（DiPlay `WifiP2pLegacyChannels.CALLBACK_TIMEOUT_MILLIS`）。 */
    const val CALLBACK_TIMEOUT_MILLIS = 2_000L

    /**
     * 建组信道计划；`null` 元素 = 不指定信道（系统默认重载）。
     *
     * ⚠️ 与 DiPlay 的完整计划相比做了**有意收敛**：DiPlay 还会考虑"车机客户端当前所在频段
     * 是否与组信道相同"（避免同一射频来回跳导致视频卡顿）。本工程的车机客户端在这台车上
     * 是关的（`WiFi：启用=false`），那条判据没有输入，故先不引入；等实测出现"组建起来但
     * 画面卡顿"再按 DiPlay 的 `stationOnFiveGhz` 分支补。
     */
    fun planChannels(): List<Int?> = listOf(5180, 5745, 2437, 2412, 2462, null)

    /** 频率（MHz）→ 信道号；映射不出来返回 null（与 DiPlay `operatingChannelFor` 同口径）。 */
    fun operatingChannelFor(frequencyMHz: Int): Int? = when {
        frequencyMHz in 5180..5240 || frequencyMHz in 5745..5825 -> frequencyMHz / 5 - 1000
        frequencyMHz in 2412..2472 -> (frequencyMHz - 2407) / 5
        else -> null
    }

    /** 诊断用的频段标签。 */
    fun bandLabel(frequencyMHz: Int): String = if (frequencyMHz / 5 - 1000 <= 14) "2.4 GHz" else "5 GHz"
}
