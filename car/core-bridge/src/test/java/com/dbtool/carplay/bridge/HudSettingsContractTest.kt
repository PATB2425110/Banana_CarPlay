package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.navigation.CarHudStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0114「HUD 导航推送加设置内容（状态 + 开关 + 排障信息）」的接线契约。
 *
 * ## 为什么钉它
 *
 * 这次改动**跨六个模块**（`:car:api` / `:car:core-bridge` / `:car:platform-geely` /
 * `:car:ui` / `:car:app` / 设置存储），而且**每一处漏接都是静默失败**：
 *
 * | 漏接 | 表现 |
 * | --- | --- |
 * | 设置字段没进 `SharedPreferences`（load 或 save 漏一边） | 关掉后重启又变回开（`fullscreen` 就这么丢过一次） |
 * | 没进 `CarDiagnosticSettingsDump` 白名单 | 现场在包里看不到它，而"看不到"与"没这功能"长得一样 |
 * | 没进 `CarSettingsReconnectPolicy` 白名单 | 界面永远多一句"有改动需重连生效"，用户以为没保存 |
 * | `CarPlaySessionBridge.updateSettings` 没接闸门 | 拨了开关不生效（`reconnect()` **不经过** `start()` 的接线段） |
 * | `GeelyHudBridge.start()` 不复位 `closed` | 「断开 → 再连接」之后 HUD **本进程内永久失效**，无任何报错 |
 * | 客户端闸门写成"断开"而不是"闸门" | 开关**关不回来**（`stop()` 之后 `start()` 无效，见上一行） |
 * | `refreshModel` 没投影 `hud` | 设置页那一整块永远显示"本车机没有 HUD 通道" |
 * | 设置页没画那一组 | 上面全都白做，用户看不见 |
 *
 * 这些行为在 JVM 上都跑不到（要真机 + 车机 + 一个装着 hudbridge 服务的仪表），
 * 所以按项目既有做法（`HudGuidanceWiringContractTest`、`NativeHotspotCredentialsWiringContractTest`
 * 同族）**静态读源码**把接线钉住，断任一环对应断言即红。
 *
 * ⚠️ 断言一律先剥注释（§29）—— 这些文件的 KDoc 里必然出现同样的字面量。
 */
class HudSettingsContractTest {

    // 单测 cwd = car/core-bridge（模块目录）。仓库根在两级之上。
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

    /** 去掉块注释与行注释再返回（项目纪律 §29）。 */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val hudContract =
        "car/api/src/main/java/com/dbtool/carplay/api/navigation/CarHudBridge.kt"
    private val sessionHost =
        "car/api/src/main/java/com/dbtool/carplay/api/session/CarSessionHost.kt"
    private val settings =
        "car/api/src/main/java/com/dbtool/carplay/api/settings/CarSessionSettings.kt"
    private val reconnectPolicy =
        "car/api/src/main/java/com/dbtool/carplay/api/settings/CarSettingsReconnectPolicy.kt"
    private val dump =
        "car/api/src/main/java/com/dbtool/carplay/api/developer/CarDiagnosticSettingsDump.kt"
    private val store =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt"
    private val sessionBridge =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"
    private val hudClient =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/navigation/CarPlayNavigationHudClient.kt"
    private val geelyBridge =
        "car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyHudBridge.kt"
    private val controller =
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"
    private val uiContract =
        "car/ui/src/main/java/com/dbtool/carplay/ui/CarUiContract.kt"
    private val settingsScreen =
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt"

    // ── 契约层：状态快照的形状 ──────────────────────────────────────────────

