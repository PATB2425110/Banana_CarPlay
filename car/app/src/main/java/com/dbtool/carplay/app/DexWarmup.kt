package com.dbtool.carplay.app

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 升级/安装后的一次性预热：撑住进程，让加固壳的 dex2oat 跑完。
 *
 * ## 为什么需要它
 *
 * 加固壳在 `Application.attachBaseContext` 之前把 dex 解到
 * `app_app_dex/v208-<hash>/`，然后 ART 逐个 `dex2oat --compiler-filter=quicken`
 * （1.1.15 实测 c1 2.5s、c2 2.8s、c3 2.0s）。装完那一刻系统起的进程如果
 * 只编译完 c1 就被杀掉，用户点开时要从头再编一遍——首启 9605ms 的来源。
 * 反之只要那个进程活到编译完成，第二次启动就只要 84–190ms。
 *
 * 所以这里不做任何"加速编译"的事，只是**装完把进程多留 25 秒**：
 * 先由 `MY_PACKAGE_REPLACED` 调度一个 `JobScheduler` 一次性任务，
 * 任务起进程后睡够 25 秒再退出。不弹 UI、不抢前台、不持 WakeLock。
 */
internal object DexWarmup {

    const val JOB_ID = 4711

    private const val PREFS = "dbplay_dex_warmup"
    private const val KEY_WARMED_CODE = "warmed_version_code"

    /** 实测三次 quicken 合计约 7.3–9.5s，留足余量。 */
    const val HOLD_MILLIS = 25_000L

    /** 装完立刻起进程没有意义：那时壳可能还在解 dex，先让系统把广播派完。 */
    const val MIN_INTERVAL_BEFORE_START_MILLIS = 5_000L

    /**
     * 兜底期限：即使系统（Doze / 应用待机分组）把最小延迟往后压，
     * 也不晚于这一刻执行。
     */
    const val OVERRIDE_DEADLINE_MILLIS = 60_000L

    /** 纯函数：要不要预热。已预热过同一 versionCode 就返回 false。 */
    fun shouldWarmUp(lastWarmedVersionCode: Int, currentVersionCode: Int): Boolean =
        currentVersionCode > 0 && lastWarmedVersionCode != currentVersionCode

    /**
     * `ACTION_MY_PACKAGE_REPLACED` 的入口：调度一次性预热任务。
     *
     * 已预热过当前版本时**直接返回**，不排任何任务——否则每次覆盖安装
     * （哪怕版本号没变）都会白占 25 秒。
     */
    fun scheduleAfterUpdate(context: Context) {
        val versionCode = currentVersionCode(context)
        if (!shouldWarmUp(readWarmedVersionCode(context), versionCode)) {
            Log.i(TAG, "预热跳过：versionCode=$versionCode 已预热过")
            return
        }

        val scheduler = runCatching {
            context.getSystemService(JobScheduler::class.java)
        }.getOrNull()
        if (scheduler == null) {
            Log.w(TAG, "预热调度失败：JobScheduler 不可用，versionCode=$versionCode")
            return
        }

        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, DexWarmupJobService::class.java),
        )
            .setMinimumLatency(MIN_INTERVAL_BEFORE_START_MILLIS)
            .setOverrideDeadline(OVERRIDE_DEADLINE_MILLIS)
            .build()

        runCatching { scheduler.schedule(job) }
            .onSuccess { result ->
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.i(
                        TAG,
                        "预热已调度：versionCode=$versionCode 最小延迟=" +
                            "${MIN_INTERVAL_BEFORE_START_MILLIS}ms 撑住=${HOLD_MILLIS}ms",
                    )
                } else {
                    Log.w(TAG, "预热调度被系统拒绝：versionCode=$versionCode result=$result")
                }
            }
            .onFailure { Log.w(TAG, "预热调度异常：versionCode=$versionCode", it) }
    }

    fun readWarmedVersionCode(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WARMED_CODE, 0)
    }.getOrDefault(0)

    fun markWarmed(context: Context, versionCode: Int) {
        runCatching {
            // 用 commit 而不是 apply：这里已经在后台线程，且写完马上要
            // jobFinished；进程若紧接着被杀，apply 的异步入盘可能来不及。
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_WARMED_CODE, versionCode)
                .commit()
        }.onFailure { Log.w(TAG, "预热状态写入失败：versionCode=$versionCode", it) }
    }

    /**
     * 当前安装包的 versionCode（读不到按 0 处理 ⇒ [shouldWarmUp] 返回 false）。
     *
     * `longVersionCode` 是 API 28 起才有的，本应用 `minSdk = 28`，可直接用。
     */
    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    private const val TAG = "carplay-warmup"
}
