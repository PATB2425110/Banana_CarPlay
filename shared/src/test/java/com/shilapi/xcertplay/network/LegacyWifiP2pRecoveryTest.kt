package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0064 的三条恢复判据（2026-10-10）。
 *
 * ## 为什么必须钉死
 *
 * 真车日志（诊断包 `1791546694008_e2f499c9_67b3c24af6b5`，VIN `L6TTEST1111111111`）里
 * `P2P_DIRECT` **7 次会话尝试全部**死在 `isWifiEnabled == false` 上，而系统日志同时写着
 * `WifiControllerIPCP: current state: WIFI_STATE_DISABLED`——车机框架 Wi-Fi 平时就是关的。
 *
 * 修复把"要不要接管车机 Wi-Fi"、"什么算开好了"、"createGroup 该不该重试"三件事
 * 抽成纯函数放在 [LegacyWifiP2pRecovery] 里。这三条一旦被人"顺手简化"
 * （最典型的是把 `currentlyEnabled == true` 写成 `!= null`、把 `ENABLING` 也当成功），
 * 后果都是**改别人的状态**或**在没就绪时硬发请求**，而这两种错在编译期完全看不出来。
 *
 * 数值本身对着 `android-34/android.jar` 用 `javap -constants` 核过：
 * `WIFI_STATE_DISABLING=0` / `DISABLED=1` / `ENABLING=2` / `ENABLED=3`、
 * `WifiP2pManager.ERROR=0` / `BUSY=2`。
 *
 * ⚠️ **2026-10-13（PATCH-0113）**：上面引用的 `P2P_DIRECT` 那一档热点模式已整体移除，
 * 这段现场属**历史证据**；本测试钉的是**保留下来的**判据，照旧跑。
 */
class LegacyWifiP2pRecoveryTest {

    // ------------------------------------------------------------ 什么算"开好了"

    @Test
    fun onlyEnabledCountsAsPoweredOn() {
        assertTrue(legacyWifiStaIsPoweredOn(LEGACY_WIFI_STATE_ENABLED))
        // ⚠️ ENABLING(2) 不算成功：此时框架的 P2P 状态机还没起来，
        // 调 createGroup 拿到的是 BUSY/ERROR。
        assertFalse(legacyWifiStaIsPoweredOn(LEGACY_WIFI_STATE_ENABLING))
        assertFalse(legacyWifiStaIsPoweredOn(LEGACY_WIFI_STATE_DISABLING))
        assertFalse(legacyWifiStaIsPoweredOn(LEGACY_WIFI_STATE_DISABLED))
        // 读不到 ⇒ 继续等，绝不放行。
        assertFalse(legacyWifiStaIsPoweredOn(null))
        assertFalse(legacyWifiStaIsPoweredOn(4))
    }

    @Test
    fun onlyTransitionalStatesCountAsInFlight() {
        assertTrue(legacyWifiStaIsTransitioning(LEGACY_WIFI_STATE_DISABLING))
        assertTrue(legacyWifiStaIsTransitioning(LEGACY_WIFI_STATE_ENABLING))
        assertFalse(legacyWifiStaIsTransitioning(LEGACY_WIFI_STATE_DISABLED))
        assertFalse(legacyWifiStaIsTransitioning(LEGACY_WIFI_STATE_ENABLED))
        // 读不到不算"在途"：再等下去只是白等。
        assertFalse(legacyWifiStaIsTransitioning(null))
    }

    // ------------------------------------------------------------ 归属：只还自己借的

    @Test
    fun restoresOnlyTheStationThisRunPoweredOn() {
        // 我们开的、现在确实开着 ⇒ 关回去。
        assertTrue(legacyWifiStaShouldBeRestored(ownedByUs = true, currentlyEnabled = true))
        // 用户 / 其他应用本来就开着的 ⇒ 绝不碰。
        assertFalse(legacyWifiStaShouldBeRestored(ownedByUs = false, currentlyEnabled = true))
        // 读不到状态 ⇒ 不动作（不许闭着眼睛发关闭命令）。
        assertFalse(legacyWifiStaShouldBeRestored(ownedByUs = true, currentlyEnabled = null))
        assertFalse(legacyWifiStaShouldBeRestored(ownedByUs = false, currentlyEnabled = null))
        // 已经关了 ⇒ 没有可归还的东西。
        assertFalse(legacyWifiStaShouldBeRestored(ownedByUs = true, currentlyEnabled = false))
        assertFalse(legacyWifiStaShouldBeRestored(ownedByUs = false, currentlyEnabled = false))
    }

    // ------------------------------------------------------------ createGroup 重试

    @Test
    fun retriesOnlyBusyAndError() {
        // Wi-Fi 刚上电、P2P 状态机还没就绪 ⇒ 这两个是"等一会儿再来"的信号。
        assertTrue(legacyP2pCreateGroupRetryable(LEGACY_P2P_BUSY))
        assertTrue(legacyP2pCreateGroupRetryable(LEGACY_P2P_ERROR))
        // 硬件不支持 / 权限被拒 / 未知码 ⇒ 立刻失败，别白吃用户的等待时间。
        assertFalse(legacyP2pCreateGroupRetryable(1))
        assertFalse(legacyP2pCreateGroupRetryable(3))
        assertFalse(legacyP2pCreateGroupRetryable(99))
    }

    // ------------------------------------------------------------ 常量与 Android 对齐

    @Test
    fun stateCodesMatchTheAndroidPublicConstants() {
        // 本文件用字面量而不是引用 `WifiManager.*` / `WifiP2pManager.*`（纯 JVM 单测里
        // 那些字段不可靠）。这条断言就是"抄错了会红"的那道闸。
        assertEquals(0, LEGACY_WIFI_STATE_DISABLING)
        assertEquals(1, LEGACY_WIFI_STATE_DISABLED)
        assertEquals(2, LEGACY_WIFI_STATE_ENABLING)
        assertEquals(3, LEGACY_WIFI_STATE_ENABLED)
        assertEquals(0, LEGACY_P2P_ERROR)
        assertEquals(2, LEGACY_P2P_BUSY)
    }
}
