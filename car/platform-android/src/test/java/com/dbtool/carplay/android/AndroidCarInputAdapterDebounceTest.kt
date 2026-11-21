package com.dbtool.carplay.android

import com.dbtool.carplay.api.input.CarInputEvent
import com.dbtool.carplay.api.input.CarInputListener
import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AndroidCarInputAdapter.dispatch] 的产线去抖行为。
 *
 * 实车一次物理按压会被车机推两条 DOWN（间隔 123~330ms）。这里用假时钟钉死
 * 350ms 窗口：同键重复被挡、不同键互不影响、UP 不受影响、长按连发被丢弃。
 */
class AndroidCarInputAdapterDebounceTest {

    private class Recorder : CarInputListener {
        val accepted = mutableListOf<CarInputEvent>()
        override fun onInputEvent(event: CarInputEvent): Boolean {
            accepted.add(event)
            return true
        }
    }

    /** 可变假时钟。 */
    private class FakeClock(var now: Long = 1_000L)

    /** 用假时钟驱动 [clock]，并把受保护的 dispatch 暴露给测试。 */
    private class TestAdapter(private val fakeClock: FakeClock) :
        AndroidCarInputAdapter(clock = { fakeClock.now }) {
        fun feed(
            key: CarKey,
            action: CarKeyAction,
            repeatCount: Int = 0,
            source: CarInputSource = CarInputSource.STEERING_WHEEL,
        ): Boolean = dispatch(
            CarInputEvent(
                key = key,
                action = action,
                source = source,
                timestampMillis = fakeClock.now,
                repeatCount = repeatCount,
            ),
        )
    }

    private fun adapterWith(recorder: Recorder, clock: FakeClock = FakeClock()): TestAdapter {
        return TestAdapter(clock).also { it.attach(recorder) }
    }

    @Test
    fun `同键 200ms 内的第二条 DOWN 被丢弃但仍算已消费`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)

        assertTrue(adapter.feed(CarKey.NEXT, CarKeyAction.DOWN))
        clock.now += 200L
        assertTrue("被去抖的 DOWN 仍须返回 true，避免漏给系统", adapter.feed(CarKey.NEXT, CarKeyAction.DOWN))

        assertEquals(1, recorder.accepted.size)
        assertEquals(CarKey.NEXT, recorder.accepted.single().key)
    }

    @Test
    fun `同键 400ms 后的 DOWN 正常分发`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)

        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)
        clock.now += 400L
        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)

        assertEquals(2, recorder.accepted.size)
    }

    @Test
    fun `不同键的去抖窗口互相独立`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)

        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)
        clock.now += 50L
        adapter.feed(CarKey.PREVIOUS, CarKeyAction.DOWN)

        assertEquals(2, recorder.accepted.size)
        assertEquals(listOf(CarKey.NEXT, CarKey.PREVIOUS), recorder.accepted.map { it.key })
    }

    @Test
    fun `UP 不参与去抖判断`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)

        adapter.feed(CarKey.PLAY_PAUSE, CarKeyAction.DOWN)
        clock.now += 10L
        adapter.feed(CarKey.PLAY_PAUSE, CarKeyAction.UP)
        clock.now += 10L
        adapter.feed(CarKey.PLAY_PAUSE, CarKeyAction.UP)

        assertEquals(
            listOf(CarKeyAction.DOWN, CarKeyAction.UP, CarKeyAction.UP),
            recorder.accepted.map { it.action },
        )
    }

    @Test
    fun `MULTIPLE 不参与去抖判断`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)

        adapter.feed(CarKey.NEXT, CarKeyAction.MULTIPLE)
        clock.now += 10L
        adapter.feed(CarKey.NEXT, CarKeyAction.MULTIPLE)

        assertEquals(2, recorder.accepted.size)
        assertEquals(listOf(CarKeyAction.MULTIPLE, CarKeyAction.MULTIPLE), recorder.accepted.map { it.action })
    }

    @Test
    fun `repeatCount 大于 0 的 DOWN 直接丢弃`() {
        val recorder = Recorder()
        val adapter = adapterWith(recorder)

        adapter.feed(CarKey.VOLUME_UP, CarKeyAction.DOWN, repeatCount = 3)

        assertEquals(0, recorder.accepted.size)
    }

    @Test
    fun `setDebounceMillis(0) 关闭抑制`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)
        adapter.setDebounceMillis(0)

        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)
        clock.now += 1L
        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)

        assertEquals(2, recorder.accepted.size)
    }

    @Test
    fun `reset 后同键可以立刻再次分发`() {
        val recorder = Recorder()
        val clock = FakeClock()
        val adapter = adapterWith(recorder, clock)

        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)
        clock.now += 10L
        adapter.reset()
        adapter.feed(CarKey.NEXT, CarKeyAction.DOWN)

        assertEquals(2, recorder.accepted.size)
    }
}
