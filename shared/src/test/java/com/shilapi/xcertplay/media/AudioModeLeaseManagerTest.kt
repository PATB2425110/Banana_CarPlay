package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AudioModeLeaseManager` 的行为测试 —— 覆盖 **DBPlay PATCH-0007**。
 *
 * 上游原本在读回校验失败时直接抛异常，导致车机上的麦克风上行完全起不来。
 * 这里用一个假 AudioManager 把两种设备都模拟出来：
 *
 * - **普通设备**：`setMode` 生效 → 行为必须与上游原版**逐字一致**（本补丁不能改变它）；
 * - **钉死 mode 的车机**：`setMode` 不报错也不生效 → 必须**继续**，而不是抛异常。
 *
 * 之所以要测：车机（Android 9 / Flyme Auto）现场复现不了"接受 mode"这一侧，
 * 而"拒绝 mode"这一侧在手机上又复现不了 —— 只有把两边都钉死，才敢说补丁是对的。
 */
class AudioModeLeaseManagerTest {

    /**
     * 假的音频模式持有者。
     *
     * [acceptsWrites] = false 模拟车机：`setMode` 调用成功、计数 +1，但模式**不变**，
     * 于是读回校验拿不到通信模式（实测现象就是这个）。
     */
    private class FakeAudioModeHolder(
        initialMode: Int,
        private val acceptsWrites: Boolean = true,
    ) {
        var mode: Int = initialMode
            private set
        var writes: Int = 0
            private set

        fun read(): Int = mode

        fun write(value: Int) {
            writes++
            if (acceptsWrites) mode = value
        }
    }

    private fun managerFor(
        holder: FakeAudioModeHolder,
        onUnavailable: (Int) -> Unit = {},
    ) = AudioModeLeaseManager(
        readMode = holder::read,
        writeMode = holder::write,
        communicationMode = AudioManager.MODE_IN_COMMUNICATION,
        onCommunicationModeUnavailable = onUnavailable,
    )

    /**
     * 守卫：确认上面引用的框架常量确实是编译期常量。
     *
     * Kotlin 会把 Java 的 `public static final int` 内联成字面量；万一哪天不内联了，
     * 单元测试里的 android.jar 是桩实现、字段会退化成 0（= `MODE_NORMAL`），
     * 整个测试会**静默变成空转**。这一行就是用来在那种情况下大声失败的。
     */
    @Test
    fun frameworkConstantsAreInlinedNotStubbed() {
        assertEquals(0, AudioManager.MODE_NORMAL)
        assertEquals(3, AudioManager.MODE_IN_COMMUNICATION)
    }

    // ---------------------------------------------------------------- 普通设备

    @Test
    fun communicationModeIsEnabledAndRestoredOnClose() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_NORMAL)

        val lease = managerFor(holder).acquire()

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, holder.mode)
        assertEquals(1, holder.writes)

        lease.close()

        assertEquals(AudioManager.MODE_NORMAL, holder.mode)
        assertEquals(2, holder.writes)
    }

    @Test
    fun alreadyInCommunicationModeWritesNothing() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_IN_COMMUNICATION)

        val lease = managerFor(holder).acquire()
        assertEquals(0, holder.writes)

        lease.close()
        // changedMode == false ⇒ 关闭时不写回（否则会把别人的模式改掉）。
        assertEquals(0, holder.writes)
    }

    @Test
    fun nestedAcquiresWriteOnceAndRestoreAfterTheLastClose() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_NORMAL)
        val manager = managerFor(holder)

        val outer = manager.acquire()
        val inner = manager.acquire()
        assertEquals(1, holder.writes)

        inner.close()
        // 还有人持有租约 ⇒ 不能提前恢复。
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, holder.mode)
        assertEquals(1, holder.writes)

        outer.close()
        assertEquals(AudioManager.MODE_NORMAL, holder.mode)
        assertEquals(2, holder.writes)
    }

    @Test
    fun closingTwiceIsIgnored() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_NORMAL)
        val lease = managerFor(holder).acquire()

        lease.close()
        lease.close()

        assertEquals(2, holder.writes)
    }

    // ------------------------------------------------------- 钉死 mode 的车机

    /** PATCH-0007 的核心：读回失败**不再**抛异常，租约照常发出去。 */
    @Test
    fun refusedCommunicationModeStillReturnsALease() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_NORMAL, acceptsWrites = false)
        val reported = mutableListOf<Int>()
        val manager = managerFor(holder) { reported += it }

        val lease = manager.acquire()

        // 写过一次（尽力而为），模式没变（车机拒绝），但**没有抛异常**。
        assertEquals(1, holder.writes)
        assertEquals(AudioManager.MODE_NORMAL, holder.mode)
        // 而且必须把"实际拿到的模式"报出来 —— 这是现场唯一的诊断线索。
        assertEquals(listOf(AudioManager.MODE_NORMAL), reported)

        lease.close()
        // 模式从头到尾都不是通信模式 ⇒ 关闭时没有东西可恢复，不该再写。
        assertEquals(1, holder.writes)
        assertEquals(AudioManager.MODE_NORMAL, holder.mode)
    }

    /** 车机场景下反复 acquire/close 也不该崩 —— 现场是每次 iPhone 请求语音输入都会走一遍。 */
    @Test
    fun refusedCommunicationModeSurvivesRepeatedLeases() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_NORMAL, acceptsWrites = false)
        var reports = 0
        val manager = managerFor(holder) { reports++ }

        repeat(5) {
            manager.acquire().close()
        }

        assertEquals(5, holder.writes)
        assertEquals(5, reports)
        assertEquals(AudioManager.MODE_NORMAL, holder.mode)
    }

    /** 正常设备上**不该**触发上报 —— 否则现场会被无意义的警告淹没。 */
    @Test
    fun successfulCommunicationModeReportsNothing() {
        val holder = FakeAudioModeHolder(AudioManager.MODE_NORMAL)
        var reports = 0

        managerFor(holder) { reports++ }.acquire().close()

        assertEquals(0, reports)
    }
}
