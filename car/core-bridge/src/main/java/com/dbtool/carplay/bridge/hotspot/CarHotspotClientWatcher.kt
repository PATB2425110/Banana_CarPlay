package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.os.SystemClock
import com.dbtool.carplay.api.diagnostics.CarDiagnosticsAdapter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 「复用车机热点」连接窗口内的**只读**观测器（PATCH-0116，2026-10-13）。
 *
 * ## 它回答哪一个问题
 *
 * 复用失败的现象在日志里永远是同一句：
 * `wireless attach timed out: iPhone did not open the AirPlay connection within 40000ms`。
 * 而它下面藏着**两种完全不同的故障**，后续该查的东西也完全不同：
 *
 * | 车机侧有没有客户端关联 | 说明 | 该查什么 |
 * | --- | --- | --- |
 * | **没有** | iPhone 压根没连上车机那张 AP | 热点名 / 密码 / 手机侧"忽略此网络" |
 * | **有** | 关联成功，但到不了我们通告的 endpoint | 路由 / endpoint 选址 / AirPlay 监听 |
 *
 * 日志 9（`1791897995606`，2026-10-13）卡在这两者之间：应用侧只能证明
 * "iPhone 没连到我们的 7000 端口"，不能证明它有没有关联上那张网络 ——
 * 于是只能去问手机侧。而**车机自己知道**：Ecarx SDK 的客户端列表就是答案，
 * 我们本来就有一套只读的读法（[EcarxHotspotReader]，诊断包里的
 * `Ecarx clients=` 就是它），只是**从来没在连接窗口里读过**。
 *
 * ## 纪律
 *
 * - **只读**：复用 [EcarxHotspotReader]，它不配置热点、不改路由、不动客户端限制；
 * - **有界**：固定间隔、固定次数，到点自己停（这是连接窗口的观测，不是常驻监控）；
 * - **不猜**：读数不可用（本车没有 Ecarx SDK / 读不到）时**不下结论**，
 *   如实写"读数不可用"，把"判不了"与"没有客户端"分开 —— 这正是本仓反复强调的
 *   "读不到 ≠ 否"；
 * - **不阻塞会话**：全部采样在**一个**后台守护线程上，失败只写日志。
 *
 * 只在「复用车机热点」这一支启动（见 `CarPlaySessionBridge.ensureManualHotspotReady`）：
 * 自建那张网是我们自己起的，客户端是谁我们本来就知道。
 */
object CarHotspotClientWatcher {

    /** Last session-window client addresses held in memory only; never logged or persisted. */
    internal data class RecentClientEvidence(
        val observedAtEpochMillis: Long,
        val clientCount: Int,
        val ipv4Addresses: List<String>,
        val routeStatus: EcarxHotspotReader.RouteStatus,
        val interfaces: List<String>,
    )

    @Volatile
    internal var latestEvidence: RecentClientEvidence? = null
        private set

    /** Max age accepted when a later diagnostic package asks for the session-window observation. */
    const val EVIDENCE_TTL_MILLIS: Long = 10 * 60 * 1000L

    internal fun recentEvidence(nowMillis: Long = System.currentTimeMillis()): RecentClientEvidence? =
        latestEvidence?.takeIf { nowMillis >= it.observedAtEpochMillis && nowMillis - it.observedAtEpochMillis <= EVIDENCE_TTL_MILLIS }

    /**
     * 采样间隔。
     *
     * 一秒采样，让连接阶段尽快看到客户端；重复日志单独限速，不拖慢状态刷新。
     */
    private const val SAMPLE_INTERVAL_MILLIS = 1_000L

    /**
     * 采样次数上限。
     *
     * 最多 90 次约 90 秒覆盖多轮连接窗口。
     * **必须有界**：无界的话，一次失败的复用会在这里留下永不停止的线程。
     */
    private const val MAX_SAMPLES = 90

    private val lock = Any()

    /**
     * 观测轮次号（PATCH-0133）。
     *
     * `start()` 会取消上一轮的采样任务，但取消不等于立刻停：旧任务可能正卡在
     * `EcarxHotspotReader.snapshot()` 里，随后才把**上一轮**的 `clientCount` 写回
     * [latestEvidence]。RFCOMM 早停读的正是这份快照，所以发布前必须核对轮次号，
     * 只允许当前轮次写入。
     */
    @Volatile
    private var evidenceGeneration = 0L

    private var executor: ScheduledExecutorService? = null

    private var task: SampleTask? = null

