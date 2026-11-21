package com.dbtool.carplay.bridge.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbtool.carplay.api.projection.CarPlayClusterProjection
import com.dbtool.carplay.api.projection.ClusterDisplayFinder
import com.dbtool.carplay.api.session.CarProjectionState

/**
 * 仪表副屏投屏的**状态机**：把「找屏 → 开窗 → 交 Surface → 让 iPhone 画」串起来。
 *
 * ## 它负责什么 / 不负责什么
 *
 * - **负责**：决定当前该不该有窗、开在哪块屏、Surface 交给谁、状态怎么报。
 * - **不负责**：发 `showUI` / `setClusterUrl` 这类 iAP2 命令 —— 那是
 *   [com.shilapi.xcertplay.airplay.AirPlaySession] 的活，由 `CarPlaySessionBridge` 直接调。
 *
 * 这样分层的原因是：**车机侧的窗口与手机侧的命令是两条独立的失败路径**，
 * 混在一起排查时无法区分「窗没建起来」还是「手机不理我」。分开后
 * [state] 能精确指出卡在哪一步。
 *
 * ## 超时
 *
 * 发出投屏请求后 15 秒仍无首帧 = 失败（与上游一致）。常见原因：iPhone 不认
 * 这个 URL（老系统/地图 App 版本），或副屏固件不收画面。超时后自动关窗，
 * 避免留一个透明的空窗盖在仪表上。
 */
