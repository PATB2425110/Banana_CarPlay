package com.dbtool.carplay.geely

import com.dbtool.carplay.api.display.CarClusterFullMapPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GeelyClusterModeSession` 纯 JVM 单测（虚拟时钟，不依赖真机 / Robolectric）。
 */
class GeelyClusterModeSessionTest {

    private class Fixture {
        var canAcquire = true
        val scheduler = FakeClusterScheduler()
        val dim = FakeNaviModeController()
        val store = MemoryJournalStore()
        val journal = GeelyClusterRestoreJournal(store)
        val logs = mutableListOf<String>()
        val session = GeelyClusterModeSession(
            dim = dim,
            journal = journal,
            scheduler = scheduler,
            diagnostics = { logs += it },
            now = { scheduler.currentTime },
            canAcquire = { canAcquire },
        )

        /** 走到 ACTIVE：原厂读数 1 → 进入后回读 3 → 首帧。 */
        fun toActive(displayId: Int = 3) {
            dim.modes.add(1)
            dim.modes.add(3)
            session.setDisplayId(displayId)
            session.acquire("attach")
            scheduler.advanceBy(200)
            session.onFirstFrame()
        }
    }

    @Test
    fun `acquire then first frame becomes active`() {
        val f = Fixture()
        f.dim.modes.add(1)
        f.dim.modes.add(3)
        f.session.setDisplayId(3)

        f.session.acquire("attach")
        assertEquals(CarClusterFullMapPhase.ACQUIRING, f.session.state().phase)

        f.scheduler.advanceBy(200)
        assertEquals(CarClusterFullMapPhase.WAITING_FRAME, f.session.state().phase)
        assertTrue(f.session.state().modeVerified)

        f.session.onFirstFrame()
        assertEquals(CarClusterFullMapPhase.ACTIVE, f.session.state().phase)

        assertNotNull(f.journal.pending())
        assertTrue(f.logs.contains("Cluster: fullmap acquire begin reason=attach"))
        assertTrue(f.logs.contains("Cluster: factory full-map mode entered=true verifiedBy=poll"))
        assertTrue(f.logs.contains("Cluster: fullmap active displayId=3"))
    }

    @Test
    fun `stable external off cancels pending recovery without touching factory mode`() {
        val f = Fixture()
        f.toActive()
        f.dim.modes.addAll(listOf(1, 1, 1))
        f.session.reopen("test-pending-recovery")
        val enterCalls = f.dim.enterCalls
        val exitCalls = f.dim.exitCalls
        f.session.acceptExternalModeOff()
        f.scheduler.advanceBy(10_000)
        assertEquals(enterCalls, f.dim.enterCalls)
        assertEquals(exitCalls, f.dim.exitCalls)
        assertEquals(CarClusterFullMapPhase.RESTORED, f.session.state().phase)
        assertNull(f.journal.pending())
        f.session.reopen("late-recovery")
        assertEquals(enterCalls, f.dim.enterCalls)
    }

    @Test
    fun `manual close blocks already scheduled enter retries`() {
        val f = Fixture()
        f.dim.enterResults.add(false)
        f.session.acquire("attach")
        assertEquals(1, f.dim.enterCalls)
        f.canAcquire = false
        f.scheduler.advanceBy(10_000)
        assertEquals(1, f.dim.enterCalls)
        f.canAcquire = true
        f.dim.modes.addAll(listOf(1, 3))
        f.session.acquire("manual-open")
        assertEquals(2, f.dim.enterCalls)
    }

    @Test
    fun `readback returns off first then full map retries until verified`() {
        val f = Fixture()
        // 原厂读数 → 首次回读 1 → 再回读 1（触发补发 enter）→ 回读 3。
        f.dim.modes.addAll(listOf(1, 1, 1, 3))
        f.session.setDisplayId(3)

        f.session.acquire("attach")
        f.scheduler.advanceBy(600)

        assertEquals(CarClusterFullMapPhase.WAITING_FRAME, f.session.state().phase)
        assertEquals(2, f.dim.enterCalls)
        assertTrue(f.session.state().modeVerified)
    }

    @Test
    fun `unreadable mode is accepted as readback after three polls`() {
        val f = Fixture()
        f.dim.modes.addAll(listOf(1, null, null, null))
        f.session.setDisplayId(3)

        f.session.acquire("attach")
        f.scheduler.advanceBy(600)

        assertEquals(CarClusterFullMapPhase.WAITING_FRAME, f.session.state().phase)
        assertFalse(f.session.state().modeVerified)
        assertTrue(f.logs.contains("Cluster: factory full-map mode entered=true verifiedBy=readback"))
    }

    @Test
    fun `enter call failing three times ends failed but still returns to off`() {
        val f = Fixture()
        f.dim.modes.add(1)
        f.dim.enterResults.addAll(listOf(false, false, false))
        var done: Boolean? = null

        f.session.acquire("attach") { done = it }
        f.scheduler.advanceBy(800)

        assertEquals(3, f.dim.enterCalls)
        assertEquals(CarClusterFullMapPhase.FAILED, f.session.state().phase)
        assertEquals(false, done)
        assertTrue(f.logs.contains("Cluster: factory full-map mode entered=false verifiedBy=poll"))
        assertEquals(1, f.dim.exitCalls)
        assertNull(f.journal.pending())
    }

    @Test
    fun `first frame timeout releases mode and closes window`() {
        val f = Fixture()
        f.dim.modes.addAll(listOf(1, 3))
        var rollbacks = 0
        f.session.onFirstFrameTimeout = { rollbacks += 1 }
        f.session.setDisplayId(3)

        f.session.acquire("attach")
        f.scheduler.advanceBy(200 + 5_000)

        assertEquals(1, rollbacks)
        assertEquals(1, f.dim.exitCalls)
        assertEquals(CarClusterFullMapPhase.RESTORED, f.session.state().phase)
        assertTrue(f.logs.contains("Cluster: fullmap waiting first frame timeoutMs=5000"))
        assertTrue(f.logs.contains("Cluster: fullmap release begin reason=first-frame-timeout"))
        assertNull(f.journal.pending())
    }

    @Test
    fun `key frame requester fires halfway to first frame`() {
        val f = Fixture()
        f.dim.modes.addAll(listOf(1, 3))
        var keyFrames = 0
        f.session.setKeyFrameRequester {
            keyFrames += 1
            true
        }

        f.session.acquire("attach")
        f.scheduler.advanceBy(200 + 2_500)

        assertEquals(1, keyFrames)
    }

    @Test
    fun `release verified clears journal and lands restored`() {
        val f = Fixture()
        f.toActive()

        var done: Boolean? = null
        f.session.release("stop") { done = it }

        assertEquals(CarClusterFullMapPhase.RESTORED, f.session.state().phase)
        assertEquals(true, done)
        assertNull(f.journal.pending())
        assertEquals(1, f.dim.exitCalls)
        assertTrue(f.logs.contains("Cluster: fullmap release begin reason=stop"))
        assertTrue(f.logs.contains("Cluster: full-map mode exited verified=true"))
        assertTrue(f.logs.contains("Cluster: explicit task exit restoration=ok"))
    }

    @Test
    fun `release readback failure keeps journal for next boot`() {
        val f = Fixture()
        f.toActive()
        f.dim.defaultMode = 3

        f.session.release("stop")
        f.scheduler.advanceBy(6_000)

        assertEquals(CarClusterFullMapPhase.RETRY_PENDING, f.session.state().phase)
        assertNotNull(f.journal.pending())
        assertEquals(3, f.dim.exitCalls)
        assertTrue(f.logs.contains("Cluster: full-map mode exited verified=false"))
        assertTrue(
            f.logs.any { it.startsWith("Cluster: restore failed; journal persisted retryAt=") },
        )
        assertTrue(f.logs.contains("Cluster: explicit task exit restoration=failed"))
    }

    @Test
    fun `reopen succeeds after factory takes screen back`() {
        val f = Fixture()
        f.toActive()
        f.dim.modes.addAll(listOf(1, 1, 3))

        var done: Boolean? = null
        f.session.reopen("poll") { done = it }
        f.scheduler.advanceBy(600)

        assertEquals(CarClusterFullMapPhase.WAITING_FRAME, f.session.state().phase)
        assertEquals(true, done)
        assertTrue(f.logs.contains("Cluster: factory menu closed full-map mode (source=poll)"))
        assertTrue(
            f.logs.contains("Cluster: manual reopen sent; waiting for a new video frame attempt=1"),
        )

        f.session.onFirstFrame()
        assertEquals(CarClusterFullMapPhase.ACTIVE, f.session.state().phase)
    }

    @Test
    fun `reopen failing three times ends failed`() {
        val f = Fixture()
        f.toActive()
        f.dim.defaultMode = 1

        f.session.reopen("poll")
        f.scheduler.advanceBy(9_000)

        assertEquals(CarClusterFullMapPhase.FAILED, f.session.state().phase)
        val failures = f.logs.filter {
            it.startsWith("Cluster: manual reopen failed; factory map stays off attempt=")
        }
        assertEquals(3, failures.size)
        assertTrue(failures.last().endsWith("attempt=3"))
    }

    @Test
    fun `restoreIfLeftOn without journal record does nothing`() {
        val f = Fixture()
        var done: Boolean? = null

        f.session.restoreIfLeftOn { done = it }

        assertEquals(true, done)
        assertEquals(0, f.dim.enterCalls)
        assertEquals(0, f.dim.exitCalls)
        assertEquals(0, f.dim.readCalls)
        assertEquals(CarClusterFullMapPhase.IDLE, f.session.state().phase)
    }

    @Test
    fun `restoreIfLeftOn with pending record returns factory mode to off`() {
        val f = Fixture()
        f.journal.markAcquireIntent(displayId = 3, originalMode = 1, nowElapsedMs = 0L)
        f.dim.modes.add(3)

        f.session.restoreIfLeftOn()

        assertEquals(CarClusterFullMapPhase.RESTORED, f.session.state().phase)
        assertEquals(1, f.dim.exitCalls)
        assertNull(f.journal.pending())
        assertTrue(f.logs.contains("Cluster: explicit task exit restoration=ok"))
    }

    @Test
    fun `restoreIfLeftOn failure keeps journal`() {
        val f = Fixture()
        f.journal.markAcquireIntent(displayId = 3, originalMode = 1, nowElapsedMs = 0L)
        f.dim.defaultMode = 3

        f.session.restoreIfLeftOn()
        f.scheduler.advanceBy(6_000)

        assertEquals(CarClusterFullMapPhase.RETRY_PENDING, f.session.state().phase)
        assertNotNull(f.journal.pending())
        assertTrue(f.logs.contains("Cluster: explicit task exit restoration=failed"))
    }

    @Test
    fun `unsupported vendor marks unsupported without calling switch`() {
        val f = Fixture()
        f.dim.available = false

        f.session.acquire("attach")

        assertEquals(CarClusterFullMapPhase.UNSUPPORTED, f.session.state().phase)
        assertEquals(0, f.dim.enterCalls)
        assertNull(f.journal.pending())
        assertTrue(f.logs.contains("Cluster: fullmap unsupported vendor=absent"))
    }

    @Test
    fun `state elapsed follows injected clock`() {
        val f = Fixture()
        f.dim.modes.addAll(listOf(1, 3))

        f.session.acquire("attach")
        f.scheduler.advanceBy(200)

        assertEquals(200L, f.session.state().sinceElapsedMs)
    }

    // ------------------------------------------------------- 事件记录（阶段 3）

    /** 当前展示用事件序列（旧→新）。 */
    private fun kinds(f: Fixture): List<String> =
        f.session.state().events.map { it.kind }

    @Test
    fun `进入成功记录请求接管与回读确认`() {
        val f = Fixture()
        f.toActive()

        val kinds = kinds(f)
        val acquire = kinds.indexOf("ACQUIRE_SENT")
        val verified = kinds.indexOf("ENTER_VERIFIED")
        assertTrue("缺少 ACQUIRE_SENT：$kinds", acquire >= 0)
        assertTrue("缺少 ENTER_VERIFIED：$kinds", verified >= 0)
        assertEquals("两条事件必须相邻且按旧→新", acquire + 1, verified)
        assertTrue("首帧事件缺失：$kinds", kinds.contains("FIRST_FRAME"))
    }

    @Test
    fun `首帧超时记录回滚事件`() {
        val f = Fixture()
        f.dim.modes.addAll(listOf(1, 3))
        f.session.setDisplayId(3)

        f.session.acquire("attach")
        f.scheduler.advanceBy(200 + 5_000)

        val kinds = kinds(f)
        val timeout = kinds.indexOf("FIRST_FRAME_TIMEOUT")
        assertTrue("缺少 FIRST_FRAME_TIMEOUT：$kinds", timeout >= 0)
        assertTrue("超时后应发还原：$kinds", kinds.drop(timeout).contains("RELEASE_SENT"))
    }

    @Test
    fun `被收回后重开成功记录收回与重开成功`() {
        val f = Fixture()
        f.toActive()
        f.dim.modes.addAll(listOf(1, 1, 3))

        f.session.reopen("poll")
        f.scheduler.advanceBy(600)

        val kinds = kinds(f)
        val taken = kinds.indexOf("TAKEN_BACK")
        val reopened = kinds.indexOf("REOPEN_OK")
        assertTrue("缺少 TAKEN_BACK：$kinds", taken >= 0)
        assertTrue("缺少 REOPEN_OK：$kinds", reopened >= 0)
        // 中间可能夹着「回读未确认，正在重试」，只要求先后顺序。
        assertTrue("TAKEN_BACK 应在 REOPEN_OK 之前：$kinds", taken < reopened)
    }

    @Test
    fun `还原记录请求与确认`() {
        val f = Fixture()
        f.toActive()

        f.session.release("stop")

        val kinds = kinds(f)
        val sent = kinds.indexOf("RELEASE_SENT")
        val verified = kinds.indexOf("RELEASE_VERIFIED")
        assertTrue("缺少 RELEASE_SENT：$kinds", sent >= 0)
        assertTrue("缺少 RELEASE_VERIFIED：$kinds", verified >= 0)
        assertEquals("请求与确认应相邻", sent + 1, verified)
    }

    @Test
    fun `守护关闭触发的还原记录专门事件`() {
        val f = Fixture()
        f.toActive()

        f.session.release("guard-disabled")

        val kinds = kinds(f)
        val disabled = kinds.indexOf("GUARD_DISABLED")
        assertTrue("缺少 GUARD_DISABLED：$kinds", disabled >= 0)
        assertEquals("RELEASE_SENT", kinds[disabled + 1])
        assertEquals("RELEASE_VERIFIED", kinds.last())
    }

    @Test
    fun `启动补还原记录专门事件`() {
        val f = Fixture()
        f.journal.markAcquireIntent(displayId = 3, originalMode = 1, nowElapsedMs = 0L)
        f.dim.modes.add(3)

        f.session.restoreIfLeftOn()

        val kinds = kinds(f)
        assertEquals("RESTORE_ON_BOOT", kinds.first())
        assertTrue("补还原应发 switchNaviMode(1)：$kinds", kinds.contains("RELEASE_SENT"))
        assertTrue("补还原应确认：$kinds", kinds.contains("RELEASE_VERIFIED"))
    }

    @Test
    fun `事件环形缓冲只保留最近三十条`() {
        val f = Fixture()
        // 每轮 5 条：ACQUIRE_SENT / ENTER_VERIFIED / FIRST_FRAME / RELEASE_SENT / RELEASE_VERIFIED。
        repeat(7) {
            f.dim.modes.addAll(listOf(1, 3))
            f.session.setDisplayId(3)
            f.session.acquire("cycle")
            f.scheduler.advanceBy(200)
            f.session.onFirstFrame()
            f.session.release("cycle")
            f.scheduler.advanceBy(200)
        }

        val events = f.session.state().events
        assertEquals(30, events.size)
        // 7 轮共 35 条，最旧一轮的 5 条被丢弃 ⇒ 只剩 6 条 ACQUIRE_SENT。
        assertEquals(6, events.count { it.kind == "ACQUIRE_SENT" })
        assertEquals("RELEASE_VERIFIED", events.last().kind)
        assertTrue("时间戳应非递减", events.zipWithNext().all { (a, b) -> a.atElapsedMs <= b.atElapsedMs })
    }
}