    /**
     * 开一次观测。**可重复调用**（每一轮复用连接都调一次）：上一次没跑完就被取消，
     * 保证任何时刻只有一个观测线程、且它对应的总是最新一轮连接。
     */
    fun start(
        context: Context,
        diagnostics: CarDiagnosticsAdapter,
        tag: String,
    ) {
        val appContext = context.applicationContext
        synchronized(lock) {
            executor?.shutdownNow()
            // 被顶掉的那一轮**要把读器也收掉**：否则每次重连都会漏一个 SDK/binder 连接。
            task?.closeQuietly()
            // PATCH-0133：新一轮连接开始前先丢掉上一轮的客户端证据。
            // RFCOMM 早停会在轮询间隙读这份快照；若不清空，切到新会话后的头几秒
            // 可能拿到上一轮（甚至上一次连接）的 `clientCount`，据此早停就是错的。
            // 清空后第一份证据由下面的采样任务立即补上，诊断包最多少一个瞬时点。
            latestEvidence = null
            val generation = ++evidenceGeneration
            val service = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "car-hotspot-client-watch").apply { isDaemon = true }
            }
            executor = service
            val newTask = SampleTask(appContext, diagnostics, tag, service, generation)
            task = newTask
            service.scheduleWithFixedDelay(
                newTask,
                0L,
                SAMPLE_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 采样主体。持有**一个**读器实例：跨采样的 SDK 加载结果本来就是进程级复用的。 */
    private class SampleTask(
        private val context: Context,
        private val diagnostics: CarDiagnosticsAdapter,
        private val tag: String,
        private val service: ScheduledExecutorService,
        private val generation: Long,
    ) : Runnable {

        private val startedAt = SystemClock.elapsedRealtime()
        private var index = 0

        /** 有没有**读到过**客户端（读到 0 个不算）。 */
        private var sawClient = false

        /** 有没有**读得动**（至少成功读到一次非 UNAVAILABLE 的快照）。 */
        private var everReadable = false
        private var previousDetail: String? = null

        private val reader = runCatching { EcarxHotspotReader(context) }.getOrNull()

        /** 收掉读器。`EcarxHotspotReader.close()` 幂等（只翻一个 `closed` 标志），可重复调用。 */
        fun closeQuietly() {
            runCatching { reader?.close() }
        }

        override fun run() {
            val sample = ++index
            val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            val snapshot = reader?.let { runCatching { it.snapshot() }.getOrNull() }
            val readable = snapshot != null &&
                snapshot.routeStatus != EcarxHotspotReader.RouteStatus.UNAVAILABLE
            if (readable) {
                everReadable = true
                // 只发布当前轮次的证据；旧任务可能在 `start()` 清空之后才返回。
                snapshot?.takeIf { generation == evidenceGeneration }?.let {
                    latestEvidence = RecentClientEvidence(
                        observedAtEpochMillis = System.currentTimeMillis(),
                        clientCount = it.clientCount,
                        ipv4Addresses = it.clientIpv4Addresses,
                        routeStatus = it.routeStatus,
                        interfaces = it.routeSources.keys.sorted(),
                    )
                }
            }

            val detail = if (snapshot == null || !readable) {
                "车机侧客户端读数不可用（本机读不到 Ecarx SDK 的客户端列表）"
            } else {
                if (snapshot.clientCount > 0) sawClient = true
                "车机侧客户端=${snapshot.clientCount}，" +
                    "地址=${snapshot.addressCount}，" +
                    "可路由=${snapshot.routeCount}（${snapshot.diagnostic}）"
            }
            if (detail != previousDetail || sample % 5 == 0) {
                diagnostics.log(tag, "复用连接窗口观测 #${sample}（+${elapsedSeconds}s）：${detail}")
                previousDetail = detail
            }

            if (sawClient || sample >= MAX_SAMPLES) {
                finish(sample)
            }
        }

        /**
         * 收尾：把**结论**写清楚，并停掉线程。
         *
         * 三种结论分开写（**"判不了"不许写成"没有"**）：Windows 上读日志的人
         * 就是靠这一行决定下一步查什么。
         */
        private fun finish(sample: Int) {
            val conclusion = when {
                sawClient ->
                    // 2026-10-20 现场复核：这里只能说「车机侧有客户端」，不能推出「就是 iPhone」。
                    // 12 个真实诊断包里，成功会话的 iPhone 是 192.168.15.7；四个晚间失败包
                    // 读到的是 192.168.15.6，而用户 22:07 的截图显示 iPhone 是 .7。现有证据
                    // 不足以证明 .6 就是 iPhone —— 客户端身份必须靠 MAC/IP 比对，不能靠数量猜。
                    "**车机侧出现了客户端**，但本机没有做 MAC/IP 身份比对，不能据此断定就是 iPhone。" +
                        "若仍 `attach timed out`，请先核对手机 Wi-Fi 页显示的 IP 与诊断包里的 " +
                        "`Ecarx current client IPv4` 是否一致：不一致 ⇒ 手机并没有连上这台热点；" +
                        "一致 ⇒ 问题在关联之后（TCAM 转发 / 路由 / AirPlay 回连），与热点名和密码无关。"
                !everReadable ->
                    "读数**全程不可用** ⇒ 这一轮判不了（本机不支持 Ecarx SDK 客户端读数），" +
                        "请不要把本行当成「没有客户端」。"
                else ->
                    "**车机侧全程没有客户端** ⇒ iPhone 没有关联上车机热点，" +
                        "该查的是：热点名是否与车机实际广播的一致、密码是否正确、" +
                        "以及手机里是否存着这张网的旧密码（需要「忽略此网络」）。"
            }
            diagnostics.log(tag, "复用连接窗口观测结束（采样 ${sample} 次）：${conclusion}")
            closeQuietly()
            service.shutdown()
        }
    }
}
