package com.dbtool.carplay.bridge.audio

import android.view.Surface
import android.media.AudioManager
import com.dbtool.carplay.bridge.CarPlayMediaSink
import com.shilapi.xcertplay.airplay.MediaSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarAudioFocusGuard] 的状态机单测。
 *
 * 这些用例跑在没有任何 Android 运行时的环境里：守卫必须在拿不到
 * `AudioManager` 时**优雅退化为空操作**，而不是抛异常把整条会话建链带崩。
 *
 * 同时也锁死本类的核心策略：**暂停不等于释放焦点**——
 * 未来若有人顺手在暂停回调里加了 `release()`，这里的用例应当失败。
 */
class CarAudioFocusGuardTest {

    /** Host sink records local gain; no Android AudioTrack/vehicle execution. */
    private class StubSink : CarPlayMediaSink {
        var gain = 1f
        var supported = true
        override fun setMediaOutputGain(gain: Float): Boolean { this.gain = gain; return supported }
        override val mediaSink: MediaSink = object : MediaSink {}

        override fun attachSurface(surface: Surface?) = Unit

        override fun setScreenStreamActiveListener(listener: (type: Int, active: Boolean) -> Unit) = Unit

        override fun close() = Unit
    }

    /** 不传 context = 拿不到 AudioManager，正是车机异常时的降级路径。 */
    private fun guard(logs: MutableList<String> = mutableListOf()) =
        CarAudioFocusGuard(context = null, sink = StubSink(), log = { logs += it })

    @Test
    fun `无 AudioManager 时 acquire 返回 false 且留下日志`() {
        val logs = mutableListOf<String>()
        val g = CarAudioFocusGuard(context = null, sink = StubSink(), log = { logs += it })

        assertFalse(g.acquire())
        assertFalse(g.holdsFocus)
        assertTrue("应记录降级原因，否则现场无迹可查", logs.any { it.contains("no AudioManager") })
    }

    @Test
    fun `无 AudioManager 时 release 幂等安全`() {
        val g = guard()
        g.release()
        g.release()
        assertFalse(g.holdsFocus)
        assertEquals("holdsFocus=false muted=false", g.describe())
    }

    @Test
    fun `acquire 可重复调用且不翻转状态`() {
        val g = guard()
        repeat(3) { assertFalse(g.acquire()) }
        assertFalse(g.holdsFocus)
    }

    @Test
    fun `describe 反映初始状态`() {
        assertEquals("holdsFocus=false muted=false", guard().describe())
    }

    @Test
    fun `日志出口收到申请尝试记录`() {
        val logs = mutableListOf<String>()
        CarAudioFocusGuard(context = null, sink = StubSink(), log = { logs += it }).acquire()
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `release 在从未 acquire 时不产生额外日志噪音`() {
        val logs = mutableListOf<String>()
        CarAudioFocusGuard(context = null, sink = StubSink(), log = { logs += it }).release()
        assertTrue("未持有时释放应静默", logs.isEmpty())
    }

    private fun focus(g: CarAudioFocusGuard, change: Int) {
        val field = CarAudioFocusGuard::class.java.getDeclaredField("listener").apply { isAccessible = true }
        (field.get(g) as AudioManager.OnAudioFocusChangeListener).onAudioFocusChange(change)
    }
    @Test fun `temporary focus loss reaches local sink before returning`() {
        val sink = StubSink(); val g = CarAudioFocusGuard(null, sink)
        focus(g, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0f, sink.gain); assertEquals("holdsFocus=false muted=true", g.describe())
    }
    @Test fun `gain restores only app sink and duck cannot undo loss`() {
        val sink = StubSink(); val g = CarAudioFocusGuard(null, sink)
        focus(g, AudioManager.AUDIOFOCUS_LOSS); focus(g, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0f, sink.gain)
        focus(g, AudioManager.AUDIOFOCUS_GAIN); assertEquals(1f, sink.gain)
        focus(g, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK); assertEquals(0.5f, sink.gain)
    }
    @Test fun `unsupported sink cannot log false success`() {
        val sink = StubSink().apply { supported = false }; val logs = mutableListOf<String>()
        focus(CarAudioFocusGuard(null, sink, { logs += it }), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(logs.any { it.contains("localApplied=false") })
    }
}
