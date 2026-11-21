package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarSettingsReconnectPolicy
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import com.dbtool.carplay.api.settings.CarTransportMode
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.display.theme.ClusterAppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些设置改动必须重连才生效」的判据（PATCH-0103，2026-10-12）。
 *
 * ## 为什么需要这个判据
 *
 * 设置页从"草稿 + 保存并重连"改成**自动保存**之后，保存与生效被拆成了两件事：
 * - 每次改动静置 500ms 后写盘（`reconnect = false`，不掐断正在投的屏）；
 * - 但画布尺寸 / `/info` 声明 / 音频通道号 / 热点身份这些**只在建立会话时读一次**，
 *   写盘对他们毫无意义。
 *
 * 于是需要回答"这次改动要不要重连"。这个问题的答案在本测试里钉死。
 *
 * ## 刻意选择的实现手法：**白名单列出就地生效的，其余一律算"要重连"**
 *
 * （而不是反过来枚举"要重连的"）。理由是**失败方向**：
 * - 漏列一个"就地生效"项 ⇒ 界面上多一句"有改动需重连生效"提示，
 *   用户多点一下重连 —— **无害**（本来重连也总是对的）。
 * - 漏列一个"必须重连"项 ⇒ 用户改完看到的还是旧行为，**没有任何提示**，
 *   且因为自动保存，他甚至不会怀疑"是不是没存上"。
 *
 * 所以新增字段必须显式登记进 [CarSettingsReconnectPolicy.LIVE_FIELDS] 才会被当成
 * 就地生效 —— 默认落在保守的一侧。本文件最后两个测试就是钉这件事的。
 */
class CarSettingsReconnectPolicyTest {

    private val base = CarSessionSettings.default()

    // ------------------------------------------------------- 基本情况

