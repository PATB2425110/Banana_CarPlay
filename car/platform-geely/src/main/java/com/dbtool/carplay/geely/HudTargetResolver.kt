package com.dbtool.carplay.geely

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.dbtool.carplay.api.navigation.CarHudGuidance

/**
 * 一个 HUD 桥目标（宿主包 + 服务类）。
 *
 * 服务类名两辆目标车相同，只有宿主包不同（见 [HudTargetResolver.CANDIDATES]），
 * 所以这里刻意把两者放在一起表达，避免"只换包名却忘了服务类"的错配。
 */
data class HudTarget(val packageName: String, val serviceName: String) {
    /** 与 `ComponentName.flattenToString()` 同形的 `包名/服务类名`。 */
    val flatName: String get() = "$packageName/$serviceName"
}

/**
 * HUD 桥目标的**纯逻辑**解析（2026-10-18）。
 *
 * ## 为什么需要它
 *
 * 车机侧的 hudbridge 服务不在固定宿主包里：
 *
 * | 宿主包 | 来源 |
 * | --- | --- |
 * | `com.baidu.naviauto` | DBPlay 目标车 1.1.15 实测可绑（`PATCH-0111`） |
 * | `com.autonavi.amapauto` | Fake Ultra 原版写死的目标（另一辆目标车） |
 *
 * 两辆车的包不一样，而过去 [GeelyHudBridge] 把包名写死成百度 ⇒ 高德车上
 * `bindService` 永远失败、HUD 全程不可用（日志只有一句"车机没有该服务"）。
 *
 * ## 为什么解析与探测分开
 *
 * [resolve] 只吃一个 `(HudTarget) -> Boolean` 谓词，不含任何 Android 类型 ⇒
 * 顺序、回退、异常降级都能在 JVM 单测里钉死。真正的 `resolveService` 探测在
 * [HudTargets] 里，只有一行 `PackageManager` 调用。
 *
 * 候选顺序**固定百度 → 高德**：百度是 DBPlay 已装车验证过的宿主，优先它；
 * 高德是 Fake Ultra 的原版目标，作为第二候选。顺序变了会让已可用车辆的
 * 绑定路径也跟着变，没有必要。
 */
internal object HudTargetResolver {

    /**
     * 候选目标，顺序固定：**高德 → 百度**（2026-10-19 调整）。
     *
     * 为什么改成高德优先：Fake Ultra 实证只在 `com.autonavi.amapauto` 上成功
     * （它压根没有百度那条候选），而现场两台车 `com.baidu.naviauto` 都不存在，
     * 先试百度只是白白多一次 bind 失败。这里与 Fake Ultra 完全对齐。
     *
     * 服务类名两个宿主都按 Fake Ultra 的实证值写死
     * （`com.baidu.naviauto.hudbridge.CarPlayHudBridgeService`，注意它本身就以
     * `com.baidu` 开头，但**不代表**宿主包必须是百度 —— 原版就是"高德包 + 百度类"的组合）。
     */
    val CANDIDATES: List<HudTarget> = listOf(
        HudTarget(
            packageName = "com.autonavi.amapauto",
            serviceName = "com.baidu.naviauto.hudbridge.CarPlayHudBridgeService",
        ),
        HudTarget(
            packageName = "com.baidu.naviauto",
            serviceName = "com.baidu.naviauto.hudbridge.CarPlayHudBridgeService",
        ),
    )

    /**
     * 按 [CANDIDATES] 顺序返回第一个可解析的目标；都不可用返回 `null`。
     *
     * 谓词抛异常（例如 `getPackageInfo` 抛 `NameNotFoundException`、跨进程
     * `PackageManager` 被回收）一律视为"该候选不可用"：探测失败**不能**把
     * 整个 HUD 链路带崩，更不能让上层看到异常。
     */
    fun resolve(isServiceResolvable: (HudTarget) -> Boolean): HudTarget? =
        CANDIDATES.firstOrNull { target ->
            runCatching { isServiceResolvable(target) }.getOrDefault(false)
        }
}

/**
 * 真实的 `PackageManager` 探测（Android 9 可用 API）。
 *
 * 与 [HudTargetResolver] 分开：这里只负责"怎么问系统"，不负责"选哪一个"。
 * 两个方法都**不抛异常** —— 车机上包不存在、服务未导出、系统回收
 * `PackageManager` 代理都属于常态。
 */
object HudTargets {

    /**
     * 生成一个"该目标能否被 `resolveService` 看见"的谓词。
     *
     * @param action 绑定用的 action（`com.stewie.carplay.hudbridge.BIND`）。
     */
    fun resolverFor(
        packageManager: PackageManager,
        action: String,
    ): (HudTarget) -> Boolean = { target ->
        runCatching {
            packageManager.resolveService(
                Intent(action).setComponent(
                    ComponentName(target.packageName, target.serviceName),
                ),
                0,
            ) != null
        }.getOrDefault(false)
    }

    /**
     * 宿主包是否安装。
     *
     * `getPackageInfo` 抛 `NameNotFoundException` ⇒ 视为未安装，**不向上抛**。
     * ⚠️ Android 11+ 的包可见性会让"看不见"与"没安装"同形；本车是 Android 9，
     * 这里只把它当诊断信息用，不当结论。
     */
    fun packageInstalled(packageManager: PackageManager, packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) != null }
            .getOrDefault(false)
}

/** HUD 内容过期窗口：120 秒无新帧就请求车机清空（与 Fake Ultra 一致）。 */
internal const val HUD_EXPIRY_MS = 120_000L

/**
 * HUD 服务断开后，若仍有有效导航，延迟多久尝试重新绑定。
 *
 * 与反编译的 HudBridge 客户端一致：`onServiceDisconnected` 时如果
 * `current != null` 就重新 `bind()`。这里加 3 秒退避，避免服务刚重启时
 * 形成紧密的 bind/unbind 循环。
 */
