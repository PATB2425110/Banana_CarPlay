package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧龄丢弃的门禁：**首帧提交前不丢帧**。
 *
 * 背景（2026-10-08，PATCH-0034）：`heldFrame` 构造 `VideoWorkQueue.Stamped` 时把
 * `enqueuedNs` 硬编码为 0，于是 `ageNs = System.nanoTime() - 0` 等于**开机时长**
 * （1.1.0 实测 `waited 396680ms`），必然越过 250ms → 攥在手里的首帧被无限期反复丢弃
 * → 解码器一帧都吃不到 → `waiting for random access` 死等 → 仪表副屏白块。
 *
 * 用例把「首帧前豁免」与「首帧后照常丢」两个方向都锁住。
 */
class StaleFrameDropGuardTest {

    private val maxAgeNs = 250_000_000L

    @Test
    fun firstFrameIsNeverDroppedEvenWhenAgeExceedsTheLimit() {
        assertFalse(
            shouldDropStaleFrame(
                submittedFirstFrame = false,
                ageNs = maxAgeNs + 1,
                maxAgeNs = maxAgeNs,
            ),
        )
    }

    @Test
    fun uptimeSizedAgeOnHeldFrameNoLongerTriggersTheDrop() {
        // 复现 1.1.0 现场：heldFrame 的 enqueuedNs 为 0 时 ageNs = 开机时长。
        assertFalse(
            shouldDropStaleFrame(
                submittedFirstFrame = false,
                ageNs = 396_680_000_000L,
                maxAgeNs = maxAgeNs,
            ),
        )
    }

    @Test
    fun staleFrameIsDroppedOnceTheFirstFrameWasSubmitted() {
        assertTrue(
            shouldDropStaleFrame(
                submittedFirstFrame = true,
                ageNs = maxAgeNs + 1,
                maxAgeNs = maxAgeNs,
            ),
        )
    }

    @Test
    fun freshFrameIsKeptAfterFirstFrame() {
        assertFalse(
            shouldDropStaleFrame(
                submittedFirstFrame = true,
                ageNs = maxAgeNs - 1,
                maxAgeNs = maxAgeNs,
            ),
        )
    }

    @Test
    fun boundaryIsExclusiveSoExactlyAtTheLimitIsKept() {
        assertFalse(shouldDropStaleFrame(true, maxAgeNs, maxAgeNs))
        assertFalse(shouldDropStaleFrame(false, maxAgeNs, maxAgeNs))
        assertTrue(shouldDropStaleFrame(true, maxAgeNs + 1, maxAgeNs))
    }

    @Test
    fun heldFrameUsesFreshTimestampSoZeroAgeIsKept() {
        // 修复后 heldFrame 用 System.nanoTime() 作 enqueuedNs，ageNs≈0。
        assertFalse(shouldDropStaleFrame(submittedFirstFrame = false, ageNs = 0L, maxAgeNs = maxAgeNs))
    }
}