    /**
     * `CarHudStatus()` 必须能**无参构造**，且恰好是"本车机没有 HUD 通道"这一态。
     *
     * 这条同时是"全字段带默认值"的编译期证明（`CarUiModel` 的默认值、以及
     * `CarSessionHost.hudStatus()` 的默认实现都依赖它），所以放在运行期再断言一次
     * 那几个字段的取值 —— 只靠"能编译"证明不了 `supported` 的默认值是 false。
     */
    @Test
    fun `CarHudStatus 的空值态就是「本车机没有 HUD 通道」`() {
        val empty = CarHudStatus()
        assertFalse("默认必须是「不支持」，否则界面会对一台没有 HUD 的车显示通道信息", empty.supported)
        assertFalse(empty.available)
        assertFalse(empty.pushing)
        assertEquals("", empty.target)
        assertEquals("", empty.lastFailure)
        assertEquals("", empty.lastPush)
        assertEquals(0L, empty.pushedFrames)
        assertEquals(0L, empty.receivedFrames)
        assertEquals(0L, empty.droppedFrames)
    }

    @Test
    fun `桥契约必须提供 status 且给默认实现`() {
        val code = codeOf(hudContract)
        assertTrue(
            "CarHudBridge 必须有 status()，否则设置页拿不到桥侧状态",
            code.contains("fun status(): CarHudStatus = CarHudStatus()"),
        )
        assertTrue("必须定义状态类型本体", code.contains("data class CarHudStatus("))
        assertTrue(
            "契约要写明实现只需填桥侧那几项（上层侧由客户端合并）",
            code.contains("supported") && code.contains("pushedFrames") &&
                code.contains("receivedFrames") && code.contains("droppedFrames"),
        )
    }

    @Test
    fun `会话宿主必须提供 hudStatus 且默认「不支持」`() {
        val code = codeOf(sessionHost)
        assertTrue(
            "CarSessionHost 必须有 hudStatus()，界面只可能从这里拿到它",
            code.contains("fun hudStatus(): CarHudStatus = CarHudStatus()"),
        )
    }

    // ── 设置项：字段 + 持久化 + 诊断白名单 + 重连策略 ────────────────────────

    @Test
    fun `设置字段存在且默认为开`() {
        val code = codeOf(settings)
        assertTrue(
            "必须有 hudGuidanceEnabled 且**默认 true**（默认关会让新装用户看不到这个能力）",
            code.contains("val hudGuidanceEnabled: Boolean = true"),
        )
    }

    @Test
    fun `持久化：load 与 save 两边都要接，且用独立的新键`() {
        val code = codeOf(store)
        assertTrue(
            "load() 必须读它（漏了 ⇒ 关掉后重启又变回开）",
            code.contains("prefs.getBoolean(KEY_HUD_GUIDANCE_ENABLED, d.hudGuidanceEnabled)"),
        )
        assertTrue(
            "save() 必须写它（漏了 ⇒ 改了不落盘，重启即丢）",
            code.contains("putBoolean(KEY_HUD_GUIDANCE_ENABLED, settings.hudGuidanceEnabled)"),
        )
        assertTrue(
            "键名必须是新键 —— 复用到 projection_enabled 会让「关掉 HUD」顺手把投屏也关掉",
            code.contains("private const val KEY_HUD_GUIDANCE_ENABLED = \"hud_guidance_enabled\""),
        )
    }

    @Test
    fun `诊断包白名单必须包含它（白名单不是反射，漏了没有任何提示）`() {
        val code = codeOf(dump)
        assertTrue(
            "effective_settings.txt 必须打出 hudGuidanceEnabled —— 现场问「仪表怎么没有转向卡片」时它是第一现场",
            code.contains("appendLine(\"hudGuidanceEnabled: ") &&
                code.contains("settings.hudGuidanceEnabled"),
        )
    }

    @Test
    fun `重连策略必须登记成「就地生效」（否则界面永远多一句待重连）`() {
        val code = codeOf(reconnectPolicy)
        assertTrue(
            "LIVE_FIELDS 必须含 hudGuidanceEnabled",
            code.contains("\"hudGuidanceEnabled\","),
        )
        assertTrue(
            "抹平比较也必须覆盖它 —— 只写进 LIVE_FIELDS 而不写进 copy(...) 是**假登记**" +
                "（名单只用于文档与回归测试，真正的判据在 copy 里）",
            code.contains("hudGuidanceEnabled = previous.hudGuidanceEnabled,"),
        )
    }

