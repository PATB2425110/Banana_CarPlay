package com.dbtool.carplay.bridge.developer

/**
 * 用 **app 自身 uid 的 `sh`** 兜底读 VIN（[CarVinReader] 的最后一级）。
 *
 * ## 来源
 *
 * 移植 DBtool3.0 的 `ShellVinFallbackProvider`（`module-fandong`）。那里的做法是
 * "已经有 shell 权限就跑 `getprop` / `settings` / `dumpsys` / `cmd` / `logcat`"，
 * 这在 DBPlay 上同样成立——`sh -c getprop` 走应用自己的 uid，`getprop` 对
 * 绝大多数属性是可读的，`settings get global` 也不需要特权。
 *
 * ## 与 DBtool3.0 的两处**故意**差异
 *
 * 1. **不碰 adb / root 通道。** DBtool3.0 的 `ShellExecutor.runAsShell` 会降级到
 *    "root → su → adb shell → app"，DBPlay 对应的是 `AdbShellSession`。这里**明令不用**：
 *    `AdbShellSession.ensure()` 会跑整套"开通车机 ADB → 起 server → 自连 → 提权探测"，
 *    等于替用户改了一项没同意的系统设置（见 [CarSystemShell] 的契约）。
 *    一条 VIN 读不到只是少一个字段，**不值得**去动车机设置。
 * 2. **一次往返。** 所有命令拼进一条 `sh -c`，一次进程把结果全取回，再在内存里筛。
 *    DBtool3.0 是每条命令一次 exec；那样一次 VIN 读取要起十几个进程，
 *    在车机上是秒级开销。
 *
 * ## 预算
 *
 * [BRIEF_TIMEOUT_MILLIS] / [FULL_TIMEOUT_MILLIS] 是**每一条命令**的超时（不是整链）。
 * 超过就强杀进程、只用已读到的部分——`logcat -d` 在日志量大时能跑几十秒，
 * 不能让它把上报线程拖住。DBtool3.0 给 `logcat` 单独留了 12s；DBPlay 只有在
 * **非首屏**路径（`briefBudget = false`）才会走到 `logcat` 那一步。
 *
 * 整链之外还有一层保险：[CarVinReader.readWithSource] 用
 * `CarVinReader.TOTAL_TIMEOUT_MILLIS`（6s）兜住整个探测（含本类）。
 *
 * ## 线程
 *
 * 阻塞（起进程 + 等输出），只能在 worker 线程调用。本工程没有协程。
 */
internal object VinShellFallback {

    /** 快路径：只跑 `getprop` 类命令，实测 < 1s。 */
    const val BRIEF_TIMEOUT_MILLIS = 4_000L

    /** 慢路径：允许 `logcat -d` / `dumpsys`（`logcat` 实测可达 12s）。 */
    const val FULL_TIMEOUT_MILLIS = 18_000L

    /**
     * 属性键**复用** [CarVinReader.VIN_PROP_KEYS]，不再抄一份。
     *
     * 两份清单一旦漂移，就会出现"进程内读得到、shell 读不到"（或反之）这种
     * 只有换车机才暴露的问题。DBtool3.0 里就是两处各写一份、顺序还不一致
     * （`ShellVinFallbackProvider` 把 `ro.vehicle.vin` 排第二）。
     */
    private val PROP_KEYS = CarVinReader.VIN_PROP_KEYS

    private val SETTINGS_KEYS = CarVinReader.SETTINGS_KEYS

    private val SDV_FLOW_VIN_FILE = CarVinReader.SDV_FLOW_VIN_FILE

    /**
     * 读一次。
     *
     * @param briefBudget true = 只跑快命令（`getprop`），不跑 `settings` /
     *   `dumpsys` / `cmd` / `logcat`；false = 允许全链路（首屏之外才用）。
     * @return 命中的 VIN；没读到返回**空串**。调用方 `CarVinReader` 用
     *   `isValid` 判空——空串与非法串等价，都会继续降级。
     */
    fun read(briefBudget: Boolean): String {
        // ① getprop：把 8 个键合成一条命令，一次进程读全。
        //    `getprop <key>` 读不到会输出空行；用 ` ; ` 串联，回读时按行对位。
        val propOut = CarShell.run(buildPropCommand(), timeoutMillis = BRIEF_TIMEOUT_MILLIS)
        parseGetprop(propOut)?.let { return it }

        if (briefBudget) return ""

        // ② Settings.Global（需要 `settings` 二进制，绝大多数车机都有）
        val settingsOut = CarShell.run(
            SETTINGS_KEYS.joinToString(" ; ") { "settings get global $it" },
            timeoutMillis = BRIEF_TIMEOUT_MILLIS,
        )
        CarVinReader.extractVinCandidate(settingsOut)?.let { return it }

        // ③ ECarX SDV 文件节点
        val fileOut = CarShell.run("cat $SDV_FLOW_VIN_FILE 2>/dev/null", BRIEF_TIMEOUT_MILLIS)
        CarVinReader.extractVinCandidate(fileOut)?.let { return it }

        // ④ VHAL 三连查：dumpsys → cmd car_service（propertyId 必须是 Hex）→ sysfs
        val vhalOut = CarShell.run(
            "dumpsys vehicle 2>/dev/null | grep -iE 'vin|vehicleId' | head -3 ; " +
                "cmd car_service get-property-value 11100100 0 2>/dev/null ; " +
                "cat /sys/class/vehicle/vin 2>/dev/null || " +
                "cat /data/vendor/vehicle/vin 2>/dev/null",
            timeoutMillis = BRIEF_TIMEOUT_MILLIS,
        )
        CarVinReader.extractVinCandidate(vhalOut)?.let { return it }

        // ⑤ 最后手段：从系统日志里抓 17 位 VIN（Flyme 的系统应用会把它写进 logcat）。
        //    ⚠️ 实测可达 12s，因此放在最末、且只在宽裕预算下跑。
        val logOut = CarShell.run(
            "logcat -d -v brief 2>/dev/null | " +
                "grep -Eio '\\b[A-HJ-NPR-Z0-9]{17}\\b' | head -5",
            timeoutMillis = FULL_TIMEOUT_MILLIS,
        )
        CarVinReader.extractVinCandidate(logOut)?.let { return it }

        return ""
    }

    /**
     * 构造"一次读 8 个属性"的命令。
     *
     * 输出被 `\n` 分隔成固定行序，[parseGetprop] 据此对位——**不要**改成
     * 并行/乱序输出，否则行与键会错位、把模型名当成 VIN。
     */
    private fun buildPropCommand(): String =
        PROP_KEYS.joinToString(" ; ") { "getprop $it" }

    /** 按行回读，逐行做合法性判定。 */
    private fun parseGetprop(output: String): String? {
        val lines = output.split('\n')
        for (i in PROP_KEYS.indices) {
            val line = lines.getOrNull(i)?.trim().orEmpty()
            if (CarVinReader.isValid(line)) return line
        }
        // 行数对不上（例如 `getprop` 被判权限拒绝）时，退化成全文扫一遍
        return CarVinReader.extractVinCandidate(output)
    }
}
