package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction
import com.shilapi.xcertplay.airplay.AirPlayHid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarKeyHidMap] 的单测。
 *
 * 这张表是"方控按下去 iPhone 有没有反应"的唯一依据，而它**没有真机可依赖**——
 * Ecarx 方控要上真实吉利/领克车机才能验，媒体 index 还要一台 iPhone 配合。
 * 因此在能自动化的一侧把边界钉死：
 *
 * - 每个按键映射到哪个 index（防止以后"顺手改一下"改错）；
 * - 抬起动作绝不转发（错了就是一次按键跳两首，且真机上极难归因）；
 * - 映射出的 index 必须落在 upstream 描述符声明的范围内。
 */
class CarKeyHidMapTest {

    // ------------------------------------------------------------------ 媒体键

    @Test
    fun `媒体键映射到描述符里对应的 usage 下标`() {
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PREVIOUS_TRACK),
            CarKeyHidMap.intentFor(CarKey.PREVIOUS),
        )
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_NEXT_TRACK),
            CarKeyHidMap.intentFor(CarKey.NEXT),
        )
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PLAY_PAUSE),
            CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE),
        )
    }

    @Test
    fun `播放暂停用 0xCD 而不是单独的 Play 或 Pause`() {
        // 0x00CD (Play/Pause) 才是"一个键切换播放状态"的语义。
        // 若错用 [1] Play / [2] Pause，方控会变成"只会播、不会停"。
        val intent = CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE) as CarKeyHidMap.Intent.Media
        assertEquals(3, intent.index)
        assertEquals("0xCD 落在下标 3", CarKeyHidMap.MEDIA_PLAY_PAUSE, intent.index)
    }

    @Test
    fun `上一首与下一首的下标不能颠倒`() {
        val previous = CarKeyHidMap.intentFor(CarKey.PREVIOUS) as CarKeyHidMap.Intent.Media
        val next = CarKeyHidMap.intentFor(CarKey.NEXT) as CarKeyHidMap.Intent.Media
        assertEquals("上一首 = Scan Previous Track = 下标 5", 5, previous.index)
        assertEquals("下一首 = Scan Next Track = 下标 4", 4, next.index)
        assertTrue("两个下标必须不同", previous.index != next.index)
    }

    // ------------------------------------------------------------------ 非媒体键

    @Test
    fun `语音键不转发 Siri 留给原车`() {
        assertEquals(null, CarKeyHidMap.intentFor(CarKey.VOICE))
    }

    @Test
    fun `HOME 与 BACK 映射成旋钮状态`() {
        assertEquals(
            CarKeyHidMap.Intent.Knob(home = true),
            CarKeyHidMap.intentFor(CarKey.HOME),
        )
        assertEquals(
            CarKeyHidMap.Intent.Knob(back = true),
            CarKeyHidMap.intentFor(CarKey.BACK),
        )
    }

    @Test
    fun `音量与静音不映射——它们归车机功放管`() {
        // 返回 null 的语义是"不消费"，事件会交回车机/系统。
        // 若这里哪天被改成映射到某个媒体 index，车机上会出现"调音量结果跳歌"。
        assertNull(CarKeyHidMap.intentFor(CarKey.VOLUME_UP))
        assertNull(CarKeyHidMap.intentFor(CarKey.VOLUME_DOWN))
        assertNull(CarKeyHidMap.intentFor(CarKey.MUTE))
    }

    @Test
    fun `MENU 不映射——描述符里没有语义对得上的 usage`() {
        assertNull(CarKeyHidMap.intentFor(CarKey.MENU))
    }

    // ------------------------------------------------------------------ 动作门禁

    @Test
    fun `只转发按下，抬起与 MULTIPLE 一律不转发`() {
        assertTrue(CarKeyHidMap.shouldForward(CarKeyAction.DOWN))
        // MULTIPLE 是 Ecarx 对"既不是 DOWN 也不是 UP"的兜底，不代表新的按压。
        // 转发它会在一次物理按压里凭空多发一次媒体键（跳两首 / 暂停又播放）。
        assertFalse(CarKeyHidMap.shouldForward(CarKeyAction.MULTIPLE))
        assertFalse(CarKeyHidMap.shouldForward(CarKeyAction.UP))
    }

    @Test
    fun `抬起时 planFor 一律返回 null——否则一次按键会跳两首`() {
        // upstream sendMedia(index) 自己会补一次 0（抬起）。我们再把 UP 转过去，
        // iPhone 就会收到两个完整的按下/抬起周期。
        for (source in CarInputSource.entries) {
            for (key in CarKey.entries) {
                assertNull(
                    "$key 的 UP 不应转发（source=$source）",
                    CarKeyHidMap.planFor(key, CarKeyAction.UP, source),
                )
            }
        }
    }

    @Test
    fun `方控来源按下时 planFor 与 intentFor 结论一致`() {
        // 方向盘来源不受 HOME/BACK 门禁影响，应与纯映射完全一致。
        for (key in CarKey.entries) {
            assertEquals(
                "$key 的 DOWN 应等价于 intentFor",
                CarKeyHidMap.intentFor(key),
                CarKeyHidMap.planFor(key, CarKeyAction.DOWN, CarInputSource.STEERING_WHEEL),
            )
        }
    }

    // ------------------------------------------------ HOME / BACK 的来源门禁

    @Test
    fun `本机硬键 HOME 与 BACK 不转发——否则用户退不出 CarPlay 界面`() {
        // MainActivity.dispatchKeyEvent 把车机自己的硬键标注成 PANEL。
        // 这类事件一旦被转发并消费（forwardToCarPlay 返回 true ⇒ 不再流向
        // super.dispatchKeyEvent），返回键就失效，用户被困在 CarPlay 画面里。
        for (source in listOf(CarInputSource.PANEL, CarInputSource.OTHER)) {
            assertNull(
                "HOME 在 source=$source 时不应转发",
                CarKeyHidMap.planFor(CarKey.HOME, CarKeyAction.DOWN, source),
            )
            assertNull(
                "BACK 在 source=$source 时不应转发",
                CarKeyHidMap.planFor(CarKey.BACK, CarKeyAction.DOWN, source),
            )
        }
    }

    @Test
    fun `方控的 HOME 与 BACK 照常转发`() {
        // 方向盘上的 home/back 按钮本来就是给 CarPlay 用的，不能一起拦掉。
        assertEquals(
            CarKeyHidMap.Intent.Knob(home = true),
            CarKeyHidMap.planFor(CarKey.HOME, CarKeyAction.DOWN, CarInputSource.STEERING_WHEEL),
        )
        assertEquals(
            CarKeyHidMap.Intent.Knob(back = true),
            CarKeyHidMap.planFor(CarKey.BACK, CarKeyAction.DOWN, CarInputSource.STEERING_WHEEL),
        )
    }

    @Test
    fun `来源门禁只作用于 HOME 与 BACK，其余按键不受影响`() {
        // 防止以后有人把门禁写成"按来源整体拦截"，那会顺手把媒体键也打死。
        val gated = setOf(CarKey.HOME, CarKey.BACK)
        for (source in CarInputSource.entries) {
            for (key in CarKey.entries) {
                if (key in gated) continue
                assertEquals(
                    "$key 在 source=$source 时不该被来源门禁拦掉",
                    CarKeyHidMap.intentFor(key),
                    CarKeyHidMap.planFor(key, CarKeyAction.DOWN, source),
                )
            }
        }
    }

    // ----------------------------------------------------- 与 upstream 描述符对齐

    @Test
    fun `所有映射出的下标都落在描述符声明的 0 到 6 之间`() {
        // mediaDescriptor 声明 Logical Minimum 0 / Logical Maximum 6，
        // 即 Report 里合法取值只有 0..6。超出范围的 index 会被 iOS 当成非法报告丢弃，
        // 而且不会报错——正是最难查的那类故障。
        val used = CarKey.entries
            .mapNotNull { CarKeyHidMap.intentFor(it) }
            .filterIsInstance<CarKeyHidMap.Intent.Media>()
            .map { it.index }
        assertTrue("至少应有一个媒体键被映射", used.isNotEmpty())
        for (index in used) {
            assertTrue("index=$index 超出描述符范围 0..6", index in 0..6)
        }
    }

    @Test
    fun `upstream 的 mediaReport 就是把 index 原样写进单字节报告`() {
        // 这条断言把我们的常量钉在 upstream 的编码方式上：如果哪天 upstream 改成
        // 位域 / 多字节编码，这里会立刻红，而不是等到真机上"按键没反应"。
        assertEquals(1, AirPlayHid.mediaReport(0).size)
        assertEquals(1, AirPlayHid.mediaReport(CarKeyHidMap.MEDIA_PLAY_PAUSE).size)
        assertEquals(
            CarKeyHidMap.MEDIA_PLAY_PAUSE.toByte(),
            AirPlayHid.mediaReport(CarKeyHidMap.MEDIA_PLAY_PAUSE)[0],
        )
    }

    @Test
    fun `释放字节必须是 0——upstream 用它表示无按键`() {
        assertEquals(0, CarKeyHidMap.MEDIA_RELEASE)
        assertEquals(
            "upstream 补发的抬起就是 mediaReport(0)",
            0.toByte(),
            AirPlayHid.mediaReport(CarKeyHidMap.MEDIA_RELEASE)[0],
        )
    }

    // ------------------------------------------- 播放/暂停：必须按状态显式发 Play/Pause
    //
    // 实车（2026-09-29）：上一首/下一首正常，**播放/暂停键完全没反应**。
    // 三个媒体键走同一套 HID 通道，所以差别只在 usage：
    //   3 = 0x00CD Play/Pause(toggle) → iOS 不理会
    //   4 = 0x00B5 Scan Next / 5 = 0x00B6 Scan Previous → 正常
    // 解法照 DBTOOL2.0 的 sendPlayOrPauseCommand：读当前状态，显式发 Play 或 Pause。

    /** 正在播 ⇒ 这次按键的意图是"暂停"。 */
    @Test
    fun `在播时发 Pause`() {
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PAUSE),
            CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE, isPlaying = true),
        )
    }

    /** 没在播 ⇒ 这次按键的意图是"播放"。 */
    @Test
    fun `暂停时发 Play`() {
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PLAY),
            CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE, isPlaying = false),
        )
    }

    /**
     * ⚠️ 状态未知时**退回 toggle**（index 3），与改动前完全一致。
     *
     * 猜一个方向比不动作更糟：总是发 Play 会让"想暂停"变成"继续播"。
     */
    @Test
    fun `状态未知时退回 toggle`() {
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PLAY_PAUSE),
            CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE, isPlaying = null),
        )
        // 不传参数时等价于 null —— 老调用点的行为不变。
        assertEquals(
            CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE, isPlaying = null),
            CarKeyHidMap.intentFor(CarKey.PLAY_PAUSE),
        )
    }

    /** 三个 index 必须真的在描述符里（0xB0 / 0xB1 / 0xCD）。 */
    @Test
    fun `Play 与 Pause 的 index 与 toggle 互不相同`() {
        assertEquals(1, CarKeyHidMap.MEDIA_PLAY)
        assertEquals(2, CarKeyHidMap.MEDIA_PAUSE)
        assertEquals(3, CarKeyHidMap.MEDIA_PLAY_PAUSE)
        // 正在播放 ⇒ 用户想"暂停" ⇒ 发 Pause(2)；已暂停 ⇒ 想"播放" ⇒ 发 Play(1)。
        assertEquals(2, CarKeyHidMap.playPauseIndex(true))
        assertEquals(1, CarKeyHidMap.playPauseIndex(false))
        // 状态未知 ⇒ 退回 toggle(3)，与修改前行为一致。
        assertEquals(3, CarKeyHidMap.playPauseIndex(null))
    }

    /**
     * 🔴 反向断言：播放状态**只影响播放/暂停键**。
     *
     * 如果哪天有人把 isPlaying 也接进 NEXT/PREVIOUS 的映射，这条会立刻红——
     * 上一首/下一首是实车**已经可用**的路径，不能被这次修复带坏。
     */
    @Test
    fun `播放状态不影响上一首下一首`() {
        listOf(true, false, null).forEach { state ->
            assertEquals(
                CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_NEXT_TRACK),
                CarKeyHidMap.intentFor(CarKey.NEXT, isPlaying = state),
            )
            assertEquals(
                CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PREVIOUS_TRACK),
                CarKeyHidMap.intentFor(CarKey.PREVIOUS, isPlaying = state),
            )
        }
    }

    /** 策略层也要把状态透下去（否则上层传了也白传）。 */
    @Test
    fun `planFor 把播放状态透给播放暂停键`() {
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PAUSE),
            CarKeyHidMap.planFor(
                CarKey.PLAY_PAUSE,
                CarKeyAction.DOWN,
                CarInputSource.STEERING_WHEEL,
                isPlaying = true,
            ),
        )
        assertEquals(
            CarKeyHidMap.Intent.Media(CarKeyHidMap.MEDIA_PLAY),
            CarKeyHidMap.planFor(
                CarKey.PLAY_PAUSE,
                CarKeyAction.DOWN,
                CarInputSource.STEERING_WHEEL,
                isPlaying = false,
            ),
        )
    }
}
