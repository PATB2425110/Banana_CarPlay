package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.CarClusterFullMapEventUi
import com.dbtool.carplay.ui.CarClusterFullMapUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 仪表「原厂全屏地图模式」文案映射（阶段 2，2026-10-18）。
 *
 * 这里钉的是**用户会读到的每一句话**：阶段名只作为代码里的键，
 * 不允许出现在返回值里。判断顺序（灰度 → 本机能力 → 阶段）也由这些用例固定：
 * 开关关着时哪怕阶段残留 ACTIVE，摘要也必须是「守护未启用」。
 */
class ClusterFullMapTextTest {

    /** 便于只写出与当前用例相关的字段。 */
    private fun snapshot(
        guardEnabled: Boolean = true,
        supported: Boolean = true,
        phase: String = "IDLE",
        lastError: String? = null,
        sinceElapsedMs: Long = 0L,
    ) = CarClusterFullMapUi(
        guardEnabled = guardEnabled,
        supported = supported,
        phase = phase,
        lastError = lastError,
        sinceElapsedMs = sinceElapsedMs,
    )

    @Test
    fun `守护未启用优先于任何阶段`() {
        val ui = snapshot(guardEnabled = false, phase = "ACTIVE")
        assertEquals("全屏模式 · 守护未启用", clusterFullMapSummary(ui))
        assertEquals(
            "守护未启用" to "灰度开关关闭时按旧路径投屏",
            clusterFullMapDetail(ui),
        )
    }

    @Test
    fun `本机不支持优先于阶段`() {
        val ui = snapshot(supported = false, phase = "UNSUPPORTED")
        assertEquals("全屏模式 · 本机不支持", clusterFullMapSummary(ui))
        assertEquals(
            "本机不支持" to "未找到车机的全屏地图接口，按原厂界面投屏",
            clusterFullMapDetail(ui),
        )
    }

    @Test
    fun `正在接管`() {
        val ui = snapshot(phase = "ACQUIRING")
        assertEquals("全屏模式 · 正在接管仪表…", clusterFullMapSummary(ui))
        assertEquals("正在接管仪表…" to null, clusterFullMapDetail(ui))
    }

    @Test
    fun `已接管等待画面`() {
        val ui = snapshot(phase = "WAITING_FRAME")
        assertEquals("全屏模式 · 已接管，等待画面…", clusterFullMapSummary(ui))
        assertEquals("已接管，等待画面…" to null, clusterFullMapDetail(ui))
    }

    @Test
    fun `已接管并报持续时长`() {
        val ui = snapshot(phase = "ACTIVE", sinceElapsedMs = 65_000L)
        assertEquals("全屏模式 · 已接管仪表", clusterFullMapSummary(ui))
        assertEquals("已接管仪表" to "已持续 65 秒", clusterFullMapDetail(ui))
    }

    @Test
    fun `画面被收回正在重开`() {
        val ui = snapshot(phase = "REOPENING")
        assertEquals("全屏模式 · 画面被车机收回，正在重开…", clusterFullMapSummary(ui))
        assertEquals("画面被车机收回，正在重开…" to null, clusterFullMapDetail(ui))
    }

    @Test
    fun `正在还原原厂仪表`() {
        val ui = snapshot(phase = "RELEASING")
        assertEquals("全屏模式 · 正在还原原厂仪表…", clusterFullMapSummary(ui))
        assertEquals("正在还原原厂仪表…" to null, clusterFullMapDetail(ui))
    }

    @Test
    fun `还原未成功带上失败原因`() {
        val ui = snapshot(phase = "RETRY_PENDING", lastError = "restore not verified")
        assertEquals("全屏模式 · 还原未成功，稍后重试", clusterFullMapSummary(ui))
        assertEquals(
            "还原未成功，稍后重试" to "restore not verified",
            clusterFullMapDetail(ui),
        )
    }

    @Test
    fun `已还原原厂仪表`() {
        val ui = snapshot(phase = "RESTORED")
        assertEquals("全屏模式 · 已还原原厂仪表", clusterFullMapSummary(ui))
        assertEquals("已还原原厂仪表" to null, clusterFullMapDetail(ui))
    }

    @Test
    fun `接管失败带上失败原因`() {
        val ui = snapshot(phase = "FAILED", lastError = "full-map acquire not verified")
        assertEquals("全屏模式 · 接管失败", clusterFullMapSummary(ui))
        assertEquals("接管失败" to "full-map acquire not verified", clusterFullMapDetail(ui))
    }

    @Test
    fun `空闲与未知阶段都说空闲`() {
        assertEquals("空闲" to null, clusterFullMapDetail(snapshot(phase = "IDLE")))
        assertEquals("全屏模式 · 空闲", clusterFullMapSummary(snapshot(phase = "IDLE")))
        // 将来状态机新增阶段而这里没跟上：宁可显示"空闲"，也不把英文枚举名怼给用户。
        assertEquals("空闲" to null, clusterFullMapDetail(snapshot(phase = "SOMETHING_NEW")))
    }

    @Test
    fun `持续不足一秒写不到一秒`() {
        assertEquals(
            "已接管仪表" to "已持续不到 1 秒",
            clusterFullMapDetail(snapshot(phase = "ACTIVE", sinceElapsedMs = 0L)),
        )
        assertEquals(
            "已接管仪表" to "已持续不到 1 秒",
            clusterFullMapDetail(snapshot(phase = "ACTIVE", sinceElapsedMs = 999L)),
        )
    }