class ClusterProjectionController(
    private val context: Context,
    /** Surface 出口：交给 iAP2 的 alt screen（111）绑定 / 解绑。 */
    private val surfaceSink: (android.view.Surface?) -> Unit,
    /** 流是否已建立（由桥注入 `AirPlaySession.clusterStream > 0`）。 */
    private val streamReady: () -> Boolean = { false },
    /** 日志出口（桥注入 `platform.diagnostics.log`）。 */
    private val log: (String) -> Unit = {},
    /**
     * 催帧出口：桥注入 `AirPlaySession.requestClusterKeyFrame`。
     *
     * 2026-10-08（PATCH-0034）新增。首帧走到半程还不来时主动调用一次，
     * 让手机补发仪表屏的 I 帧，而不是干等到超时收窗。
     */
    private val requestKeyFrame: () -> Boolean = { false },
    /**
     * 平台层是否**已经自己管着副屏窗口**（即 `platform.cluster != null`）。
     *
     * ## 为什么必须有这道闸门（2026-10-08 定案）
     *
     * 反编译原版只有**一个**窗口 owner（`GeelyClusterSurfaceOwner`）。DBPlay 却有
     * 两套并行：本控制器（PATCH-0025/0030/0033/0034）与平台层
     * `CarClusterPresenter` 的实现（PATCH-0011/0017）。诊断包实证同一次会话里
     * 两块屏都开了窗，14:10:25 还发生 Surface 换绑：
     *
     * ```
     * 14:09:17.470 connecting to surface 0x7513ac9010   ← 本控制器建的窗
     * 14:10:25.490 connecting to surface 0x75142c9010   ← 平台层的窗（后建在上）
     * 14:10:25.494 disconnecting from surface 0x7513ac9010
     * ```
     *
     * 先建的窗从此收不到帧，又因 `firstFrame` 已置位不会触发超时，会**一直留在屏上**。
     *
     * 定案：**有平台实现时，窗口归平台层独占**（对齐原版）。本控制器退回
     * "iAP2 命令 + 状态"职责，`setEnabled(true)` 直接拒绝建窗。
     * 没有平台实现的车机（如通用 Android）仍由本控制器建窗，行为不变。
     */
    private val platformOwnsWindow: () -> Boolean = { false },
) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var enabled = false
    @Volatile private var uiShown = false
    @Volatile private var projection: CarPlayClusterProjection? = null
    @Volatile private var attachedDisplayId = -1
    @Volatile private var requestedAt = 0L

    /** 本次开窗是否已催过关键帧（只催一次，避免每个轮询周期都打扰手机）。 */
    @Volatile private var recoveryRequested = false

    /**
     * 首帧等待上限。
     *
     * 2026-10-08 由 15s 收紧到 **5s**：斌哥口径 —— 让用户自选屏试投，
     * 选错屏或手机不推流时，别让一块空窗在仪表上挂 15 秒（用户以为死机）。
     * 5 秒没画面就收窗，用户能立刻换下一块屏重试。
     */
    private val firstFrameTimeoutMs = 5_000L

    /**
     * 用户选定的仪表屏 displayId（-1 = 未选，退回旧自动挑）。
     *
     * 由桥在每次 [start] 前从设置里读回，避免控制器反向依赖设置层。
     */
    @Volatile private var requestedDisplayId = -1

    /** 设置变更时由桥调用：下次 [start] 生效。 */
    fun setRequestedDisplayId(id: Int) {
        requestedDisplayId = id
    }

    private val timeoutCheck = object : Runnable {
        override fun run() {
            if (!enabled) return
            val p = projection ?: return
            if (p.firstFrame) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - requestedAt
            if (elapsed >= firstFrameTimeoutMs) {
                // 超时：iPhone 没推画面。收窗，别把透明空窗留在仪表上。
                debug("projection: 首帧超时（${firstFrameTimeoutMs}ms），关闭投屏窗口")
                releaseProjection()
                return
            }
            // 2026-10-08（PATCH-0034）：走到半程仍无首帧 → 主动催一次仪表关键帧
            // （带 ALT_UUID）。解码器此刻多半停在 `waiting for random access` 上空等
            // I 帧，催一次能立刻恢复，不必干等到超时。只催一次，避免反复打扰手机。
            if (!recoveryRequested && elapsed >= firstFrameTimeoutMs / 2) {
                recoveryRequested = true
                debug("projection: 半程无首帧，请求仪表关键帧 sent=${requestKeyFrame()}")
            }
            main.postDelayed(this, 500L)
        }
    }

    /** 开关投屏。返回「本次是否真的建立了窗口」。 */
    fun setEnabled(value: Boolean): Boolean {
        if (value == enabled) return projection != null
        enabled = value
        return if (value) start() else { stop(); true }
    }

    private fun start(): Boolean {
        // 2026-10-08：平台层已经有副屏窗口 owner（`platform.cluster != null`）⇒
        // 本控制器**不建窗**。两套同时建窗会各挂一块 Presentation 并抢同一条
        // ALT(111) 轨（见构造函数 `platformOwnsWindow` 的实证）。对齐反编译原版
        // 的"单窗口 owner"。
        if (platformOwnsWindow()) {
            debug("projection: 副屏窗口归平台层（CarClusterPresenter）独占，本控制器不建窗")
            enabled = false
            return false
        }
        // 2026-10-08：优先用用户选的屏（设置里的 clusterDisplayId）。
        val display = ClusterDisplayFinder.best(context, requestedDisplayId)
        if (display == null) {
            // 2026-10-10：判据改成「未选屏一律不挑」（连"只有一块候选"也不挑），
            // 所以这里的诊断文案不再区分"候选有几块"——有候选就得等用户去设置里选。
            val candidates = ClusterDisplayFinder.selectable(context).size
            val why = when {
                requestedDisplayId >= 0 ->
                    "用户选定的屏 displayId=$requestedDisplayId 当前不可用（未插 / 已重排 / 是主屏）"
                candidates > 0 ->
                    "检测到 $candidates 块非主屏候选，但用户尚未选屏 —— 不替用户猜，" +
                        "等他在「设置 → 仪表导航」里选一块"
                else ->
                    "本机没有非主屏候选"
            }
            debug("projection: 未找到仪表副屏（$why）")
            enabled = false
            return false
        }
        // 流尺寸：用屏的原始分辨率。Presentation 内部会按「等比居中」把它缩放到屏上，
        // 保持 1:1（标定后再调）。
        val w = display.mode?.physicalWidth ?: display.width
        val h = display.mode?.physicalHeight ?: display.height
        val p = CarPlayClusterProjection(
            context = context,
            onSurface = surfaceSink,
            onFirstFrame = { debug("projection: 首帧到达，仪表地图可见") },
        )
        val ok = p.attach(display.displayId, w, h)
        if (!ok) {
            // Presentation.show() 抛异常（BadToken / InvalidDisplay / 权限 / 屏不可写）。
            // 带上 flags 便于区分「安全屏静默丢弃」与「根本没这块屏」。
            debug(
                "projection: Presentation.show() 失败 —— " +
                    "${ClusterDisplayFinder.describe(display)} flags=0x${Integer.toHexString(display.flags)}",
            )
            enabled = false
            return false
        }
        projection = p
        attachedDisplayId = display.displayId
        requestedAt = android.os.SystemClock.elapsedRealtime()
        recoveryRequested = false
        debug("projection: 窗口已建于 ${ClusterDisplayFinder.describe(display)}")
        main.postDelayed(timeoutCheck, 500L)
        return true
    }

    private fun stop() {
        main.removeCallbacks(timeoutCheck)
        releaseProjection()
    }

    private fun releaseProjection() {
        projection?.close()
        projection = null
        attachedDisplayId = -1
        // 关窗时 CarPlayClusterProjection 会回调 onSurface(null)，桥负责解绑 111。
    }

    fun setUiShown(shown: Boolean) {
        uiShown = shown
    }

    fun isUiShown(): Boolean = uiShown

    /** 当前状态快照，供 UI 直接读。 */
    fun state(): CarProjectionState = CarProjectionState(
        streamReady = streamReady(),
        attached = projection?.attached == true,
        firstFrame = projection?.firstFrame == true,
        displayId = attachedDisplayId,
        shown = uiShown,
    )

    private fun debug(message: String) {
        runCatching { log("ClusterProjection: $message") }
    }
}
