package com.dbtool.carplay.api.hotspot

/**
 * 「5556 到底开没开」的**纯判据**（2026-10-16）。
 *
 * ## 为什么要把判据单独抽出来
 *
 * 实车日志反复出现同一种**误判**：三条属性都已读回 `5556`、adbd 也起来了，
 * 界面却报"未通过系统属性读回验证 / 持久性未确认"。成因全都在**判据**这一层，
 * 不在"能不能开"这一层：
 *
 * | 现场证据 | 曾经被判成 | 真实含义 |
 * |---|---|---|
 * | 端口读写脚本**退出码 127**（某条子命令 `not found`），但读回值全是 5556 | ❌ 切换失败 | ✅ 只是那条脚本的非零退出，端口其实已生效 |
 * | `init.svc.adbd=stopping`（`ctl.stop`+`ctl.start` / `adb root` 之后的一瞬） | ❌ adbd 没起来 | ⏳ adbd 正在重启，停在 `stopping` 是**预期中间态** |
 * | `service.adb.tcp.port=5556` 但 adbd 不在跑 | ❌ 通道可用 | ❌ 属性只是配置，**没在监听** |
 *
 * 所以判据必须是**三件事分开算**（需求原文：「不能仅凭配置属性或端口号判断
 * ADB 已成功开启」）：
 *
 * 1. **当前可用** = `adb connect 127.0.0.1:5556` + shell 验活通过。
 *    这是唯一权威判据 —— 属性写对了但没监听时它必然失败；属性写得含糊但真在监听时它必然成功。
 * 2. **配置对齐** = 瞬态属性读回 5556（"adbd 本次听哪个口"的设计值）。
 * 3. **重启后可用** = 持久属性（AOSP `persist.adb.tcp.port` + 厂商 `persist.adb.tcpip`）读回 5556。
 *
 * ⇒ 复用判据只看第 1 条：**端口现在能用就直接复用**，不因为它"看起来像没配好"
 * 而重跑一遍换口流程（那会 `ctl.stop adbd` 掐断一条本来好用的通道）。
 *
 * ## 为什么纯函数
 *
 * 这些规则全是"读回值 + 退出码 → 结论"的映射，不需要设备。抽成纯函数才能在
 * JVM 单测里把上表三行**钉死**（见 `AdbPortVerdictTest`）—— 留在
 * `AdbShellRunner` / `AdbShellSession` 里就只能靠上车试。
 *
 * ⚠️ 本对象**不做任何 IO**、不读系统属性、不建连接。调用方负责把读到的值
 * 填进 [Snapshot]。
 */
object AdbPortVerdict {

    /** adbd 在跑时的 `init.svc.adbd` 值。 */
    const val ADBD_RUNNING: String = "running"

    /**
     * 一次端口状态取证的**原始读回值**（全部来自设备，不做解释）。
     *
     * 空串一律表示"**未读到**"（ROM 禁读 / 属性不存在 / 那条命令没跑成），
     * **不等于** 5555，也**不等于** 5556 —— 见 [CarAdbHotspotDefaults.ADB_PORT_PROPERTY] 的注释。
     */
    data class Snapshot(
        /** `service.adb.tcp.port` 读回（adbd 本次实际监听的口）。 */
        val transientPort: String = "",
        /** `persist.adb.tcp.port` 读回（AOSP 的重启回填源）。 */
        val persistentPort: String = "",
        /** `persist.adb.tcpip` 读回（厂商那条重启回填源）。 */
        val vendorPort: String = "",
        /** `init.svc.adbd` 读回（`running` / `stopping` / `restarting` / 空）。 */
        val adbdState: String = "",
        /**
         * 端口读写脚本的**退出码**。
         *
         * ⚠️ 它只是**证据**，不是判据：`/system/bin/sh: 5556: not found` 这类
         * 子命令失败会让整条脚本非零退出，而端口其实已经生效。把它当判据就会
         * 出现"读回值全对却报失败"（本类存在的直接原因）。
         */
        val shellExitCode: Int = 0,
        /** `adb connect 127.0.0.1:<port>` + shell 验活是否通过（唯一权威判据）。 */
        val connectionVerified: Boolean = false,
    )

    /**
     * 判据结论。
     *
     * [notes] 是**给车主看的中文短句**，直接拼进界面结果行 / 自检页；
     * 调用方不要再自己解释一遍（那正是"两处判据漂移"的来源）。
     */
    data class Verdict(
        /** 5556 现在真的能用（实连 + shell 验活通过）⇒ 直接复用。 */
        val currentUsable: Boolean,
        /** 瞬态属性读回 == 目标口。 */
        val transientAligned: Boolean,
        /** AOSP 持久属性读回 == 目标口（"重启后还能用"的必要条件，非充分）。 */
        val persistentVerified: Boolean,
        /** 厂商持久属性读回 == 目标口。 */
        val vendorVerified: Boolean,
        /** `init.svc.adbd` 读回 == `running`。 */
        val adbdRunning: Boolean,
        /** 人类可读的分项说明（含"未验证/未知"的如实描述）。 */
        val notes: List<String>,
    ) {
        /** 单行摘要。 */
        val detail: String get() = notes.joinToString(" ")
    }