    @Test
    fun `持续整秒向下取整`() {
        assertEquals(
            "已接管仪表" to "已持续 1 秒",
            clusterFullMapDetail(snapshot(phase = "ACTIVE", sinceElapsedMs = 1_000L)),
        )
        assertEquals(
            "已接管仪表" to "已持续 65 秒",
            clusterFullMapDetail(snapshot(phase = "ACTIVE", sinceElapsedMs = 65_999L)),
        )
    }

    @Test
    fun `默认快照是未启用不支持空闲`() {
        val ui = CarClusterFullMapUi()
        assertFalse(ui.guardEnabled)
        assertFalse(ui.supported)
        assertEquals("", ui.phase)
        assertNull(ui.lastError)
        assertEquals(0L, ui.sinceElapsedMs)
        // 默认（灰度也关着）= 未启用；补齐两个前提后，空阶段应落到「空闲」。
        assertEquals("全屏模式 · 守护未启用", clusterFullMapSummary(ui))
        assertEquals(
            "空闲" to null,
            clusterFullMapDetail(ui.copy(guardEnabled = true, supported = true)),
        )
    }

    // ------------------------------------------------------------ 事件文案（阶段 3）

    private fun event(
        kind: String,
        atElapsedMs: Long = 0L,
        detail: String? = null,
    ) = CarClusterFullMapEventUi(kind = kind, atElapsedMs = atElapsedMs, detail = detail)

    @Test
    fun `十三种事件都有中文文案`() {
        val expected = mapOf(
            "ACQUIRE_SENT" to "请求接管仪表",
            "ENTER_VERIFIED" to "车机已确认进入全屏地图",
            "ENTER_UNVERIFIED" to "进入未确认，正在重试",
            "FIRST_FRAME" to "收到第一帧画面",
            "FIRST_FRAME_TIMEOUT" to "等首帧超时，已回滚",
            "TAKEN_BACK" to "车机收回了仪表",
            "REOPEN_OK" to "重新接管成功",
            "REOPEN_FAILED" to "重新接管失败",
            "RELEASE_SENT" to "请求还原原厂仪表",
            "RELEASE_VERIFIED" to "已确认还原",
            "RELEASE_UNVERIFIED" to "还原未确认，稍后重试",
            "RESTORE_ON_BOOT" to "启动时补还原",
            "GUARD_DISABLED" to "守护关闭，主动还原",
        )
        assertEquals(13, expected.size)
        expected.forEach { (kind, text) ->
            assertEquals(kind, text, clusterFullMapEventText(event(kind)))
        }
    }

    @Test
    fun `事件文案不含英文标识`() {
        val kinds = listOf(
            "ACQUIRE_SENT",
            "ENTER_VERIFIED",
            "ENTER_UNVERIFIED",
            "FIRST_FRAME",
            "FIRST_FRAME_TIMEOUT",
            "TAKEN_BACK",
            "REOPEN_OK",
            "REOPEN_FAILED",
            "RELEASE_SENT",
            "RELEASE_VERIFIED",
            "RELEASE_UNVERIFIED",
            "RESTORE_ON_BOOT",
            "GUARD_DISABLED",
        )
        val forbidden = listOf(
            "ACQUIRE", "VERIFIED", "TIMEOUT", "SENT", "REOPEN",
            "RELEASE", "GUARD", "RESTORE", "TAKEN", "FRAME", "_",
        )
        kinds.forEach { kind ->
            val text = clusterFullMapEventText(event(kind))
            assertFalse("缺少文案：$kind", text.isNullOrBlank())
            forbidden.forEach { word ->
                assertFalse("「$text」不应含 $word（kind=$kind）", text!!.contains(word))
            }
        }
    }

    @Test
    fun `未知事件返回空由调用方跳过`() {
        assertNull(clusterFullMapEventText(event("SOMETHING_NEW")))
        assertNull(clusterFullMapEventText(event("")))
    }

    @Test
    fun `刚刚发生的事件显示零秒前`() {
        assertEquals("0 秒前", clusterFullMapEventAgo(event("FIRST_FRAME", atElapsedMs = 1_000L), 1_000L))
    }

    @Test
    fun `不足一分钟按秒显示`() {
        assertEquals("59 秒前", clusterFullMapEventAgo(event("FIRST_FRAME", atElapsedMs = 0L), 59_999L))
    }

    @Test
    fun `整六十秒仍按秒显示`() {
        assertEquals("60 秒前", clusterFullMapEventAgo(event("FIRST_FRAME", atElapsedMs = 0L), 60_000L))
    }

    @Test
    fun `超过六十秒进位到一分钟`() {
        assertEquals("1 分钟前", clusterFullMapEventAgo(event("FIRST_FRAME", atElapsedMs = 0L), 61_000L))
    }

    @Test
    fun `超过一分钟按分钟向下取整`() {
        assertEquals("2 分钟前", clusterFullMapEventAgo(event("FIRST_FRAME", atElapsedMs = 0L), 125_000L))
    }

    @Test
    fun `未来时间戳钳到零秒前`() {
        assertEquals("0 秒前", clusterFullMapEventAgo(event("FIRST_FRAME", atElapsedMs = 10_000L), 5_000L))
    }
}
