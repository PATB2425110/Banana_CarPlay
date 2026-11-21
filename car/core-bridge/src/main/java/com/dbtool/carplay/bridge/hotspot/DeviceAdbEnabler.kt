package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.AdbPortVerdict
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults

/**
 * 开通车机自身的 ADB（`service.adb.tcp.port=5556` + 拉起 `adbd`）。
 *
 * ## 与参考实现（`FUCK-Lynk&Co-ADB.apk`）的关系（2026-10-06 定案）
 *
 * 本类的**判据与顺序逐条对齐**参考实现的 `OSNwifi.openAdb()`，只有两处**故意不同**：
 *
 * | | 参考实现 | DBPlay |
 * |---|---|---|
 * | 端口 | 写死 `5555`（厂商口，蹭它） | **`5556`**（我们自己的口，不跟厂商抢） |
 * | 写几条属性 | 只有 `service.adb.tcp.port`（瞬态） | **瞬态 + `persist.adb.tcp.port`**（重启不回退） |
 *
 * 参考实现的原始逻辑（`OSNwifi.smali` .line 74~81，逐行核对）：
 *
 * ```java
 * String port = FxBridge.getProperties("service.adb.tcp.port", "");
 * String adbd = FxBridge.getProperties("init.svc.adbd", "");
 * if (TextUtils.isEmpty(port)) {
 *     FxBridge.setProperties("service.adb.tcp.port", "5555");
 *     FxBridge.setProperties("ctl.stop",  "adbd");
 *     FxBridge.setProperties("ctl.start", "adbd");
 * } else if (!"running".equals(adbd)) {
 *     FxBridge.setProperties("ctl.start", "adbd");
 * }
 * ```
 *
 * ## 为什么必须是"直写属性"而不是"走 adb shell"
 *
 * ⚠️ 这是**鸡生蛋**：`adb shell` 得先能连上，而连不上正是因为 ADB 没开。
 * 参考实现用的是 `SystemProperties.set`（`FxBridge` 只是它的转发壳），
 * **不需要 root、也不需要任何已建立的 adb 通道**——这正是它能"自己打开 ADB"
 * 的原因。所以本类**不能**退化成调 `AdbShellRunner`，否则永远开不起来。
 *
 * ## 端口为什么是 5556 而不是 5555
 *
 * 5555 是车机厂商自己的口（实车 `persist.adb.tcp.port=5555`，且车机里还有独立的
 * ADB 引导组件在反复操作 adbd）。我们蹭它的两个风险：
 *   ① 厂商组件一重启 adbd，我们的通道就断；
 *   ② **换一台 ADB 没开的车**，5555 压根没人听 —— 我们连入口都没有。
 * ⇒ 拿到能力后立刻把 adbd 切到 **我们自己的 5556**，从此不碰厂商的口。
 * 详见 [CarAdbHotspotDefaults.DEVICE_ADB_PORT] 与 `ADB-HOTSPOT.md` §2.3.2。
 *
 * ## 为什么两条属性都要写（关键，别删）
 *
 * `service.adb.tcp.port` 是 init 的**瞬态**属性，重启即丢；
 * 只有 `persist.adb.tcp.port` 会被 init 从 `/data/property` **回填**。
 * 只写瞬态 ⇒ 每次车机重启 adbd 又回到厂商的 5555 ⇒ **每次上车都要重抢一次**。
 * 这正是"独占 5556、避免冲突"要解决的问题。
 *
 * ## 幂等（与参考实现同判据，只是值不同）
 *
 * - 瞬态口 **不是 5556** ⇒ 写 5556 + `ctl.stop adbd` + `ctl.start adbd`；
 * - 持久口 **不是 5556** ⇒ 补写 5556（**不重启 adbd**——它本次不生效，等下次开机）；
 * - adbd **不是 `running`** ⇒ 补 `ctl.start adbd`。
 *
 * ⚠️ `ctl.stop adbd` **只在整个口都要换时才做**。若只是补 `ctl.start`，
 * 绝不能 stop —— 会掐断我们**当下正在用**的这条连接（此刻通常还在 5555 上，
 * 一旦断掉，第 5 步的 `adb connect` 就再没机会了）。
 *
 * ## 为什么是"尽力而为"
 *
 * [ok] 只表示"本步骤没挡住流程"，**不表示 ADB 已可用**。
 * 真正的判据永远是"下一步能不能 `connect` 上"；
 * 把它记成失败会让自检页出现一个红叉却仍然跑通，反而更难读。
 *
 * ## 验证（首次装车看这三项）
 *
 * ① `setProperties` 是否真生效（**读回** == 5556）；② `ctl.start adbd` 后
 * `init.svc.adbd` 是否变 `running`；③ `logcat` 有无 `avc: denied`。
 * 读回值本方法会写进 [Outcome.detail]，直接看诊断包里的这一行即可。
 *
 * ## 与 `switchToDedicatedPort` 的分工
 *
 * 本方法走 `SystemProperties`（**无 root、连接之前**）；
 * `AdbShellRunner.switchToDedicatedPort` 走 `su` / `adb shell`（**有 root、连接之后**）。
 * 两条路都指向 5556，且都幂等 ⇒ 谁先跑都安全，互为兜底：
 * 本方法失败（部分 ROM 对普通应用禁写 `persist.*`）时，
 * 连接建立后的那条 root 路径还能补上。
 */
