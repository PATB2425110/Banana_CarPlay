package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表投屏入口与触摸分流的源码契约。
 *
 * 这些接线点依赖车机窗口和会话，JVM 单测无法真正建窗；这里静态钉住手动入口、
 * 投屏诊断日志，以及移除多指投屏触发后的触摸保护行为。
 */
class CarClusterProjectionDiagnosabilityContractTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private fun codeOf(relative: String): String = sourceOf(relative)
        .replace(Regex("(?s)/\\*.*?\\*/"), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private val controller = codeOf(
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt",
    )
    private val contract = codeOf(
        "car/ui/src/main/java/com/dbtool/carplay/ui/CarUiContract.kt",
    )
    private val screen = codeOf(
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt",
    )
    private val carPlayScreen = codeOf(
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt",
    )
    private val activity = codeOf(
        "car/app/src/main/java/com/dbtool/carplay/app/MainActivity.kt",
    )

    @Test
    fun `仪表投屏日志同时落盘与进浮层`() {
        assertTrue(
            "CarAppController 必须同时写诊断日志与界面浮层",
            controller.contains("platform.diagnostics.log(TAG, \"仪表投屏：\$message\")") &&
                controller.contains("appendLog(message)"),
        )
    }

    @Test
    fun `设置页通过总开关手动投屏`() {
        assertTrue("契约层必须保留 setClusterVisible", contract.contains("fun setClusterVisible(visible: Boolean)"))
        assertTrue(
            "控制器必须将总开关接到统一的显隐实现",
            controller.contains("override fun setClusterVisible(visible: Boolean)") &&
                controller.contains("showClusterProjection(source = \"仪表投屏按钮\", manualOpen = true)") &&
                controller.contains("hideClusterProjection(source = \"仪表投屏按钮\")"),
        )
        assertFalse("旧的「投到仪表」按钮必须移除", screen.contains("\"投到仪表\""))
        assertFalse("设置页不应再展示投屏状态", screen.contains("\"投屏状态\""))
        assertTrue(
            // 2026-10-23 分区重划：「投屏到仪表」改名为「在仪表上显示导航画面」，
            // 它现在只控制导航层；主题层显隐在「仪表主题」页单独开关。
            "设置页必须由「仪表盘投屏」开关驱动显隐",
            screen.contains("title = \"仪表盘投屏\"") &&
                screen.contains("actions.setClusterVisible("),
        )
    }

    @Test
    fun `投屏总开关先同步落盘再建窗`() {
        val target = screen.substringAfter("private fun ClusterTargetGroup(")
            .substringBefore("private fun ClusterContentGroup(")
        val updateIndex = target.indexOf("update(next)")
        val applyIndex = target.indexOf("actions.applySettings(next, reconnect = false)")
        val hideIndex = target.indexOf("actions.setClusterVisible(false)")
        val showIndex = target.indexOf("actions.setClusterVisible(true)")
        assertTrue("开启时必须先更新草稿", updateIndex >= 0)
        assertTrue("防抖绕过后必须同步提交", applyIndex > updateIndex)
        assertTrue("关闭时必须在提交前收起仪表", hideIndex in (updateIndex + 1)..<applyIndex)
        assertTrue("提交成功后才能建窗，避免读到旧 projectionEnabled", showIndex > applyIndex)
        assertEquals(
            "一次开关回调只能处理一次显隐，不能重复建窗/拆窗",
            2,
            Regex("actions\\.setClusterVisible\\(").findAll(target).count(),
        )
    }

    @Test
    fun `CarPlay画面本地三指投屏并保护不支持的多点触摸`() {
        assertTrue("三指手势在本地识别", carPlayScreen.contains("ThreeFingerProjectionGesture"))
        assertFalse("UI 契约不应再暴露投屏手势回调", contract.contains("onClusterGesture"))
        assertFalse("控制器不应再响应投屏手势动作", controller.contains("onClusterGesture"))
        assertTrue(
            "HID 最多两点；超过能力的整段触摸序列应被丢弃，不能截成两点转发",
            carPlayScreen.contains("if (gesture.releasePhone) {") && carPlayScreen.contains("currentActions.onTouch(emptyList())") &&
                carPlayScreen.contains("if (gesture.consume)"),
        )
        assertTrue(
            "一指/两指 CarPlay 触摸转发仍应保留",
            carPlayScreen.contains("CarTouchMapper.contacts(event, view.width, view.height)"),
        )
        assertTrue("必须受总开关控制且走手动意图", carPlayScreen.contains("if (currentProjectionEnabled) gesture.showProjection?.let { currentActions.setClusterVisible(it) }"))
    }

    @Test
    fun `窗口触摸诊断不再依赖多指手势探针`() {
        assertTrue(activity.contains("输入诊断：窗口层收到按下"))
        assertFalse("无需再记录多指手势专用状态", activity.contains("multiTouchLogged"))
        assertTrue("窗口层记录有界多指元数据以区分系统拦截与视频层命中", activity.contains("window_multi_touch pointers="))
    }

    @Test
    fun `主页导航入口开关已移除但保留旧偏好与独立导航功能`() {
        val settingsStore = codeOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt",
        )
        assertTrue(
            "设置读取必须恢复主页导航卡开关",
            settingsStore.contains("localNavigationCardEnabled = prefs.getBoolean(") &&
                settingsStore.contains("KEY_LOCAL_NAVIGATION_CARD_ENABLED"),
        )
        assertTrue(
            "设置保存必须持久化主页导航卡开关",
            settingsStore.contains("putBoolean(KEY_LOCAL_NAVIGATION_CARD_ENABLED, settings.localNavigationCardEnabled)"),
        )
        assertFalse("设置页不再提供主页导航入口开关", screen.contains("title = \"显示主页导航卡片\""))
        assertFalse("设置页不再改写主页导航入口偏好", screen.contains("draft.copy(localNavigationCardEnabled = it)"))
        val home = codeOf("car/ui/src/main/java/com/dbtool/carplay/ui/screens/HomeScreen.kt")
        assertTrue(
            "主页入口必须同时受用户设置与路线快照控制",
            home.contains("model.settings.localNavigationCardEnabled && model.navigation != null"),
        )
        assertTrue(
            "仪表副屏 iPhone 导航卡片开关仍是独立设置",
            screen.contains("checked = draft.clusterNavigationCard") &&
                screen.contains("draft.copy(clusterNavigationCard = it)"),
        )
    }
}
