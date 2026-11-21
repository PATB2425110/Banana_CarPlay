package com.dbtool.carplay.geely

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仪表模式变化监听 —— 车机把仪表「收回」时能第一时间知道，好把画面抢回来。
 *
 * ## 解决什么现象
 *
 * 真车实测：仪表画面上来之后，**一启动地图 / 车机切一次仪表模式，画面就黑了**。
 * 日志里看到的是一条固定的组合：
 *
 * ```
 * Cluster: 仪表副屏已挂载 displayId=3 面板=1024x274
 * BufferQueueProducer: cancelBuffer: BufferQueue has been abandoned
 * SurfaceUtils: disconnecting from surface …disconnectFromSurface
 * ```
 *
 * 挂载是成功的，Surface 却被车机收走 —— 因为车机原厂界面（DimMenu）把仪表
 * 抢回去了，而**我们没有任何人被告知这件事**。
 *
 * ## 原版怎么做（反编译结论）
 *
 * `io.dushu.car.geely.GeelyDimModeObserver` 起一个 **logcat 子进程**盯着车机
 * 自己的日志（tag `DimMenuInteraction`），一旦命中原版的匹配串就回调上层，
 * 上层据此重开画面。
 *
 * 🔴 **匹配串是 `"switchNaviMode naviMode:1"`**（`GeelyDimModeObserver.smali` 第 264 行，
 * 2026-10-09 逐行核实），**不是 `"onModeOff"`**：
 *
 * ```
 * const-string v8, "switchNaviMode naviMode:1"
 * invoke-static {line, v8}, …StringsKt;->contains$default(…)
 * ```
 *
 * ⚠️ `onModeOff` 是**它的 Kotlin 字段名**（`Function0` 回调），**从来不是任何日志串**。
 * PATCH-0046 移植时把字段名当成了匹配串 ⇒ 该 `if` 永远命中 `continue` ⇒ 回调永不触发。
 * 详见 `REF-pitfalls.md` §80。
 *
 * ### 语义：这条串盯的是「谁」
 *
 * 车机值域 `{1=关, 3=全屏地图}`（实测）。**我方"抢屏"拨的是 3**（见
 * [GeelyDimModeController.enterFullNavi]），打的是 `naviMode:3` —— **不命中本串**。
 * 只有**我方主动退出（拨 1）** 或**别人把模式拨回 1（= 关掉全屏导航）** 才会打出
 * `naviMode:1`。因此：
 *
 * - 「我方 exit」的记录；`markOwnModeOff()` 打时间戳后 [OWN_OFF_QUIET_MS] 内忽略
 *   （原版同机制，窗口 = `0x5dc` = **1500ms**）；
 * - 「别人拨回 1」只说明模式关闭，无法区分 VIEW / 导航结束 / 厂商切换。
 *   回调 [onModeOff] 仅作提示；presenter 通过稳定回读尊重关闭，禁止立即重抢。
 *
 * 监听**不写任何文件、不外发数据**，只是读 logcat 文本。
 *
 * 本类按原版**思路**重写（上游 GPL-3.0，只照思路不抄码）。
 */
internal class GeelyDimModeObserver(
    private val diagnostics: (String) -> Unit,
    private val onModeOff: () -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var process: Process? = null

    /** 我们自己主动关的时刻（去抖，见 [markOwnModeOff]）。 */
    @Volatile
    private var ownOffAt: Long? = null

    fun isOwnModeOffQuiet(): Boolean =
        ownOffAt?.let { SystemClock.elapsedRealtime() - it < OWN_OFF_QUIET_MS } ?: false

    /**
     * 标记「这次关闭是我方主动发起的」。
     *
     * 在调 `exitFullNavi()` / `stop()` 之前调用。之后 [OWN_OFF_QUIET_MS]
     * 毫秒内收到的 `naviMode:1` 会被忽略 —— 否则「我们自己退出的」会被当成
     * 「被车机抢走的」，触发无意义的重开。
     */
    fun markOwnModeOff() {
        ownOffAt = SystemClock.elapsedRealtime()
    }

    /** 启动监听（幂等；已在跑则直接返回）。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return

        // 与原版命令对齐（`GeelyDimModeObserver.smali` 第 144~189 行）：
        //   logcat -T 1 -v raw DimMenuInteraction:D *:S
        // ⚠️ `*:S`（默认静默）必须放**最后** —— liblog 按顺序解析过滤器。
        // 只留 `DimMenuInteraction` 一个 tag：原版就没有 `DimMenu:D`。
        val cmd = arrayOf(
            "logcat", "-T", "1", "-v", "raw", "DimMenuInteraction:D", "*:S",
        )

        val proc = runCatching {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        }.getOrElse { error ->
            diagnostics(
                "Cluster: DimMenu 监听启动失败 " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            running.set(false)
            return
        }
        process = proc
        diagnostics("Cluster: DimMenu 监听已启动（logcat 子进程，盯 naviMode:1）")

        val worker = Thread({ pump(proc) }, "geely-dim-observer").apply { isDaemon = true }
        thread = worker
        worker.start()
    }

    private fun pump(proc: Process) {
        runCatching {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (!line.contains(MATCH_NAVI_MODE_OFF)) continue
                    // 我方主动关的不算 —— 否则会把自己退出误判成被抢走。
                    if (isOwnModeOffQuiet()) continue
                    diagnostics("Cluster: 监听到模式1（来源未知，非VIEW按键信号），交给上层回读确认")
                    runCatching { onModeOff() }
                }
            }
        }.onFailure { error ->
            diagnostics("Cluster: DimMenu 监听读流异常 ${error.javaClass.simpleName}: ${error.message}")
        }
        running.set(false)
    }

    /** 停掉监听并释放子进程。可重复调用。 */
    fun stop() {
        running.set(false)
        runCatching { process?.destroy() }
        process = null
        thread?.interrupt()
        thread = null
    }

    private companion object {
        /**
         * 原版匹配串（smali 第 264 行）。含义：**全屏导航被拨回 `1`（关闭）**，
         * 即仪表被车机收回。⚠️ 别改回 `onModeOff`（那是字段名）。
         */
        const val MATCH_NAVI_MODE_OFF = "switchNaviMode naviMode:1"

        /** 我方主动关闭后的静默窗口，毫秒（原版 `markOwnModeOff` = `0x5dc` = 1500ms）。 */
        const val OWN_OFF_QUIET_MS = 1500L
    }
}