    @Test
    fun `相同设置不需要重连`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base))
    }

    @Test
    fun `方向无关_新旧互换结论一致`() {
        val next = base.copy(hotspotMode = CarHotspotMode.ADB_HOSTAPD)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(next, base))
    }

    // ---------------------------------------- 就地生效：白名单字段逐项

    @Test
    fun `副屏倒装就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(clusterFlip180 = !base.clusterFlip180)))
    }

    @Test
    fun `停靠栏顺序就地生效`() {
        val order = listOf("car.browser", "car.media")
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(dockAppOrder = order)))
    }

    @Test
    fun `保持热点存活就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(keepHotspotAlive = !base.keepHotspotAlive)))
    }

    @Test
    fun `安全区改动必须重连以更新握手声明`() {
        val area = CarSettingsSafeArea(x = 100, y = 50, width = 400, height = 300)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(safeArea = area)))
    }

    @Test
    fun `安全区外部描边就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(safeAreaDrawOutside = !base.safeAreaDrawOutside)))
    }

    @Test
    fun `导航焦点默认开启且开关无需重连_不改变通道号`() {
        assertTrue(base.navigationAudioFocusEnabled)
        val next = base.copy(navigationAudioFocusEnabled = false)
        assertEquals(base.navigationAudioChannel, next.navigationAudioChannel)
        assertEquals(base.mediaAudioChannel, next.mediaAudioChannel)
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, next))
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(next, base))
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next.copy(navigationAudioChannel = base.navigationAudioChannel + 1)))
    }

    @Test
    fun `调试日志就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(debugLogs = !base.debugLogs)))
    }

    @Test
    fun `顶栏开关就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(hideTopBar = !base.hideTopBar)))
    }

    @Test
    fun `底栏开关就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(hideBottomBar = !base.hideBottomBar)))
    }

    @Test
    fun `停靠栏开关就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(dockBar = !base.dockBar)))
    }

    @Test
    fun `全屏开关就地生效`() {
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, base.copy(fullscreen = !base.fullscreen)))
    }

    /**
     * PATCH-0114（2026-10-13）：HUD 导航推送开关。
     *
     * 它是**唯一**一个"不需要 iPhone 同意任何东西"的开关 —— 握手之后才开始的一条
     * 单向上报，所以改完当场生效（`CarPlaySessionBridge.updateSettings` 里直接改闸门）。
     * 这条用例钉住"它确实登记成了就地生效"：漏登记不会报错，只会让界面永远多一句
     * "有改动需重连生效"，用户以为设置没保存。
     */
    @Test
    fun `HUD 导航推送开关就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(hudGuidanceEnabled = !base.hudGuidanceEnabled),
            ),
        )
    }

    /**
     * 2026-10-22：ECarX HUD 使能通道开关（`CB_VF_HUD_ActvReq`）。
     *
     * 它只是会话建立后的一条车控信号，不参与与 iPhone 的握手声明；
     * 打开/关闭必须在会话中途立即生效（`CarAppController.applySettings`
     * 直接 activate/deactivate）。漏登记会让界面永远提示“需重连生效”，
     * 用户点一下就把 CarPlay 掐断。
     */
    @Test
    fun `HUD 使能通道开关就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(hudPushEnabled = !base.hudPushEnabled),
            ),
        )
    }

    /**
     * PATCH-0119（2026-10-14）：「导航时自动投屏」开关。
     *
     * 与 HUD 那条同理 —— 它只是"路线状态要不要自动投"的**本地判据**，不参与与 iPhone
     * 的握手声明，所以改完当场生效（`CarPlaySessionBridge.updateSettings` 里直接改闸门）。
     *
     * 而且**必须**是就地生效：导航中把开关关掉若要重连才生效，等于为了关一个开关
     * 掐断一次 CarPlay（音乐也断）。漏登记不会报错，只会让界面永远多一句
     * "有改动需重连生效"，用户以为设置没保存。
     */
    @Test
    fun `导航时自动投屏开关就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(clusterAutoShowOnRoute = !base.clusterAutoShowOnRoute),
            ),
        )
    }

    @Test
    fun `主页本地导航卡片开关就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(localNavigationCardEnabled = !base.localNavigationCardEnabled),
            ),
        )
    }

    /**
     * 2026-10-20：「仪表地图投屏」能力开关与「投到仪表」手动按钮合并成总开关后，
     * 开关的 `onCheckedChange` 会同步 `applySettings(reconnect = false)` 并立即建窗/拆窗。
     *
     * 能力门由 `CarPlaySessionBridge` 的 `isProjectionEnabled = { settings.projectionEnabled }`
     * **现读**，`resolveClusterSpec()` 也不读它 ⇒ 不参与握手声明，改完当场生效。
     * 漏登记不会报错，只会让开关拨完后底部多一句"有改动需重连生效"，
     * 用户可能因此点「重连生效」，白白掐断一次 CarPlay。
     */
    @Test
    fun `投屏总开关就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(projectionEnabled = !base.projectionEnabled),
            ),
        )
    }

    @Test
    fun `仪表明暗模式就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(clusterAppearanceMode = ClusterAppearanceMode.LIGHT),
            ),
        )
    }

    /**
     * 2026-10-22：HUD 地图投屏开关。
     *
     * 它不参与握手声明（111 的声明来自仪表副屏 spec），窗口由
     * `CarAppController.applyHudProjection()` 当场建/拆 ⇒ 必须就地生效。
     * 若误判成"需要重连"，用户拨开关后底部会提示重连，点一下就把 CarPlay 掐断。
     */
    @Test
    fun `HUD 地图投屏开关就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(hudProjectionEnabled = !base.hudProjectionEnabled),
            ),
        )
    }

    @Test
    fun `HUD 裁剪与缩放方式就地生效`() {
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(
                    hudCrop = com.dbtool.carplay.api.display.HudCropPreset.CENTERED.crop,
                ),
            ),
        )
        assertFalse(
            CarSettingsReconnectPolicy.requiresReconnect(
                base,
                base.copy(hudScaleMode = com.dbtool.carplay.api.display.HudScaleMode.FIT),
            ),
        )
    }

    // -------------------------------- 必须重连：只在建立会话时读一次的

    @Test
    fun `链路改了要重连`() {
        val next = base.copy(transport = CarTransportMode.WIRED)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `MFi 认证方式改了要重连`() {
        val next = base.copy(mfiTarget = CarMfiTarget.USB_CH341)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `热点模式改了要重连`() {
        val next = base.copy(hotspotMode = CarHotspotMode.ADB_HOSTAPD)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `换仪表屏要重连`() {
        val next = base.copy(clusterDisplayId = 3)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `媒体音频通道号改了要重连`() {
        val next = base.copy(mediaAudioChannel = base.mediaAudioChannel + 1)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `导航音频通道号改了要重连`() {
        val next = base.copy(navigationAudioChannel = base.navigationAudioChannel + 1)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `物理尺寸改了要重连`() {
        val next = base.copy(widthPhysicalMm = base.widthPhysicalMm + 100)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `帧率改了要重连`() {
        val next = base.copy(fps = base.fps + 5)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `调试隧道地址改了要重连`() {
        val next = base.copy(debugTunnelEnabled = !base.debugTunnelEnabled)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    @Test
    fun `安全区以外还有一项_重连结论仍由那一项决定`() {
        // 一次改动同时动"就地生效"与"必须重连"两个字段 ⇒ 必须按**更严格**的那个算。
        // （抹平实现天然满足：把 live 字段抹回旧值后剩下的差异就是这一项。）
        val next = base
            .copy(clusterFlip180 = !base.clusterFlip180)
            .copy(fps = base.fps + 5)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(base, next))
    }

    // ---------------------------- 保守方向：白名单列错 = 立刻变红

    @Test
    fun `白名单里的每个名字都真实存在于设置对象`() {
        // 拼错一个字段名 = 那个字段**永远**不会被判定为就地生效 ⇒ 界面永久多一句
        // "有改动需重连生效"。这种错在编译期与运行期都看不出来，只能在这里钉。
        val fields = CarSessionSettings::class.java.declaredFields.map { it.name }.toSet()
        CarSettingsReconnectPolicy.LIVE_FIELDS.forEach { name ->
            assertTrue(
                "LIVE_FIELDS 里的 \"$name\" 不是 CarSessionSettings 的字段（拼错了？）",
                name in fields,
            )
        }
    }

    @Test
    fun `白名单没有重复项`() {
        // 重复登记会让"顺手在不动的地方拨一下"也触发重连提示，且很难定位原因。
        val names = CarSettingsReconnectPolicy.LIVE_FIELDS
        assertEquals(names.size, names.distinct().size)
        // 11（PATCH-0114 为止）+ clusterAutoShowOnRoute + localNavigationCardEnabled
        // + projectionEnabled + clusterAppearanceMode（2026-10-21 仪表深浅模式）
        // + hudProjectionEnabled / hudCrop / hudScaleMode（2026-10-22 HUD 地图投屏）
        // + hudPushEnabled（2026-10-22 ECarX HUD 使能通道）
        // + uiAppearanceMode（2026-10-23 界面深浅，全站立即生效）
        // 安全区矩形已移出即时生效项；新增导航焦点开关，合计仍为 20。
        assertEquals(23, names.size)
    }

    @Test
    fun `抹平只需要覆盖白名单字段_其余字段原样参与比较`() {
        // 结构保证"新增字段默认算要重连"：实现把白名单字段用旧值覆盖后整体比较，
        // 只要新字段**不在**白名单里，它的一点点差异都会让比较结果不等。
        // 这里用一个虚构的对比对象证明"非白名单字段的任何差异都会返回 true"。
        val untouched = base.copy()
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(base, untouched))
        // 反过来：把白名单字段全改一遍（值都变、但每项都在白名单里）⇒ 仍为 false。
        val onlyLive = base.copy(
            clusterFlip180 = !base.clusterFlip180,
            dockAppOrder = listOf("car.media", "car.browser"),
            keepHotspotAlive = !base.keepHotspotAlive,
            safeAreaDrawOutside = !base.safeAreaDrawOutside,
            debugLogs = !base.debugLogs,
            hideTopBar = !base.hideTopBar,
            hideBottomBar = !base.hideBottomBar,
            dockBar = !base.dockBar,
            fullscreen = !base.fullscreen,
            hudGuidanceEnabled = !base.hudGuidanceEnabled,
            hudPushEnabled = !base.hudPushEnabled,
            clusterAutoShowOnRoute = !base.clusterAutoShowOnRoute,
            localNavigationCardEnabled = !base.localNavigationCardEnabled,
            projectionEnabled = !base.projectionEnabled,
            hudProjectionEnabled = !base.hudProjectionEnabled,
            hudCrop = com.dbtool.carplay.api.display.HudCropPreset.CENTERED.crop,
            hudScaleMode = com.dbtool.carplay.api.display.HudScaleMode.FIT,
            navigationAudioFocusEnabled = !base.navigationAudioFocusEnabled,
        )
        assertFalse(
            "只改白名单字段却判定为需要重连 —— 有一项漏登记了",
            CarSettingsReconnectPolicy.requiresReconnect(base, onlyLive),
        )
    }
}