internal object DeviceAdbEnabler {

    /** 初始化结果：端口读回与服务启动必须满足才算本次配置成功。 */
    data class Outcome(
        val ok: Boolean,
        val persistentVerified: Boolean,
        val detail: String,
    )

    /**
     * **结构化的只读快照**（2026-10-16 新增）。
     *
     * 与 [snapshot]（拼好的整串、只给日志用）的分工：本类型把四条值**分开**存，
     * 供判据层按字段读。
     *
     * ⚠️ 为什么不能拿 [snapshot] 的整串做 `contains("5556")`：
     * "瞬态=5555 而持久=5556"这种撕裂状态会被整串匹配误判成"口是对的"。
     * 空串一律表示**未读到**（ROM 禁读 / 属性不存在），不等于 5555 也不等于 5556。
     */
    data class PortSnapshot(
        /** `service.adb.tcp.port`：adbd **本次实际监听**的口。 */
        val transient: String,
        /** `persist.adb.tcp.port`：AOSP 的重启回填源。 */
        val persist: String,
        /** `persist.adb.tcpip`：本车那套厂商重启回填源。 */
        val vendor: String,
        /** `init.svc.adbd`：`running` / `stopping` / `restarting` / 空。 */
        val adbdState: String,
    ) {
        /** 换成本判据层认识的取证快照。 */
        fun toVerdictSnapshot(
            shellExitCode: Int,
            connectionVerified: Boolean,
        ): AdbPortVerdict.Snapshot = AdbPortVerdict.Snapshot(
            transientPort = transient,
            persistentPort = persist,
            vendorPort = vendor,
            adbdState = adbdState,
            shellExitCode = shellExitCode,
            connectionVerified = connectionVerified,
        )
    }

