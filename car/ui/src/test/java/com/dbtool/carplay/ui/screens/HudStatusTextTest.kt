package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarHudStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HUD 设置版块文案的单测（2026-10-18）。
 *
 * 这些函数是纯的，组合却很多：未支持 / 未解析 / 绑定中 / 已连接 / 有失败 / 没收到帧。
 * 写错方向的表现是"设置页说已连接、车机其实没连"或"未解析到时显示了一个默认包名"，
 * 都会把现场排障带偏 —— 所以逐条钉死。
 */
class HudStatusTextTest {

    @Test
    fun `本机不支持时目标与连接都说明不支持`() {
        val status = CarHudStatus(supported = false)
        assertEquals("本机不支持", hudTargetText(status))
        assertEquals("不支持", hudConnectionText(status))
        assertEquals("—", hudCountersText(status))
        assertEquals("暂无导航提示", hudLatestText(status))
        assertNull("不支持时不该渲染失败行", hudFailureText(status))
    }

    @Test
    fun `解析不到服务时显示未找到而不是默认包名`() {
        val status = CarHudStatus(supported = true, target = "com.baidu.naviauto", targetPackage = "")
        assertEquals("未找到车机 HUD 服务", hudTargetText(status))
        assertEquals("未连接", hudConnectionText(status))
    }

    @Test
    fun `已连接时目标显示实际宿主包`() {
        val status = CarHudStatus(
            supported = true,
            available = true,
            targetPackage = "com.autonavi.amapauto",
        )
        assertEquals("com.autonavi.amapauto", hudTargetText(status))
        assertEquals("已连接", hudConnectionText(status))
    }

    @Test
    fun `绑定中优先于未连接`() {
        val status = CarHudStatus(supported = true, connecting = true)
        assertEquals("绑定中…", hudConnectionText(status))
    }

    @Test
    fun `已连接优先于绑定中`() {
        val status = CarHudStatus(supported = true, available = true, connecting = true)
        assertEquals("已连接", hudConnectionText(status))
    }

    @Test
    fun `有失败原因才显示失败行`() {
        val failed = CarHudStatus(
            supported = true,
            lastFailure = "com.baidu.naviauto：车机没有该服务（bindService 返回 false）",
        )
        assertEquals("com.baidu.naviauto：没有可用的地图服务", hudFailureText(failed))

        val clean = CarHudStatus(supported = true, lastFailure = "")
        assertNull("没有失败时那一行不该出现", hudFailureText(clean))
    }

    @Test
    fun `失败原因不把实现细节暴露给车主`() {
        val onBind = CarHudStatus(supported = true, lastFailure = "服务 onBind 返回 null")
        assertEquals("地图服务未响应", hudFailureText(onBind))

        val transaction = CarHudStatus(supported = true, lastFailure = "HUD 事务 10 异常：RemoteException")
        assertEquals("推送失败，服务未响应", hudFailureText(transaction))
    }

    @Test
    fun `无导航时显示暂无导航提示`() {
        assertEquals("暂无导航提示", hudLatestText(CarHudStatus(supported = true)))
    }

    @Test
    fun `最近一条是转向文案加距离`() {
        val status = CarHudStatus(
            supported = true,
            lastManeuverText = "左转",
            lastSegmentMeters = 200,
        )
        assertEquals("左转 · 200 米", hudLatestText(status))
    }

    @Test
    fun `超过一千米用公里`() {
        val status = CarHudStatus(
            supported = true,
            lastManeuverText = "右转",
            lastSegmentMeters = 1250,
        )
        assertEquals("右转 · 1.3 公里", hudLatestText(status))
    }

    @Test
    fun `只有距离没有转向文案时只显示距离`() {
        val status = CarHudStatus(supported = true, lastSegmentMeters = 80)
        assertEquals("80 米", hudLatestText(status))
    }

    @Test
    fun `计数按接收推送丢弃的顺序展示`() {
        val status = CarHudStatus(
            supported = true,
            receivedFrames = 12,
            pushedFrames = 9,
            droppedFrames = 3,
        )
        assertEquals("收到 12 次 · 桥接受理 9 次 · 重复更新已过滤 3 次", hudCountersText(status))
    }
}
