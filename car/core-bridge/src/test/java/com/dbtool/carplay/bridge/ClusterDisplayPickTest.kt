package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.projection.ClusterDisplayFinder
import com.dbtool.carplay.api.projection.ClusterDisplayFinder.ClusterDisplayInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ClusterDisplayFinder.pickDisplayId] 单测 —— 仪表副屏「选哪块」的判据。
 *
 * ## 为什么这段必须钉死
 *
 * 2026-10-08 现场（吉利 CS11，VIN L6TTEST1111111112）暴露两类故障：
 *
 * 1. **静默选错屏**：`best()` 从系统演示屏里挑「面积最大」的那块，车上有多块
 *    非主屏时抓错 —— 窗口建在一块没内容的屏上，用户看到**白块**，日志里却是
 *    「窗口已建于 displayId=N」看着一切正常。
 * 2. **主屏被当成候选**：设置页候选清单误用了含主屏的 `all()`，用户一旦选中
 *    主屏，副屏窗口就开在**主屏**上（1920×720 白块盖住 CarPlay 主界面）。
 *
 * 判据现收敛为**两条硬条件**：
 *
 * 1. 非主屏（`!isDefault`）—— 主屏永远不是副屏；
 * 2. **用户明确指定**（`id == requestedId`）—— 未选就返回 -1。
 *
 * ⚠️ 2026-10-10 车主口径：**删掉了「未选 + 恰好一块候选 ⇒ 自动用它」那条兜底**
 * （原版 `GeelyDisplaySelector.select` 的 `singleOrNull` 语义）。原版假设仪表屏
 * 有且只有一块，这个前提在实车上不成立；车主明确要求「由用户自行选择，不要自动选」。
 * 对应地，本文件里「只有一块候选」的场景也必须返回 -1。
 *
 * 这种错法在开发机上永远复现不了（开发机只有一块屏），所以把判据在这里穷举掉。
 */
class ClusterDisplayPickTest {

    /** 现场两块屏：主屏(0) 1920×1080 / HDMI 副屏(3) 1024×274。 */
    private val realCarScreens = listOf(
        ClusterDisplayInfo(displayId = 0, width = 1920, height = 1080),
        ClusterDisplayInfo(displayId = 3, width = 1024, height = 274),
    )

    @Test
    fun `用户选定 id 存在时，必须原样返回该 id`() {
        assertEquals(3, ClusterDisplayFinder.pickDisplayId(3, realCarScreens))
    }

    @Test
    fun `用户选定的屏不在了，返回 -1 而不是退回自动挑`() {
        // HDMI 拔掉后 displayId=3 消失；此时绝不能自动挑一块别的填上（会把白块盖错屏）。
        val afterUnplug = realCarScreens.filter { it.displayId != 3 }
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(3, afterUnplug))
    }

    @Test
    fun `用户选中主屏时返回 -1 —— 主屏永远不是副屏候选`() {
        // 反编译原版 `!isDefault` 是硬条件。把主屏当副屏 = 在 CarPlay 主界面上开窗。
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(0, realCarScreens))
    }

    /**
     * 未选屏时**一块候选也不自动用**（2026-10-10 口径）。
     *
     * 车机上「检测到哪块屏」可以自动，**选哪块必须由人决定**。这条要是被改回
     * 「自动用唯一那块」，现场就又会回到"用户没选、画面自己跑出来"的状态 ——
     * 那正是「选错屏 / 白块」的温床。
     */
    @Test
    fun `未选屏时即使只有一块候选也不自动用`() {
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(-1, realCarScreens))
    }

    /** 未选屏 + 只有一块副屏候选（另一块是主屏）：仍然 -1。 */
    @Test
    fun `未选屏且只有一块副屏候选时仍返回 -1`() {
        val mainPlusOne = listOf(
            ClusterDisplayInfo(0, 1920, 1080),
            ClusterDisplayInfo(1, 1280, 720),
        )
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(-1, mainPlusOne))
    }

    @Test
    fun `未选屏且有多块非主屏时返回 -1 —— 不替用户猜`() {
        val screens = listOf(
            ClusterDisplayInfo(0, 1920, 1080),   // 主屏
            ClusterDisplayInfo(1, 1920, 1080),   // 副屏 A
            ClusterDisplayInfo(2, 1280, 720),    // 副屏 B
        )
        // 原版 `singleOrNull` 在命中多块时返回 null ⇒ 这里必须 -1（交给用户去选）。
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(-1, screens))
    }

    @Test
    fun `未选屏且只有主屏时返回 -1`() {
        val onlyMain = listOf(ClusterDisplayInfo(0, 1920, 1080))
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(-1, onlyMain))
    }

    @Test
    fun `一块屏都没有时返回 -1`() {
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(-1, emptyList()))
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(0, emptyList()))
    }

    @Test
    fun `选定 id 与主屏同值时也不认（主屏硬剔除优先于 id 命中）`() {
        // 双保险：即便调用方把 0 当"有效 id"传进来，主屏过滤也必须先把它剔掉。
        val screens = listOf(
            ClusterDisplayInfo(0, 1920, 1080),
            ClusterDisplayInfo(0, 1920, 1080),
        )
        assertEquals(-1, ClusterDisplayFinder.pickDisplayId(0, screens))
    }
}
