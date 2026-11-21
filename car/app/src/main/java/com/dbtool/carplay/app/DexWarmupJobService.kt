package com.dbtool.carplay.app

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.SystemClock
import android.util.Log

/**
 * 装完预热任务的执行体：**只负责把进程撑住** [DexWarmup.HOLD_MILLIS]。
 *
 * 加固壳的 `dex2oat` 发生在 `Application.attachBaseContext` 之前，与本服务
 * 的代码没有调用关系；这里不主动触发编译，也不探测编译进度——进程活着，
 * 壳自己会把 c1–c3 编完。
 *
 * 不持 WakeLock：车机熄火时任务被系统推迟/中止都可以接受（下次冷启动
 * 本来就会重编）。因此 [onStopJob] 返回 `false`，不要求系统重新调度。
 */
class DexWarmupJobService : JobService() {

    /** 只用于在 [onStopJob] 里打断正在睡眠的线程。 */
    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val versionCode = DexWarmup.currentVersionCode(this)
        if (!DexWarmup.shouldWarmUp(DexWarmup.readWarmedVersionCode(this), versionCode)) {
            Log.i(TAG, "预热跳过：versionCode=$versionCode 已预热过")
            jobFinished(params, false)
            return false
        }

        val startedAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "预热开始：versionCode=$versionCode 将撑住 ${DexWarmup.HOLD_MILLIS}ms",
        )

        val thread = Thread({ holdProcess(params, versionCode, startedAt) }, "dex-warmup")
        worker = thread
        thread.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        Log.i(TAG, "预热被中止：versionCode=${DexWarmup.currentVersionCode(this)}")
        // 不重试：装完这次没撑住，下次冷启动照样会编译；反复重排只会耗电。
        return false
    }

    private fun holdProcess(params: JobParameters, versionCode: Int, startedAt: Long) {
        val totalSeconds = (DexWarmup.HOLD_MILLIS / 1_000L).toInt()
        val segmentMillis = DexWarmup.HOLD_MILLIS / SEGMENTS

        repeat(SEGMENTS) {
            try {
                Thread.sleep(segmentMillis)
            } catch (_: InterruptedException) {
                // 被 onStopJob 中止：jobFinished 不能再调，直接退出。
                Thread.currentThread().interrupt()
                return
            }
            val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()
            Log.i(TAG, "预热中：已撑住 ${elapsedSeconds}s / ${totalSeconds}s")
        }

        val heldMillis = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "预热完成：versionCode=$versionCode 实际撑住 ${heldMillis}ms")
        DexWarmup.markWarmed(this, versionCode)
        worker = null
        jobFinished(params, false)
    }

    private companion object {
        const val TAG = "carplay-warmup"
        const val SEGMENTS = 5
    }
}