    /**
     * 仅供用户明确点击的首次/修复操作调用。日常 ADB ensure 路径不得调用本方法。
     *
     * 尝试将 adbd 运行端口与重启回填端口都设为 5556。SystemProperties 反射在不同
     * ROM 上可能被权限策略拒绝；必须读回验证，不能把“set 没抛异常”当成功。
     *
     * ## `restartAdbdWhenConfigured`（2026-10-16 加，别删）
     *
     * 调用方（`AdbShellSession`）**已经实连验活失败**时传 `true`。这时即使三条属性
     * 早已是 5556、adbd 也在 `running`，仍有一种必须处理的现场：
     * **属性是在 adbd 跑起来之后才写进去的** ⇒ adbd 这一轮根本没重新读过它，
     * 配置对、进程在，**但没人在 5556 上监听**。此时只有 `ctl.stop` + `ctl.start`
     * 能让它按新口重听。默认 `false`（无谓重启会掐断一条本来好用的通道）。
     */
    fun initializeDedicatedPort(restartAdbdWhenConfigured: Boolean = false): Outcome {
        val target = CarAdbHotspotDefaults.DEVICE_ADB_PORT.toString()
        val transientProp = CarAdbHotspotDefaults.ADB_PORT_PROPERTY
        val persistProp = CarAdbHotspotDefaults.ADB_PORT_PROPERTY_PERSIST
        val vendorProp = CarAdbHotspotDefaults.ADB_VENDOR_TCPIP_PROPERTY
        val stateProp = CarAdbHotspotDefaults.ADB_STATE_PROPERTY
        return try {
            val before = snapshotNow()
            var restarted = false
            when {
                // 口不对：写瞬态 + 重启 adbd（让它按新口重听）。
                before.transient != target -> {
                    systemSet(transientProp, target)
                    systemSet("ctl.stop", "adbd")
                    systemSet("ctl.start", "adbd")
                    restarted = true
                }
                // 口已对、进程没跑：只拉起（**不 stop**，避免掐断当下这条连接）。
                before.adbdState != AdbPortVerdict.ADBD_RUNNING -> systemSet("ctl.start", "adbd")
                // 口对、进程在跑，但调用方说连不上 ⇒ 让 adbd 按新口重听一轮。
                restartAdbdWhenConfigured -> {
                    systemSet("ctl.stop", "adbd")
                    systemSet("ctl.start", "adbd")
                    restarted = true
                }
            }
            // 持久化两条：幂等、非破坏（**不重启 adbd**，本次不生效就等下次开机）。
            val persistWritten = before.persist != target
            if (persistWritten) systemSet(persistProp, target)
            val vendorWritten = before.vendor != target
            if (vendorWritten) systemSet(vendorProp, target)

            // ⚠️ 重启过 adbd 就必须**等它起来**再读回：`ctl.stop` 之后的一瞬间
            //    `init.svc.adbd` 就是 `stopping`，读到一次 stopping 就判失败正是
            //    "属性全对却报未通过读回验证"的直接成因（2026-10-16 修）。
            val actualState = if (restarted) awaitAdbdRunning().adbdState else snapshotNow().adbdState
            val actualTransient = systemGet(transientProp)
            val actualPersist = systemGet(persistProp)
            val actualVendor = systemGet(vendorProp)
            val currentOk = actualTransient == target && actualState == AdbPortVerdict.ADBD_RUNNING
            val persistentOk = actualPersist == target
            val detail = buildString {
                append(
                    "运行端口=$actualTransient，持久端口=$actualPersist，" +
                        "厂商持久口=$actualVendor，adbd=$actualState。",
                )
                when {
                    !currentOk -> append(
                        if (actualState != AdbPortVerdict.ADBD_RUNNING) {
                            "当前 5556 未通过系统属性读回验证：adbd=$actualState" +
                                "（已等它重启完仍未到 ${AdbPortVerdict.ADBD_RUNNING}）。"
                        } else {
                            "当前 5556 未通过系统属性读回验证：运行端口读回=$actualTransient。"
                        },
                    )
                    !persistentOk -> append("当前端口已设定，但 ROM 未确认持久属性；不能保证重启后仍可用。")
                    actualVendor != target -> append(
                        "厂商持久口未确认读回 $target（写前=${before.vendor.ifEmpty { "空" }}）；" +
                            "本车可能仍按厂商旧口重启，已由自愈兜底。",
                    )
                    restarted -> append("系统属性读回通过（adbd 已按 $target 重启）；仍需重启后实测端口自动可用。")
                    else -> append("系统属性读回通过（本次未重启 adbd）；仍需重启后实测端口自动可用。")
                }
                if (!persistWritten && !vendorWritten && !restarted) {
                    append(" 三条属性与 adbd 状态本就一致，未做任何写入。")
                }
            }
            Outcome(ok = currentOk, persistentVerified = persistentOk, detail = detail)
        } catch (t: Throwable) {
            Outcome(
                ok = false,
                persistentVerified = false,
                detail = "ROM 拒绝或无法设置 ADB 端口（${t.javaClass.simpleName}: ${t.message}）。" +
                    "请在车机系统设置中手动启用无线 ADB 后重试；未验证前不会标记为永久成功。",
            )
        }
    }

    /**
     * 【新增 2026-10-15】**只读快照**：瞬态口 / 持久口 / adbd 状态。
     *
     * ⚠️ 与 [enable] 的分工：本方法**只读**三条属性，**不写属性、不 `ctl.stop` /
     * `ctl.start`、不建 adb 连接、不 `ensure()`**。开机时刻（`BOOT_COMPLETED`）
     * 任何写 / 重启 adbd 的动作都可能掐断别人正在用的通道，所以那一刻只需要"看一眼"。
     *
     * 读不到（ROM 禁读 / 属性不存在）时给空串 —— 与 [systemGet] 的口径一致
     * （那是"未验证"，**按设计不当失败**）。
     *
     * ⚠️ 返回值是拼好的整串，**不要**拿它做 `contains("5556")` 之类的控制流判据：
     * "持久=5556 而瞬态=5555"这种撕裂状态会被整串匹配误判成"口是对的"。
     * 需要判断时请分别读三条属性再比字段。
     */
    fun snapshot(): String = "瞬态=" + systemGet(CarAdbHotspotDefaults.ADB_PORT_PROPERTY) +
        " 持久=" + systemGet(CarAdbHotspotDefaults.ADB_PORT_PROPERTY_PERSIST) +
        " adbd=" + systemGet(CarAdbHotspotDefaults.ADB_STATE_PROPERTY)

