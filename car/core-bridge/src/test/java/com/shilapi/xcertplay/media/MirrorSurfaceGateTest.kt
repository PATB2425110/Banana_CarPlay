package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HUD 中途挂载的核心回归（v2 方案评审 P1-A，2026-10-21）。
 *
 * ## 它钉的是什么
 *
 * DiPlay 的 `AndroidMediaSink.setMirrorSurface` 会在收到非空 Surface 时
 * **回放 `lastVideoConfig`**（`AndroidMediaSink.java:253-263`）。若只存 config
 * 不消费，HUD 在「手机已经发完 SPS/PPS 之后才挂上」时永远等不到配置，
 * 表现是一帧都不出 —— 而窗口、Surface、流状态在日志里全都「正常」。
 *
 * 本测试在 JVM 上钉死两个顺序：
 * 1. **先 Surface 后 config**：Surface 到达时状态 WAITING_CONFIG，config 到达后
 *    状态 CONFIG_READY 并给出 `[CONFIGURE, START]`；
 * 2. **先 config 后 Surface**（真正的中途挂载）：config 先到只记住，Surface 到达
 *    的那一刻**立即回放**，同样进入 CONFIG_READY。
 *
 * ⚠️ 单测只能证明「配置会下发、状态机进入可解码态」；真实首帧仍需实车验证
 * （`MediaCodec` 在 JVM 上不可用）。
 */
class MirrorSurfaceGateTest {

    private val config = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f)

    @Test
    fun `先 Surface 后 config 能进入 CONFIG_READY 并下发配置`() {
        val gate = MirrorSurfaceGate()

        assertEquals(MirrorSurfaceGate.State.WAITING_CONFIG, gate.state)
        assertTrue("Surface 先到、config 未到 ⇒ 不应下发任何动作", gate.attachSurface().isEmpty())
        assertEquals(MirrorSurfaceGate.State.WAITING_CONFIG, gate.state)

        val actions = gate.onConfig(VideoCodec.H264, config)

        assertEquals(
            listOf(MirrorSurfaceGate.Action.CONFIGURE, MirrorSurfaceGate.Action.START),
            actions,
        )
        assertEquals(MirrorSurfaceGate.State.CONFIG_READY, gate.state)
        assertTrue(gate.lastConfig!!.codecData.contentEquals(config))
    }

    @Test
    fun `先 config 后 Surface 时必须回放 lastVideoConfig（P1-A）`() {
        val gate = MirrorSurfaceGate()

        // 手机先发 SPS/PPS，此时 HUD 窗口还没挂上。
        assertTrue(gate.onConfig(VideoCodec.H265, config).isEmpty())
        assertEquals(MirrorSurfaceGate.State.WAITING_SURFACE, gate.state)

        // 窗口交出 Surface 的那一刻必须立即 configure + start。
        val actions = gate.attachSurface()

        assertEquals(
            "Surface 晚到但没有回放配置 ⇒ HUD 永远不出首帧",
            listOf(MirrorSurfaceGate.Action.CONFIGURE, MirrorSurfaceGate.Action.START),
            actions,
        )
        assertEquals(MirrorSurfaceGate.State.CONFIG_READY, gate.state)
        assertEquals(VideoCodec.H265, gate.lastConfig!!.codec)
    }

    @Test
    fun `Surface 销毁后保留 config_重新挂载仍能回放`() {
        val gate = MirrorSurfaceGate()
        gate.onConfig(VideoCodec.H264, config)
        gate.attachSurface()

        gate.detachSurface()
        assertEquals(MirrorSurfaceGate.State.WAITING_SURFACE, gate.state)

        val actions = gate.attachSurface()
        assertEquals(
            "窗口重建后同一会话的 SPS/PPS 仍然有效，不应等手机再发一次",
            listOf(MirrorSurfaceGate.Action.CONFIGURE, MirrorSurfaceGate.Action.START),
            actions,
        )
    }

    @Test
    fun `流停止时必须作废旧 config_新流要重新下发`() {
        val gate = MirrorSurfaceGate()
        gate.onConfig(VideoCodec.H264, config)
        gate.attachSurface()

        gate.invalidateConfig()

        assertEquals(MirrorSurfaceGate.State.WAITING_CONFIG, gate.state)
        assertNull("旧配置已作废，不能继续用于 configure", gate.lastConfig)
        assertTrue("新流配置到达前不应再下发动作", gate.attachSurface().isEmpty())
    }

    @Test
    fun `config 字节按快照保存_协议层复用缓冲不会污染`() {
        val gate = MirrorSurfaceGate()
        val mutable = config.copyOf()
        gate.onConfig(VideoCodec.H264, mutable)
        mutable[4] = 0x00

        assertTrue(
            "config 必须复制：协议层复用 ByteArray 时，延迟回放会拿到被改写的参数集",
            gate.lastConfig!!.codecData.contentEquals(config),
        )
    }

    @Test
    fun `reset 之后状态归零`() {
        val gate = MirrorSurfaceGate()
        gate.onConfig(VideoCodec.H264, config)
        gate.attachSurface()

        gate.reset()

        assertEquals(MirrorSurfaceGate.State.WAITING_CONFIG, gate.state)
        assertFalse(gate.isSurfaceAttached)
    }
}