    // ── 实现层：闸门 + 状态采集 ─────────────────────────────────────────────

    @Test
    fun `客户端闸门：关掉时既停推送、又主动清一次车机上的提示`() {
        val code = codeOf(hudClient)
        val start = code.indexOf("fun setEnabled(enabled: Boolean)")
        assertTrue("必须有 setEnabled —— 它是开关的唯一落点", start >= 0)
        val onState = code.indexOf("private fun onState(")
        assertTrue("onState 必须在（拿它当切片终点）", onState > start)
        val body = code.substring(start, onState)

        assertTrue(
            "状态没变时必须直接返回（幂等）",
            body.contains("if (wasEnabled == enabled) return"),
        )
        assertTrue(
            "关掉时必须补发一次 update(null)：否则仪表上那张卡片会一直挂着，" +
                "用户看到的正是「开关关掉了、卡片还在」",
            body.contains("bridge.update(null)"),
        )
        assertTrue(
            "重新打开时必须复位去重基线（否则重开后首帧可能被当成「没变化」丢掉）",
            body.contains("deduper.reset()"),
        )
        assertFalse(
            "⚠️ 关掉**不能**走 bridge.stop() —— 那会把桥推进「已停止」态，" +
                "而平台实例进程内唯一 ⇒ 开关**关不回来**（见 setEnabled 的 KDoc）",
            body.contains("bridge.stop()"),
        )
    }

    @Test
    fun `客户端闸门必须在 onState 的第一行（含「导航结束」那一支）`() {
        val code = codeOf(hudClient)
        val onState = code.indexOf("private fun onState(")
        val head = code.substring(onState, minOf(code.length, onState + 700))
        assertTrue(
            "闸门必须挡在最前面：关掉后一点都不该碰车机（不清提示、不发停止事务）",
            head.contains("if (!enabled) return"),
        )
        assertTrue(
            "闸门必须在 state == null 那支**之前**",
            head.indexOf("if (!enabled) return") < head.indexOf("if (state == null)"),
        )
    }

    @Test
    fun `客户端必须统计收帧与去重丢帧，并合并桥侧状态`() {
        val code = codeOf(hudClient)
        assertTrue(
            "收帧计数必须真的加上（否则排障行永远是 0，看不出 iPhone 到底发没发）",
            code.contains("receivedFrames += 1"),
        )
        assertTrue(
            "去重丢帧计数必须真的加上（仪表「没动」时多半是它）",
            code.contains("droppedFrames += 1"),
        )
        assertTrue(
            "status() 必须把桥侧状态 copy 过来再补自己那三项",
            code.contains("bridgeStatus.copy(") &&
                code.contains("pushing = enabled && unsubscribe != null") &&
                code.contains("receivedFrames = receivedFrames") &&
                code.contains("droppedFrames = droppedFrames"),
        )
    }

    @Test
    fun `会话桥：start 与 updateSettings 两处都要接闸门，并暴露 hudStatus`() {
        val code = codeOf(sessionBridge)
        assertTrue(
            "start() 里要按设置初始化闸门（会话起来时用户可能早就把它关了）",
            code.contains("hudClient?.setEnabled(settings.hudGuidanceEnabled)"),
        )
        assertTrue(
            "⚠️ updateSettings() 里也必须接 —— `reconnect()` **不经过** start() 的接线段，" +
                "只放 start() 的话「关掉 → 点重连生效」照样没用",
            code.contains("hudClient?.setEnabled(next.hudGuidanceEnabled)"),
        )
        assertTrue(
            "hudStatus() 必须委派给客户端（拿不到就是「本车机没有 HUD 通道」）",
            code.contains("override fun hudStatus(): CarHudStatus = hudClient?.status() ?: CarHudStatus()"),
        )
        assertTrue(
            "开关变更要留一行诊断（现场要知道这次会话到底推没推）",
            code.contains("HUD 导航推送："),
        )
    }