internal const val HUD_RECONNECT_DELAY_MS = 3_000L

/**
 * HUD 保活周期。
 *
 * 反编译的服务端 `k.e()` 在最后一次有效导航后约 30.5 秒无新帧就会清空
 * `FakeBaiduLbsService` 的导航状态。DBPlay 的 iPhone 帧在停车等灯时可能几十秒
 * 没有距离/秒数变化（去重会丢弃它们），所以用 20 秒重发同一帧保活；
 * 服务端字段没变时不会重复发 remain/turn 事件，只把它的超时窗口向后推。
 */
internal const val HUD_KEEPALIVE_MS = 20_000L

/**
 * 等待服务连接期间保存的最后一帧有效导航。
 *
 * 反编译的 HudBridge 客户端在 `onServiceConnected` 里会先重放 `current`，
 * 没有当前导航时才发停止。DBPlay 之前把“收到帧时还没绑上”的帧直接丢了，
 * 表现是导航开始后仪表要等下一帧才出现卡片；服务重连后也可能一直空着。
 *
 * 只保存**通过 turnKind 校验**的帧，并用 [HUD_EXPIRY_MS] 判断是否还值得重放：
 * 断线很久后把一条旧提示重新推到仪表，比不推更糟。
 *
 * 纯逻辑、无线程依赖：调用方在 HUD 的主线程 handler 上访问。
 */
internal class HudPendingGuidance(
    private val maxAgeMs: Long = HUD_EXPIRY_MS,
) {
    private var value: CarHudGuidance? = null

    /** 记下最近一帧有效导航；下一帧到来时覆盖。 */
    fun remember(guidance: CarHudGuidance) {
        value = guidance
    }

    /** 导航结束、停止或关闭时清空，避免重连后把旧提示推回去。 */
    fun clear() {
        value = null
    }

    /**
     * 返回仍可重放的帧；超过 [maxAgeMs] 时顺手清空。
     *
     * @param nowMs epoch 毫秒（与 [CarHudGuidance.updatedAtMs] 同口径）。
     */
    fun fresh(nowMs: Long): CarHudGuidance? {
        val guidance = value ?: return null
        if (nowMs - guidance.updatedAtMs > maxAgeMs) {
            value = null
            return null
        }
        return guidance
    }
}

/**
 * HUD 保活计时（单调时钟，与数据时间戳 `updatedAtMs` 不同）。
 *
 * 每次真正发到车机的一帧（新帧或保活帧）都调 [onFrame]；到 [intervalMs]
 * 才允许再发一次。纯逻辑，便于在 JVM 上钉住“保活周期必须短于服务端超时”。
 */
internal class HudKeepalive(
    private val intervalMs: Long = HUD_KEEPALIVE_MS,
) {
    private var lastFrameMs: Long? = null

    fun onFrame(nowMs: Long) {
        lastFrameMs = nowMs
    }

    fun cancel() {
        lastFrameMs = null
    }

    fun isDue(nowMs: Long): Boolean =
        lastFrameMs?.let { nowMs - it >= intervalMs } ?: false
}

/**
 * HUD 120 秒看门狗的**纯状态机**（挂在这个文件里避免新增一个只放十行代码的文件）。
 *
 * ## 为什么不是直接写 `postDelayed`
 *
 * `Handler` 的延迟回调在 JVM 单测里跑不了（Looper 未准备），而"过期窗口写错"
 * 的后果只有上车才看得见：要么 HUD 卡片永远挂着，要么导航途中被误清。
 * 所以把**截止时间**抽成纯逻辑，`GeelyHudBridge` 只负责"帧到了调 [onFrame]、
 * 到点调 [claimExpiry]、停用调 [cancel]"。
 *
 * 时钟用调用方传入的单调毫秒（桥上用 `SystemClock.elapsedRealtime()`），
 * 与 `CarHudGuidance.updatedAtMs` 的 epoch ms **不是同一个钟** —— 后者是发给
 * 车机的数据时间，本类只管本地计时。
 *
 * 语义逐条对应任务书：
 * - 每次成功发帧 [onFrame] ⇒ 截止时间后移（新帧重置计时）；
 * - 截止前 [claimExpiry] 返回 false（120 s 内不触发）；
 * - 到点后第一次 [claimExpiry] 返回 true，随后截止时间清空 ⇒ **只触发一次**；
 * - [cancel] 之后 [claimExpiry] 永远 false（stop/close 取消看门狗）。
 */
internal class HudExpiryDeadline(private val windowMs: Long = HUD_EXPIRY_MS) {

    private var deadlineMs: Long? = null

    /** 截止时刻；`null` = 没有在计时。测试与日志用。 */
    val deadline: Long? get() = deadlineMs

    /** 成功发帧：从现在起重新计 [windowMs]。 */
    fun onFrame(nowMs: Long) {
        deadlineMs = nowMs + windowMs
    }

    /** 取消计时（stop/close/会话结束）。 */
    fun cancel() {
        deadlineMs = null
    }

    /** 是否已到点（不会消费触发机会）。 */
    fun isExpired(nowMs: Long): Boolean = deadlineMs?.let { nowMs >= it } ?: false

    /**
     * 到点则消费一次并返回 true；未到点（或已取消/已消费）返回 false。
     *
     * "消费"保证同一个截止时刻只清空一次 —— 否则 Handler 因主线程忙而晚触发时，
     * 多出来的回调会对着已清空的车机再发一遍停止事务。
     */
    fun claimExpiry(nowMs: Long): Boolean {
        val deadline = deadlineMs ?: return false
        if (nowMs < deadline) return false
        deadlineMs = null
        return true
    }
}