    /**
     * 【2026-10-16】**只读**结构化快照：四条值分开返回，供按字段判断。
     *
     * 与 [snapshot] 的区别见 [PortSnapshot] 的注释 —— 需要判断时一律用本方法。
     */
    fun snapshotNow(): PortSnapshot = PortSnapshot(
        transient = systemGet(CarAdbHotspotDefaults.ADB_PORT_PROPERTY),
        persist = systemGet(CarAdbHotspotDefaults.ADB_PORT_PROPERTY_PERSIST),
        vendor = systemGet(CarAdbHotspotDefaults.ADB_VENDOR_TCPIP_PROPERTY),
        adbdState = systemGet(CarAdbHotspotDefaults.ADB_STATE_PROPERTY),
    )

    /**
     * **等 adbd 到 `running`**（有界轮询，2026-10-16 新增）。
     *
     * ## 为什么必须有
     *
     * `ctl.stop adbd` → `ctl.start adbd`（或 `adb root`）之后，`init.svc.adbd`
     * 会依次经过 `stopping` → `starting` → `running`。旧实现**写命令后立刻读一次**，
     * 撞上中间态就得出"adbd 没起来 ⇒ 未通过验证"的结论 —— 现场表现就是三条属性
     * 都已读回 5556、界面却报失败。
     *
     * ## 边界
     *
     * - 总等待有界（[ADBD_WAIT_ATTEMPTS] × [ADBD_WAIT_INTERVAL_MILLIS] ≈ 2 秒），
     *   绝不把用户可感知的开启动作拖长；
     * - 读不到属性（空串）时**不把它当 running**，直接退出轮询、如实报"未读到"；
     * - 只读 `init.svc.adbd`，不写任何东西。
     */
    private fun awaitAdbdRunning(): PortSnapshot {
        var latest = snapshotNow()
        var attempt = 0
        while (attempt < ADBD_WAIT_ATTEMPTS &&
            latest.adbdState != AdbPortVerdict.ADBD_RUNNING &&
            latest.adbdState.isNotEmpty()
        ) {
            try {
                Thread.sleep(ADBD_WAIT_INTERVAL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            latest = snapshotNow()
            attempt += 1
        }
        return latest
    }

    /**
     * 【新增 2026-10-15】**只读**：adbd 此刻真正在听的端口（瞬态 `service.adb.tcp.port`）。
     *
     * 为什么需要单独一个入口：自建热点的**一次性自愈**（见
     * `AdbShellSession.repairDedicatedPort`）必须先分清两种失败——
     *   ① 「车机重启把 adbd 打回了厂商口」⇒ 值得自愈（写 5556 + 重启 adbd）；
     *   ② 「口本来就是 5556，只是连不上」⇒ 自愈帮不上，不该白白重启 adbd 掐断别人。
     * 判据只能来自这条瞬态属性。
     *
     * ⚠️ 读不到（ROM 禁读 / 属性不存在）返回**空串** = "未验证"，
     * **不等于 5555**，也**不当作 5556** —— 调用方按自己的口径决定怎么处理。
     */
    fun currentPort(): String = systemGet(CarAdbHotspotDefaults.ADB_PORT_PROPERTY)

    /**
     * 读系统属性，读不到（ROM 禁读/属性不存在）返回空串——
     * 与参考实现 `FxBridge.getProperties(key, "")` 的默认值一致。
     */
    private fun systemGet(key: String): String = runCatching {
        SystemPropertiesBridge.get(key, "")
    }.getOrDefault("")

    /**
     * 写系统属性。
     *
     * ⚠️ 故意**不吞异常**：调用方 [enable] 用一个大 try 包住并降级，
     * 这样 detail 里能带上真实的失败类型（`SecurityException` 还是别的），
     * 比在这里静默掉更有诊断价值。
     */
    private fun systemSet(key: String, value: String) {
        SystemPropertiesBridge.set(key, value)
    }

    /**
     * 等 adbd 起来的轮询节奏：8 × 250ms ≈ 2 秒上限。
     *
     * 取值理由：实车 `adb root` 的重启观察是 2.5 秒量级（见
     * `AdbShellRunner` 的 `ROOT_FIRST_WAIT_MILLIS`），纯 `ctl.stop`+`ctl.start`
     * 明显更快。2 秒足够覆盖后者，又不会让「永久开启 ADB」这颗按钮的等待变长。
     */
    private const val ADBD_WAIT_ATTEMPTS = 8
    private const val ADBD_WAIT_INTERVAL_MILLIS = 250L
}