    /**
     * 判定一次端口取证的结论。
     *
     * @param targetPort 目标端口（生产恒为 [CarAdbHotspotDefaults.DEVICE_ADB_PORT]，
     *        显式传参只为单测能换值）。
     */
    fun judge(snapshot: Snapshot, targetPort: Int): Verdict {
        val target = targetPort.toString()
        val transientAligned = snapshot.transientPort == target
        val persistentVerified = snapshot.persistentPort == target
        val vendorVerified = snapshot.vendorPort == target
        val adbdRunning = snapshot.adbdState == ADBD_RUNNING
        val notes = mutableListOf<String>()

        // ① 当前可用：只看实连验活。属性/状态与它不一致时**只补说明，不改结论**。
        if (snapshot.connectionVerified) {
            notes += "5556 当前可用（adb connect + shell 验活通过）。"
        } else {
            notes += "5556 当前不可用（adb connect + shell 验活未通过）。"
        }

        // ② 配置对齐（瞬态口）。
        if (transientAligned) {
            notes += "运行端口=$target。"
        } else {
            notes += "运行端口读回为「${snapshot.transientPort.ifEmpty { "未读到" }}」，不是 $target。"
        }

        // ③ adbd 服务状态。
        when {
            adbdRunning -> notes += "adbd=running。"

            snapshot.adbdState.isEmpty() -> notes +=
                "读不到 init.svc.adbd（该 ROM 可能禁读；此项按「未验证」处理，不作失败判据）。"

            else -> notes +=
                "adbd=${snapshot.adbdState}（ctl.stop/ctl.start 或 adb root 之后会短暂停在 " +
                "stopping/restarting，属重启中间态，不能据此判失败）。"
        }

        // ④ 持久性：与"当前可用"**分开报**，绝不合并成一句"开启成功"。
        if (persistentVerified) {
            notes += "持久端口已读回 $target（重启后仍由 init 回填）。"
        } else {
            notes += "持久端口未确认读回 $target（「现在能用」与「重启后还能用」是两件事，重启后可能回到厂商口）。"
        }
        // 厂商那条（`persist.adb.tcpip`）只在**有冲突值**时才值得提：很多 ROM 压根
        // 没有这条属性，把它当"未通过"会让每一台这种车都常驻一句无意义的告警。
        // 值为空 = 开机不会从它回填（不影响结论）；非空且不等于目标口 = 真的会打架。
        when {
            vendorVerified -> notes += "厂商持久口也已读回 $target。"
            snapshot.vendorPort.isNotEmpty() -> notes +=
                "厂商持久口读回为「${snapshot.vendorPort}」（本车可能仍按厂商口开机，已由自愈兜底）。"
            else -> Unit
        }

        // ⑤ 非零退出码：如实记录，但**不当判据**（见 [Snapshot.shellExitCode]）。
        if (snapshot.shellExitCode != 0) {
            notes += "端口读写脚本退出码 ${snapshot.shellExitCode}（常见于某条子命令 not found）；判据取读回值，本项不影响结论。"
        }

        // ⑥ 失败时给出**下一步该做什么**（需求：端口未开启或服务异常要显示具体原因并允许重试）。
        if (!snapshot.connectionVerified) {
            notes += when {
                transientAligned && !adbdRunning ->
                    "端口配置已是 $target 而 adbd=${snapshot.adbdState.ifEmpty { "未读到" }} ⇒ 需要把 adbd 拉起来后再验一次。"
                !transientAligned ->
                    "adbd 本次听的不是 $target ⇒ 需要切回 $target 并重启 adbd。"
                else -> "端口与服务配置看起来正常但仍连不上 ⇒ 属其它故障，可在设置页重试。"
            }
        }

        return Verdict(
            currentUsable = snapshot.connectionVerified,
            transientAligned = transientAligned,
            persistentVerified = persistentVerified,
            vendorVerified = vendorVerified,
            adbdRunning = adbdRunning,
            notes = notes,
        )
    }
}

/**
 * 「当前可用 / 重启后可用 / 还没初始化过」→ [CarAdbChannelStatus] 的**唯一映射处**（2026-10-16）。
 *
 * ## 为什么必须只有一处
 *
 * 界面上的三件事各自读一个字段：状态行文案、开启按钮是否置灰、关闭按钮是否置灰。
 * 它们原来各写一遍 `when`，于是"当前可用但持久性未确认"这种中间态很容易在
 * 某一处被压成 `READY`（按钮被错误置灰，用户再也没法把它变持久），
 * 或者在另一处被压成 `UNAVAILABLE`（读起来像故障，把用户引去重开一遍）。
 *
 * 需求原文：「如果 5556 端口已开启但永久属性尚未验证成功，应区分"当前端口可用"
 * 和"重启后能否继续使用"两种状态，不要直接提示全部开启成功。」
 * ⇒ 三条输入，一个 `when`，谁都不许自己再拼。
 */
object CarAdbChannelStatusPolicy {

    /**
     * @param usable 实连 + shell 验活通过（[AdbPortVerdict.Verdict.currentUsable]）。
     * @param persistentVerified 持久属性读回为目标口。
     * @param initializedByUser 用户历史上点过初始化（用于区分"没开过"与"开过但坏了"）。
     */
    fun statusFor(
        usable: Boolean,
        persistentVerified: Boolean,
        initializedByUser: Boolean,
    ): CarAdbChannelStatus = when {
        !usable && initializedByUser -> CarAdbChannelStatus.UNAVAILABLE
        !usable -> CarAdbChannelStatus.NOT_INITIALIZED
        !persistentVerified -> CarAdbChannelStatus.PERSISTENCE_UNVERIFIED
        else -> CarAdbChannelStatus.READY
    }
}