    @Test
    fun `吉利桥：status 要如实回显目标组件与计数，并实现可重启`() {
        val code = codeOf(geelyBridge)
        assertTrue(
            "必须实现 status()",
            code.contains("override fun status(): CarHudStatus = CarHudStatus("),
        )
        assertTrue(
            "target 必须是「包名/服务类名」，且取自常量（换车机后第一个要核对的就是它）",
            code.contains("target = \"\$PACKAGE/\$SERVICE\""),
        )
        assertTrue(
            "必须回显失败说明（绑不上是常态，且**完全静默**）",
            code.contains("lastFailure = lastFailure"),
        )
        assertTrue(
            "必须回显最近一帧与受理帧数",
            code.contains("lastPush = lastPush") && code.contains("pushedFrames = pushedFrames"),
        )
        assertTrue(
            "成功受理时必须真的写进 lastPush / pushedFrames",
            code.contains("lastPush = summary") && code.contains("pushedFrames += 1"),
        )
        assertTrue(
            "绑定失败、静默绑定失败、onBind 返回 null 三条路都要留痕",
            code.contains("bindService 返回 false") &&
                code.contains("静默绑定失败") &&
                code.contains("服务 onBind 返回 null"),
        )
        assertTrue(
            "⚠️ start() 必须复位 closed —— 否则「断开 → 再连接」之后 HUD 在本进程内**永久失效**" +
                "（平台实例进程内唯一，stop() 置位后过去永不复位），而且没有任何报错",
            code.contains("closed.getAndSet(false)") && code.contains("lifecycleEpoch.next()"),
        )
        assertTrue(
            "可用性回调必须在 post 里赋值（stop() 也往同一个 handler 排了「清回调」的任务，同步赋值会被它清掉）",
            code.contains("onAvailability = onAvailabilityChanged"),
        )
    }

    // ── 投影层 + 界面层 ────────────────────────────────────────────────────

    @Test
    fun `投影层必须把 hud 写进 model`() {
        val code = codeOf(controller)
        assertTrue(
            "refreshModel() 必须投影 hud，否则设置页那一整块永远显示「本车机没有 HUD 通道」",
            code.contains("hud = session.hudStatus(),"),
        )
    }

    @Test
    fun `UI 契约必须带上 hud 字段（默认「不支持」）`() {
        val code = codeOf(uiContract)
        assertTrue(
            "CarUiModel 必须有 hud 字段且带默认值（预览与单测才不用写桩）",
            code.contains("val hud: CarHudStatus = CarHudStatus(),"),
        )
    }

    @Test
    fun `设置页允许预先保存原车仪表卡片开关并独立报告可用性`() {
        val code = codeOf(settingsScreen)
        assertTrue(
            "ClusterSection 必须真的调用它（定义了不调用等于没做）",
            code.contains("ClusterHudGroup(draft = draft, update = update, model = model)"),
        )
        assertTrue("必须有这一组的实现", code.contains("private fun ClusterHudGroup("))
        assertTrue(
            "开关必须写回 draft（否则拨了不生效）",
            code.contains("update(draft.copy(hudGuidanceEnabled = it))"),
        )
        assertTrue(
            "偏好保存不能被尚未探测的supported状态阻止",
            !code.contains("enabled = hud.supported") && code.contains("当前未报告原车仪表卡片接口"),
        )
        assertTrue(
            "排障信息要给出「收到 / 去重 / 受理」三个数",
            code.contains("hud.receivedFrames") && code.contains("hud.droppedFrames") &&
                code.contains("hud.pushedFrames"),
        )
    }
}
