package com.dbtool.carplay.geely

import android.os.Handler
import android.os.SystemClock
import com.dbtool.carplay.api.display.CarClusterFullMapEvent
import com.dbtool.carplay.api.display.CarClusterFullMapPhase
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.ACQUIRING
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.ACTIVE
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.FAILED
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.IDLE
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.RELEASING
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.REOPENING
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.RESTORED
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.RETRY_PENDING
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.UNSUPPORTED
import com.dbtool.carplay.api.display.CarClusterFullMapPhase.WAITING_FRAME
import com.dbtool.carplay.api.display.CarClusterFullMapState

/**
 * 原厂全屏地图模式状态机。
 *
 * ## 它接管的三件事
 *
 * 1. **进入可回读**：记账 → `switchNaviMode(3)` → 轮询 `getNaviMode()`；
 *    回读不稳定时重试，2s 仍拿不到 3 就不假装成功。
 * 2. **首帧有超时**：进入后 5s 没画面 ⇒ 主动释放，避免把黑窗留在仪表上。
 * 3. **退出可还原**：只有回读确认 =1 才清账本；失败保留账本并后台重试，
 *    进程被杀后由 [restoreIfLeftOn] 补还原。
 *
 * ## 线程模型
 *
 * 所有方法都应在**主线程**调用（与现有 `GeelyClusterPresenter` / `Presentation`
 * 的约束一致）。定时任务通过 [GeelyClusterScheduler] 注入；真机用主线程
 * `Handler`，单测用虚拟时钟调度器，不需要 sleep。
 */
