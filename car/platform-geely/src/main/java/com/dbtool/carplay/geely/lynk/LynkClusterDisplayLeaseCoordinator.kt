package com.dbtool.carplay.geely.lynk

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lynk 借屏**生命周期协调器**：reserve → activate → release 三段式。
 *
 * ## 为什么 DBPlay 需要它
 *
 * DBPlay 现在只做「挂窗口 + setSurface」，**缺 reserve/activate 两步**。
 * 上游 DiPlay(Lynk) 的 `LynkClusterDisplayLeaseCoordinator` 负责：
 *
 * ```
 * 1. reserve(owner)   ← 预定：开通 Binder 通道 → 轮询 isShown()
 * 2. activate(owner)  ← 激活：isShown==null 就 enable()，按 retry 重试
 * 3. LynkPresentation.showSafely()   ← 挂窗口（Presentation + SurfaceView）
 * 4. mediaSink.setSurface(...)       ← 把 Surface 交给解码器
 * ```
 *
 * **第 1、2 步是 DBPlay 完全没有的**（见 [LynkClusterDisplayWire] 注释）。
 *
 * ⚠️ 未实机验证。接入前先用 [LynkClusterDisplayWire.probe] 确认服务存在。
 */
class LynkClusterDisplayLeaseCoordinator(
    private val wireFactory: () -> LynkClusterDisplayWire.Port? = {
        LynkClusterDisplayWire.open()
    },
    /** 单次 enable 后等待确认的时间。 */
    private val timeoutMillis: Long = 1500L,
    /** enable 未生效时的重试间隔。 */
    private val retryMillis: Long = 400L,
    /** 最多重试几次。 */
    private val maxAttempts: Int = 5,
) {

    private companion object {
        const val TAG = "DBPlay-LynkLease"
    }

    /** 借屏阶段。 */
    enum class Phase { IDLE, RESERVING, ACTIVE, RELEASING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dbplay-lynk-lease").apply { isDaemon = true }
    }

    private var port: LynkClusterDisplayWire.Port? = null
    private val busy = AtomicBoolean(false)

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /**
     * 预定并激活借屏（reserve + activate 合一）。
     *
     * @param onResult 主线程回调；true = 已拿到屏所有权
     */
    fun acquire(owner: String, onResult: (Boolean) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "acquire 重入，忽略 owner=$owner")
            main.post { onResult(phase == Phase.ACTIVE) }
            return
        }
        phase = Phase.RESERVING
        worker.execute {
            val ok = acquireOnWorker(owner)
            phase = if (ok) Phase.ACTIVE else Phase.FAILED
            busy.set(false)
            main.post { onResult(ok) }
        }
    }

    /**
     * 还屏（release）。幂等；无论是否成功都关通道。
     */
    fun release(owner: String, onDone: (() -> Unit)? = null) {
        phase = Phase.RELEASING
        worker.execute {
            runCatching { port?.restore() }
                .onFailure { Log.w(TAG, "restore 失败 owner=$owner", it) }
            runCatching { port?.close() }
            port = null
            phase = Phase.IDLE
            Log.i(TAG, "已还屏 owner=$owner")
            if (onDone != null) main.post(onDone)
        }
    }

    // ---- worker ----

    private fun acquireOnWorker(owner: String): Boolean {
        val p = wireFactory() ?: run {
            Log.w(TAG, "借屏通道打不开（DimNaviService 不存在？）owner=$owner")
            return false
        }
        port = p

        // reserve 阶段：先探已知状态。
        val known = runCatching { p.isShown() }.getOrNull()
        if (known == true) {
            Log.i(TAG, "reserve：车机已借出（isShown=true），无需 enable owner=$owner")
            return true
        }

        // activate 阶段：isShown==null 或 false → enable()，按 retry 重试。
        repeat(maxAttempts) { attempt ->
            val ok = runCatching { p.enable() }.getOrDefault(false)
            if (ok) {
                // 等待确认（给车机一点时间）。
                val deadline = System.currentTimeMillis() + timeoutMillis
                while (System.currentTimeMillis() < deadline) {
                    if (runCatching { p.isShown() }.getOrNull() == true) {
                        Log.i(TAG, "activate 成功，attempt=${attempt + 1} owner=$owner")
                        return true
                    }
                    runCatching { Thread.sleep(retryMillis) }
                }
            }
            Log.w(TAG, "activate 第 ${attempt + 1}/$maxAttempts 次未确认 owner=$owner")
        }
        Log.e(TAG, "借屏失败：$maxAttempts 次仍未拿到 owner=$owner")
        return false
    }
}
