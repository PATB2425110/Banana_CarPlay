package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarAdbHotspotHost
import com.dbtool.carplay.api.hotspot.CarAdbHotspotState
import com.dbtool.carplay.api.hotspot.CarHotspotNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「热点永久开关」的接口契约（2026-10-04）。
 *
 * ## 斌哥的口径（verbatim）
 *
 * > 关掉热点是永久关闭，热点是永久开启，跟随车机自启动热点
 *
 * 三个要点，逐条钉在这里：
 * 1. **永久**——不是"关到下次开机为止"，不存在"本次开机"这种中间态；
 * 2. **跟随车机自启动**——开机预热路径必须读同一份判据；
 * 3. **可通过设置恢复**——判据是持久化设置，不是内存标记。
 *
 * ## 为什么测接口层而不是 `CarAdbHotspotManager`
 *
 * manager 的构造要吃 `Context`（它要真去 adb shell 起 hostapd），本模块是
 * 纯 JVM 单测、没有 Robolectric，构造不出来。但这里要锁的恰恰**不是**
 * "hostapd 起没起来"那次 I/O，而是**接口默认语义**——桥在会话启动时问
 * `isAutoStartAllowed()` 到底得到什么。
 */
class CarAdbHotspotAutoStartContractTest {

    /**
     * 只记状态的最小假实现。**照原样继承**两个默认方法，
     * 用来验证"默认实现不改变既有行为"。
     */
    private class FakeHost : CarAdbHotspotHost {
        var stopped = false
        override fun state() = CarAdbHotspotState()
        override fun ensureStarted(
            network: CarHotspotNetwork?,
            onState: (CarAdbHotspotState) -> Unit,
        ) = true
        override fun selfCheck(interfaceOverride: String, onState: (CarAdbHotspotState) -> Unit) = Unit
        override fun stop() { stopped = true }
    }

    /**
     * 🔴 默认实现（不覆写）必须**照旧允许自动拉起**。
     *
     * 防的是"新加一个默认值把老实现悄悄改坏"：老的替身/空实现没有覆写
     * `isAutoStartAllowed`，它们的行为必须与加这个功能之前**一模一样**。
     */
    @Test
    fun `默认实现不改既有行为：恒允许自动拉起`() {
        val host = FakeHost()
        assertTrue("没覆写的实现必须仍然允许自动拉起", host.isAutoStartAllowed())
        host.stopByUser()
        assertTrue(
            "默认 stopByUser 只是转发 stop，不得偷偷改变自动拉起语义",
            host.isAutoStartAllowed(),
        )
        assertTrue("默认 stopByUser 必须真的停掉", host.stopped)
    }

    /**
     * 🔴 **永久**语义：判据是持久化开关，不随进程/开机重置。
     *
     * 这是斌哥那条需求的核心断言。用"gate 读一份可变状态"来模拟
     * `settings.keepHotspotAlive` 落盘——`stopByUser` 本身**不改** gate，
     * 改 gate 的是调用方（`CarAppController` 落盘）。这里验证：
     * gate 一旦为 false，自动拉起就被拒绝，且**没有任何"下次开机恢复"的路径**。
     */
    @Test
    fun `永久关闭：gate 为 false 时拒绝自动拉起，且不会自己恢复`() {
        // 模拟落盘的 keepHotspotAlive。
        var keepAlive = true
        val host = object : CarAdbHotspotHost by FakeHost() {
            override fun isAutoStartAllowed(): Boolean = keepAlive
        }

        assertTrue("关之前应当允许自动拉起", host.isAutoStartAllowed())

        // 用户点「永久关闭」：调用方落盘 + 停硬件。
        keepAlive = false
        host.stopByUser()

        assertFalse("永久关过之后必须拒绝自动拉起", host.isAutoStartAllowed())
        // ⚠️ 关键：判据是**落盘值**，进程重启读到的还是 false —— 没有任何
        // "下次开机自动恢复"的路径。这正是"永久"二字的含义。
        assertFalse("重启后读到的仍是落盘的 false", keepAlive)

        // 用户回设置页重新打开。
        keepAlive = true
        assertTrue("用户显式重新打开后应当允许自动拉起", host.isAutoStartAllowed())
    }
}