internal class GeelyClusterModeSession(
    private val dim: GeelyNaviModeController,
    private val journal: GeelyClusterRestoreJournal,
    private val scheduler: GeelyClusterScheduler,
    private val diagnostics: (String) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val canAcquire: () -> Boolean = { true },
) {
    /** 真机入口：定时任务全部跑在主线程 `Handler` 上。 */
    internal constructor(
        dim: GeelyNaviModeController,
        journal: GeelyClusterRestoreJournal,
        handler: Handler,
        diagnostics: (String) -> Unit,
        now: () -> Long = SystemClock::elapsedRealtime,
        canAcquire: () -> Boolean = { true },
    ) : this(dim, journal, HandlerClusterScheduler(handler), diagnostics, now, canAcquire)

    private var phase: CarClusterFullMapPhase = IDLE
    private var sinceElapsed = 0L
    private var modeVerified = false
    private var lastError: String? = null
    private var displayId = -1

    /** 代次号：每次新操作 +1，旧定时任务全部失效。 */
    private var generation = 0L
    private val timers = mutableListOf<() -> Unit>()

    /**
     * 状态变化记录（只用于展示，不参与任何判定）。
     *
     * 环形缓冲保留最近 [EVENT_HISTORY_LIMIT] 条；[state] 按旧→新返回。
     * 只在主线程读写（与本类其它状态一致）。
     */
    private val events = ArrayDeque<CarClusterFullMapEvent>()

    /** 首帧先于回读确认到达时先记下，进入 WAITING_FRAME 后立刻转 ACTIVE。 */
    private var pendingFirstFrame = false

    private var keyFrameRequester: (() -> Boolean)? = null
    private var enterAttempts = 0
    private var reopenAttempts = 0
    private var lastReopenAt = 0L

    /**
     * 首帧看门狗触发时的宿主回调（presenter 用来关窗）。
     *
     * 状态机自己负责回滚与日志；关窗必须由窗口 owner 做，避免本类反向依赖
     * `Presentation`。
     */
    var onFirstFrameTimeout: (() -> Unit)? = null

    /** 当前挂窗的 displayId（由 presenter 在 attach 时灌入）。 */
    fun setDisplayId(id: Int) {
        displayId = id
    }

    /** 首帧半程催 I 帧；null = 不催。 */
    fun setKeyFrameRequester(requester: (() -> Boolean)?) {
        keyFrameRequester = requester
    }

    /**
     * 进入全屏地图模式。
     *
     * @param onDone 状态机给出一次结论时回调；true = 已受理并进入等待首帧。
     */
    fun acquire(reason: String, onDone: (Boolean) -> Unit = {}) {
        resetForNewOperation()
        val token = generation
        sinceElapsed = now()

        if (!safeAvailable()) {
            phase = UNSUPPORTED
            lastError = "vendor absent"
            diagnostics("Cluster: fullmap unsupported vendor=absent")
            onDone(false)
            return
        }

        // 🔴 顺序不可交换：账本 commit 完成之后才允许切模式。
        val pending = journal.pending()
        // pending 存在但 originalMode 为 null（原厂模式当时读不到）时不要再读一次：
        // 此刻读到的可能是 3（我们上次已经切过去了），会污染「原厂模式」记录。
        val original = if (pending != null) pending.originalMode else safeRead()
        journal.markAcquireIntent(displayId, original, now())

        diagnostics("Cluster: fullmap acquire begin reason=$reason")
        record(EventKind.ACQUIRE_SENT)
        phase = ACQUIRING
        enterAttempts = 1
        val enteredAt = now()
        if (safeEnter()) {
            schedule(ACQUIRE_POLL_INTERVAL_MS, token) {
                pollEnter(token, isReopen = false, enteredAt, nullPolls = 0, retryIndex = 0, onDone)
            }
        } else {
            retryEnter(token, isReopen = false, nextAttempt = 2, onDone)
        }
    }

    /** 首帧到达（presenter 的 `onFirstFrame` 通知）。 */
    fun onFirstFrame() {
        when (phase) {
            ACQUIRING, REOPENING -> pendingFirstFrame = true
            WAITING_FRAME -> activate()
            else -> Unit
        }
    }

    /**
     * 原厂把模式收回（logcat / poll 命中 `naviMode:1`）后重开。
     *
     * 连续失败 3 次进入 [FAILED]：原厂保持 OFF，不再打成风暴。
     */
    fun reopen(reason: String, onDone: (Boolean) -> Unit = {}) {
        if (phase == REOPENING) return
        if (phase != ACTIVE && phase != WAITING_FRAME) {
            onDone(false)
            return
        }
        val t = now()
        if (lastReopenAt > 0 && t - lastReopenAt < REOPEN_MIN_INTERVAL_MS) {
            onDone(false)
            return
        }
        lastReopenAt = t
        reopenAttempts += 1
        record(EventKind.TAKEN_BACK, "第 $reopenAttempts 次重开")

        invalidate()
        val token = generation
        pendingFirstFrame = false
        phase = REOPENING
        diagnostics("Cluster: factory menu closed full-map mode (source=$reason)")

        enterAttempts = 1
        val enteredAt = now()
        if (safeEnter()) {
            schedule(ACQUIRE_POLL_INTERVAL_MS, token) {
                pollEnter(token, isReopen = true, enteredAt, nullPolls = 0, retryIndex = 0, onDone)
            }
        } else {
            retryEnter(token, isReopen = true, nextAttempt = 2, onDone)
        }
    }

    /**
     * 退出全屏地图模式并回读确认。
     *
     * 只有回读 =1 才清账本；失败保留给下次启动补还原。
     */
    fun release(reason: String, onDone: (Boolean) -> Unit = {}) {
        releaseInternal(reason, attempt = 1, successPhase = RESTORED, onDone = onDone)
    }

    /** OEM is already stably off: cancel recovery without issuing any vendor mode write. */
    fun acceptExternalModeOff() {
        invalidate()
        pendingFirstFrame = false
        journal.clear()
        phase = RESTORED
        modeVerified = true
        diagnostics("Cluster: stable external mode off accepted; recovery cancelled")
    }

    /**
     * 进程启动补还原：账本有记录才动作。
     *
     * 账本为空时**不调用任何厂商接口**、也不打业务日志（避免每次冷启动都噪声）。
     */
    fun restoreIfLeftOn(onDone: (Boolean) -> Unit = {}) {
        val pending = journal.pending()
        if (pending == null) {
            phase = IDLE
            onDone(true)
            return
        }

        resetForNewOperation()
        displayId = pending.displayId
        sinceElapsed = now()
        record(EventKind.RESTORE_ON_BOOT, "displayId=${pending.displayId}")

        // 已经是 1 ⇒ 上次其实还回去了（或车机自己复位），只补清账本。
        if (safeRead() == NAVI_MODE_OFF) {
            journal.clear()
            phase = RESTORED
            modeVerified = true
            record(EventKind.RELEASE_VERIFIED)
            diagnostics("Cluster: full-map mode exited verified=true")
            diagnostics("Cluster: explicit task exit restoration=ok")
            onDone(true)
            return
        }

        if (!safeAvailable()) {
            phase = UNSUPPORTED
            lastError = "vendor absent"
            diagnostics("Cluster: fullmap unsupported vendor=absent")
            onDone(false)
            return
        }
        releaseInternal("boot-restore", attempt = 1, successPhase = RESTORED, onDone = onDone)
    }

    /** 只读状态快照。 */
    fun state(): CarClusterFullMapState = CarClusterFullMapState(
        phase = phase,
        supported = safeAvailable(),
        modeVerified = modeVerified,
        lastError = lastError,
        sinceElapsedMs = if (phase == IDLE) 0L else (now() - sinceElapsed).coerceAtLeast(0L),
        events = events.toList(),
    )

    // ------------------------------------------------------------------ 进入

    private fun retryEnter(
        token: Long,
        isReopen: Boolean,
        nextAttempt: Int,
        onDone: (Boolean) -> Unit,
    ) {
        if (nextAttempt > ENTER_MAX_ATTEMPTS) {
            if (isReopen) failReopen(onDone) else failAcquire(token, onDone)
            return
        }
        enterAttempts = nextAttempt
        record(EventKind.ENTER_UNVERIFIED, "第 $nextAttempt 次尝试")
        schedule(ENTER_RETRY_DELAY_MS, token) {
            val enteredAt = now()
            if (safeEnter()) {
                schedule(ACQUIRE_POLL_INTERVAL_MS, token) {
                    pollEnter(token, isReopen, enteredAt, nullPolls = 0, retryIndex = 0, onDone)
                }
            } else {
                retryEnter(token, isReopen, nextAttempt + 1, onDone)
            }
        }
    }

    /**
     * 200ms 轮询回读；读到 3 或连续三次读不到（接口不可回读）即视为已进入。
     * 读到 1 等非 3 时按 300ms 间隔补发 enter，最多 3 次，2s 仍不成立则失败。
     */
    private fun pollEnter(
        token: Long,
        isReopen: Boolean,
        enteredAt: Long,
        nullPolls: Int,
        retryIndex: Int,
        onDone: (Boolean) -> Unit,
    ) {
        if (token != generation) return
        val mode = safeRead()
        val elapsed = now() - enteredAt
        when {
            mode == NAVI_MODE_FULL_MAP -> proceedEntered(isReopen, verifiedBy = "poll", onDone)
            mode == null && nullPolls + 1 >= UNREADABLE_POLLS ->
                proceedEntered(isReopen, verifiedBy = "readback", onDone)
            elapsed >= ACQUIRE_TIMEOUT_MS ->
                if (isReopen) failReopen(onDone) else failAcquire(token, onDone)
            else -> {
                var nextRetryIndex = retryIndex
                val retryDue = mode != null &&
                    enterAttempts < ENTER_MAX_ATTEMPTS &&
                    elapsed >= (retryIndex + 1) * ENTER_RETRY_DELAY_MS
                if (retryDue) {
                    enterAttempts += 1
                    nextRetryIndex += 1
                    record(EventKind.ENTER_UNVERIFIED, "第 $enterAttempts 次尝试")
                    safeEnter()
                }
                schedule(ACQUIRE_POLL_INTERVAL_MS, token) {
                    pollEnter(
                        token,
                        isReopen,
                        enteredAt,
                        if (mode == null) nullPolls + 1 else nullPolls,
                        nextRetryIndex,
                        onDone,
                    )
                }
            }
        }
    }

    private fun proceedEntered(
        isReopen: Boolean,
        verifiedBy: String,
        onDone: (Boolean) -> Unit,
    ) {
        phase = WAITING_FRAME
        modeVerified = verifiedBy == "poll"
        journal.markAcquired(modeVerified)
        diagnostics("Cluster: factory full-map mode entered=true verifiedBy=$verifiedBy")
        if (isReopen) {
            record(EventKind.REOPEN_OK, "第 $reopenAttempts 次")
            diagnostics("Cluster: manual reopen sent; waiting for a new video frame attempt=$reopenAttempts")
        } else if (modeVerified) {
            record(EventKind.ENTER_VERIFIED)
        }

        if (pendingFirstFrame) {
            pendingFirstFrame = false
            activate()
            onDone(true)
            return
        }

        val token = generation
        schedule(KEY_FRAME_REQUEST_AT_MS, token) {
            val sent = runCatching { keyFrameRequester?.invoke() ?: false }.getOrDefault(false)
            diagnostics("Cluster: fullmap keyframe requested sent=$sent")
        }
        schedule(FIRST_FRAME_TIMEOUT_MS, token) {
            lastError = "first frame timeout"
            record(EventKind.FIRST_FRAME_TIMEOUT)
            diagnostics("Cluster: fullmap waiting first frame timeoutMs=$FIRST_FRAME_TIMEOUT_MS")
            onFirstFrameTimeout?.invoke()
            releaseInternal(
                reason = "first-frame-timeout",
                attempt = 1,
                successPhase = RESTORED,
                onDone = {},
            )
        }
        onDone(true)
    }

    private fun activate() {
        cancelTimers()
        phase = ACTIVE
        journal.markAcquired(modeVerified)
        record(EventKind.FIRST_FRAME)
        diagnostics("Cluster: texture frame ready displayId=$displayId")
        diagnostics("Cluster: fullmap active displayId=$displayId")
    }

    private fun failAcquire(token: Long, onDone: (Boolean) -> Unit) {
        if (token != generation) return
        lastError = "full-map acquire not verified"
        diagnostics("Cluster: factory full-map mode entered=false verifiedBy=poll")
        // 失败方向偏向 OFF：先补发 1，但最终状态保留 FAILED（窗口应关闭）。
        releaseInternal(
            reason = "acquire-failed",
            attempt = 1,
            successPhase = FAILED,
            onDone = { onDone(false) },
        )
    }

    private fun failReopen(onDone: (Boolean) -> Unit) {
        diagnostics("Cluster: manual reopen failed; factory map stays off attempt=$reopenAttempts")
        record(EventKind.REOPEN_FAILED, "第 $reopenAttempts 次")
        if (reopenAttempts >= REOPEN_MAX_ATTEMPTS) {
            lastError = "manual reopen failed"
            phase = FAILED
            onDone(false)
            return
        }
        // 连续失败 3 次才进 FAILED；两次之间至少隔 1.5s，避免打成风暴。
        val token = generation
        phase = REOPENING
        schedule(REOPEN_MIN_INTERVAL_MS, token) {
            reopenAttempts += 1
            enterAttempts = 1
            val enteredAt = now()
            if (safeEnter()) {
                schedule(ACQUIRE_POLL_INTERVAL_MS, token) {
                    pollEnter(token, isReopen = true, enteredAt, nullPolls = 0, retryIndex = 0, onDone)
                }
            } else {
                retryEnter(token, isReopen = true, nextAttempt = 2, onDone)
            }
        }
    }

    // ------------------------------------------------------------------ 退出

    private fun releaseInternal(
        reason: String,
        attempt: Int,
        successPhase: CarClusterFullMapPhase,
        onDone: (Boolean) -> Unit,
    ) {
        if (attempt == 1) {
            if (phase == UNSUPPORTED) {
                phase = IDLE
                onDone(true)
                return
            }
            invalidate()
            sinceElapsed = now()
            pendingFirstFrame = false
            if (reason == REASON_GUARD_DISABLED) {
                record(EventKind.GUARD_DISABLED)
            }
            phase = RELEASING
            diagnostics("Cluster: fullmap release begin reason=$reason")
        }

        val token = generation
        record(EventKind.RELEASE_SENT, if (attempt > 1) "第 $attempt 次" else null)
        safeExit()
        pollRelease(token, reason, attempt, successPhase, now(), onDone)
    }

    private fun pollRelease(
        token: Long,
        reason: String,
        attempt: Int,
        successPhase: CarClusterFullMapPhase,
        startedAt: Long,
        onDone: (Boolean) -> Unit,
    ) {
        if (token != generation) return
        if (safeRead() == NAVI_MODE_OFF) {
            journal.markReleased(verified = true)
            journal.clear()
            phase = successPhase
            modeVerified = true
            lastError = null
            record(EventKind.RELEASE_VERIFIED)
            diagnostics("Cluster: full-map mode exited verified=true")
            diagnostics("Cluster: explicit task exit restoration=ok")
            onDone(true)
            return
        }
        if (now() - startedAt < RELEASE_TIMEOUT_MS) {
            schedule(RELEASE_POLL_INTERVAL_MS, token) {
                pollRelease(token, reason, attempt, successPhase, startedAt, onDone)
            }
            return
        }
        if (attempt < RELEASE_MAX_ATTEMPTS) {
            releaseInternal(reason, attempt + 1, successPhase, onDone)
            return
        }

        phase = RETRY_PENDING
        lastError = "restore not verified"
        record(EventKind.RELEASE_UNVERIFIED, "第 $attempt 次仍未确认")
        diagnostics("Cluster: full-map mode exited verified=false")
        diagnostics(
            "Cluster: restore failed; journal persisted retryAt=${now() + RETRY_PENDING_INTERVAL_MS}",
        )
        diagnostics("Cluster: explicit task exit restoration=failed")
        onDone(false)
        schedulePendingRetry(reason, attempt = 1)
    }

    /** 进程内后台补还：5s × 12（60s）。超过后保留账本，等下次启动。 */
    private fun schedulePendingRetry(reason: String, attempt: Int) {
        if (attempt > RETRY_PENDING_MAX_ATTEMPTS) {
            phase = FAILED
            return
        }
        val token = generation
        schedule(RETRY_PENDING_INTERVAL_MS, token) {
            safeExit()
            if (safeRead() == NAVI_MODE_OFF) {
                journal.markReleased(verified = true)
                journal.clear()
                phase = RESTORED
                modeVerified = true
                lastError = null
                diagnostics("Cluster: full-map mode exited verified=true")
                diagnostics("Cluster: explicit task exit restoration=ok")
                return@schedule
            }
            if (attempt == RETRY_PENDING_MAX_ATTEMPTS) {
                phase = FAILED
                lastError = "restore retry exhausted"
                diagnostics("Cluster: manual reopen failed; factory map stays off attempt=$attempt")
                return@schedule
            }
            schedulePendingRetry(reason, attempt + 1)
        }
    }

    // ------------------------------------------------------------------ 基础设施

    private fun resetForNewOperation() {
        invalidate()
        phase = IDLE
        pendingFirstFrame = false
        modeVerified = false
        lastError = null
        enterAttempts = 0
        reopenAttempts = 0
        lastReopenAt = 0L
    }

    private fun invalidate() {
        generation += 1
        cancelTimers()
    }

    private fun cancelTimers() {
        timers.forEach { runCatching { it() } }
        timers.clear()
    }

    private fun schedule(delayMs: Long, token: Long = generation, block: () -> Unit) {
        var cancel: (() -> Unit)? = null
        var ran = false
        cancel = scheduler.postDelayed(delayMs) {
            ran = true
            cancel?.let { handle -> timers.remove(handle) }
            if (token == generation) block()
        }
        if (!ran) {
            cancel?.let { timers += it }
        }
    }

    private fun safeAvailable(): Boolean =
        runCatching { dim.isAvailable() }.getOrDefault(false)

    private fun safeEnter(): Boolean =
        runCatching { canAcquire() && dim.enterFullNavi() }.getOrDefault(false)

    private fun safeExit(): Boolean =
        runCatching { dim.exitFullNavi() }.getOrDefault(false)

    private fun safeRead(): Int? =
        runCatching { dim.readNaviMode() }.getOrNull()

    /** 追加一条展示用事件；环形缓冲满时丢最旧的一条。 */
    private fun record(kind: String, detail: String? = null) {
        if (events.size >= EVENT_HISTORY_LIMIT) {
            events.removeFirst()
        }
        events.addLast(CarClusterFullMapEvent(kind = kind, atElapsedMs = now(), detail = detail))
    }

    /** 事件 kind（与 UI 文案映射一一对应）。 */
    private object EventKind {
        const val ACQUIRE_SENT = "ACQUIRE_SENT"
        const val ENTER_VERIFIED = "ENTER_VERIFIED"
        const val ENTER_UNVERIFIED = "ENTER_UNVERIFIED"
        const val FIRST_FRAME = "FIRST_FRAME"
        const val FIRST_FRAME_TIMEOUT = "FIRST_FRAME_TIMEOUT"
        const val TAKEN_BACK = "TAKEN_BACK"
        const val REOPEN_OK = "REOPEN_OK"
        const val REOPEN_FAILED = "REOPEN_FAILED"
        const val RELEASE_SENT = "RELEASE_SENT"
        const val RELEASE_VERIFIED = "RELEASE_VERIFIED"
        const val RELEASE_UNVERIFIED = "RELEASE_UNVERIFIED"
        const val RESTORE_ON_BOOT = "RESTORE_ON_BOOT"
        const val GUARD_DISABLED = "GUARD_DISABLED"
    }

    internal companion object {
        /** 展示用事件历史长度。 */
        const val EVENT_HISTORY_LIMIT = 30

        /** `release()` 的「灰度开关被关」原因（与 presenter 的调用点一致）。 */
        const val REASON_GUARD_DISABLED = "guard-disabled"

        /** 车机模式值：1 = 原厂（关闭全屏导航），3 = 全屏地图。 */
        const val NAVI_MODE_OFF = 1
        const val NAVI_MODE_FULL_MAP = 3

        /** 进入 / 退出回读轮询周期。 */
        const val POLL_INTERVAL_MS = 200L
        const val ACQUIRE_POLL_INTERVAL_MS = POLL_INTERVAL_MS
        const val RELEASE_POLL_INTERVAL_MS = POLL_INTERVAL_MS

        /** 进入模式回读超时。 */
        const val ACQUIRE_TIMEOUT_MS = 2_000L

        /** 补发 `switchNaviMode(3)` 的间隔与上限。 */
        const val ENTER_RETRY_DELAY_MS = 300L
        const val ENTER_MAX_ATTEMPTS = 3

        /** 连续三次读不到模式 ⇒ 视为「不可回读但调用成功」。 */
        const val UNREADABLE_POLLS = 3

        /** 首帧看门狗（日志串固定 5000）。 */
        const val FIRST_FRAME_TIMEOUT_MS = 5_000L

        /** 首帧半程催一次 I 帧。 */
        const val KEY_FRAME_REQUEST_AT_MS = 2_500L

        /** 退出回读：2s × 3 次。 */
        const val RELEASE_TIMEOUT_MS = 2_000L
        const val RELEASE_MAX_ATTEMPTS = 3

        /** 后台补还：5s × 12（60s）。 */
        const val RETRY_PENDING_INTERVAL_MS = 5_000L
        const val RETRY_PENDING_MAX_ATTEMPTS = 12

        /** 两次重开之间的最小间隔（防 onModeOff 风暴）。 */
        const val REOPEN_MIN_INTERVAL_MS = 1_500L

        /** 重开连续失败上限。 */
        const val REOPEN_MAX_ATTEMPTS = 3
    }
}

/** 定时任务调度口（真机 = 主线程 Handler；单测 = 虚拟时钟）。 */
internal interface GeelyClusterScheduler {
    /** 返回取消句柄。 */
    fun postDelayed(delayMillis: Long, task: () -> Unit): () -> Unit
}

private class HandlerClusterScheduler(
    private val handler: Handler,
) : GeelyClusterScheduler {
    override fun postDelayed(delayMillis: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMillis)
        return { handler.removeCallbacks(runnable) }
    }
}
