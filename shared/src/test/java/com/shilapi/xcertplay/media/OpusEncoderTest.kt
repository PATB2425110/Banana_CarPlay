package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `OpusEncoder` 的兜底与「进程内记忆」行为。
 *
 * 现场背景（VIN L6TTEST1111111112 / QUALCOMM CS11 / Android 9）：
 * 该车机的系统 Opus 编码器不存在，`MediaCodec.createEncoderByType("audio/opus")`
 * 抛 `Failed to initialize audio/opus, error 0xfffffffe`（ENOENT）。上游实现在**每次**
 * 建链时都重试一遍系统编码器 —— 白花一次 MediaCodec 创建，还每次刷一行 W。
 *
 * 2026-10-08 改为进程级记忆：首次仍真实尝试并如实告警，之后直接走软件后端。
 */
class OpusEncoderTest {

    @Before
    fun setUp() {
        OpusEncoder.resetSystemBackendAvailabilityForTest()
    }

    @After
    fun tearDown() {
        // 进程级状态会跨用例留存，跑完必须复位，否则后面的用例会被"没有系统编码器"污染。
        OpusEncoder.resetSystemBackendAvailabilityForTest()
    }

    /** 系统后端可用时优先用它，且**不**触发任何回退告警。 */
    @Test
    fun `系统后端可用时直接使用系统后端`() {
        val fallbacks = mutableListOf<String>()
        val system = FakeBackend("system")
        val encoder = OpusEncoder(
            sampleRate = 16_000,
            bitrate = 48_000,
            systemFactory = { _, _ -> system },
            softwareFactory = { _, _ -> FakeBackend("software") },
            onFallback = { fallbacks += it },
        )

        assertEquals("system", encoder.backendName)
        assertTrue("不应触发回退告警，实际：$fallbacks", fallbacks.isEmpty())
    }

    /** 系统后端创建失败 → 回退软件后端，并如实上报一次原因。 */
    @Test
    fun `系统后端不可用时回退软件后端并告警一次`() {
        val fallbacks = mutableListOf<String>()
        val encoder = OpusEncoder(
            sampleRate = 16_000,
            bitrate = 48_000,
            systemFactory = { _, _ -> error("Failed to initialize audio/opus, error 0xfffffffe") },
            softwareFactory = { _, _ -> FakeBackend("software") },
            onFallback = { fallbacks += it },
        )

        assertEquals("software", encoder.backendName)
        assertEquals(1, fallbacks.size)
        assertTrue(
            "告警要带上原始原因：$fallbacks",
            fallbacks.single().contains("0xfffffffe"),
        )
    }

    /**
     * 核心用例：一旦确认本机没有系统编码器，**第二个实例不再尝试** systemFactory。
     *
     * 这正是消除现场 10 行重复 W 与重复 MediaCodec 创建的机制。
     */
    @Test
    fun `记住系统后端不可用后不再重复尝试`() {
        var systemAttempts = 0
        val failingSystem: (Int, Int) -> OpusBackend = { _, _ ->
            systemAttempts++
            error("Failed to initialize audio/opus, error 0xfffffffe")
        }

        val first = OpusEncoder(
            sampleRate = 16_000,
            bitrate = 48_000,
            systemFactory = failingSystem,
            softwareFactory = { _, _ -> FakeBackend("software") },
        )
        assertEquals(1, systemAttempts)
        assertEquals("software", first.backendName)
        first.close()

        // 第二个实例：应当**直接**走软件后端，不再碰系统编码器。
        val second = OpusEncoder(
            sampleRate = 16_000,
            bitrate = 48_000,
            systemFactory = failingSystem,
            softwareFactory = { _, _ -> FakeBackend("software") },
        )
        assertEquals("第二个实例不应再尝试系统编码器", 1, systemAttempts)
        assertEquals("software", second.backendName)
        second.close()
    }

    /** 复位钩子必须真能把记忆清掉，否则跨用例会互相污染。 */
    @Test
    fun `复位后重新尝试系统后端`() {
        var systemAttempts = 0
        val failingSystem: (Int, Int) -> OpusBackend = { _, _ ->
            systemAttempts++
            error("boom")
        }

        OpusEncoder(
            sampleRate = 16_000,
            bitrate = 48_000,
            systemFactory = failingSystem,
            softwareFactory = { _, _ -> FakeBackend("software") },
        ).close()
        assertEquals(1, systemAttempts)

        OpusEncoder.resetSystemBackendAvailabilityForTest()

        OpusEncoder(
            sampleRate = 16_000,
            bitrate = 48_000,
            systemFactory = failingSystem,
            softwareFactory = { _, _ -> FakeBackend("software") },
        ).close()
        assertEquals("复位后应再次尝试", 2, systemAttempts)
    }

    private class FakeBackend(override val name: String) : OpusBackend {
        override fun encode(pcm: ByteArray): List<ByteArray> = listOf(pcm)
        override fun close() = Unit
    }
}
