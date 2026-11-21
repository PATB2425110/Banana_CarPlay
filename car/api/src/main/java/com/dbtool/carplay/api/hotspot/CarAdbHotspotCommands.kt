package com.dbtool.carplay.api.hotspot

/**
 * 车机侧 shell **命令字符串**的构造。
 *
 * ## 为什么单独抽出来
 *
 * 这些命令要通过 `adb shell <一串字符串>` 发到设备上，**由设备侧的 shell 再解析一次**。
 * 于是"提权前缀 + 复合语句 + 引号"三者的组合极容易悄悄错掉，而且错法都很隐蔽：
 *
 * - `su 0 for f in ...; do ...; done` ⇒ `su` 把 `for` 当**命令名**去找 ⇒ `for: not found`。
 *   调用方若不检查，就会把"探测不到"误读成"设备上没有"（本项目踩过）。
 * - 复合语句不套 `sh -c '...'` ⇒ 只有第一条命令以 root 跑，`;` 后面那些**退回原 uid**，
 *   而且**不会报错**——这是最难发现的一种。
 *
 * 抽成纯函数之后，这些引号与参数顺序就能被单测钉住，不必上车试。
 *
 * ## 约定
 *
 * 所有函数都接受**提权前缀**（`""` = adbd 本身就是 root，`"su 0 "` = 需要 `su 0`），
 * 而不是自己去判断——前缀由 `AdbShellSession` 探测后统一给出。
 */
object CarAdbHotspotCommands {

    /**
     * 探测 `hostapd` 到底在哪个候选路径上。
     *
     * ## 为什么必须带前缀
     *
     * `/vendor/bin/hw/` 下的二进制在部分 ROM 上是 `0700 root:root`。那时以
     * `shell` uid 跑 `test -x` **看不到它**，于是把"有 hostapd"误判成"没有"。
     * 而参考实现根本不探测——它直接 `su 0 /vendor/bin/hw/hostapd <conf>` 以 root 执行。
     *
     * ## 为什么是 `sh -c '<脚本>'`
     *
     * `su 0 <cmd>` 把后面的参数当**一个命令**执行。`for` 不是命令名，
     * 所以 `su 0 for f in ...` 只会得到 `for: not found`。
     * 必须把整段复合语句交给**内层** `sh -c`：单引号由设备侧 shell 剥掉，
     * 脚本整体作为 `sh -c` 的**一个**参数。
     *
     * ⚠️ 脚本内部对 `$f` 用的是**双引号**（`test -x "$f"`）：它由内层 `sh` 展开，
     * 而不是由设备侧 shell 展开（那样会在外层就被替换成空串）。
     */
    fun hostapdProbe(prefix: String, candidates: List<String>): String {
        val list = candidates.joinToString(" ")
        val script = "for f in $list; do if test -x \"\$f\"; then echo \"OK \$f\"; fi; done; true"
        return "$prefix/system/bin/sh -c '$script'"
    }

    /**
     * 只读列出**正在运行的 hostapd** 的 pid 与启动参数。
     *
     * ## 它回答什么问题
     *
     * 2026-09-30 实车：Wi-Fi 网卡（`wlan0`）上广播的是**别人的** `car`，
     * 而整份诊断包里**没有一处**说明它是谁起的、读的哪份配置。结果只能靠事后
     * 逐行翻 `iw dev` 才发现，而"是谁"这个问题当时答不上来。
     *
     * `hostapd` 的 `argv[1]` **就是它读的配置文件**，所以
     * `/proc/<pid>/cmdline` 是唯一能直接把这件事问出来的地方。
     *
     * ## 为什么是 `sh -c '<脚本>'`（与 [hostapdProbe] 同一个坑）
     *
     * `su 0 <cmd>` 把后面的参数当**一个命令**执行，而 `for` 不是命令名 ⇒
     * `for: not found`，然后我们又会把"读不到"误读成"没有 hostapd 在跑"——
     * 正好把这次要查的东西查漏。必须把整段复合语句交给**内层** `sh -c`。
     *
     * ⚠️ 脚本内部的 `$p` / `$(pidof …)` 用**反斜杠转义**：它们要由内层 `sh`
     * 展开，而不是在设备侧 shell 那层就被替换掉。
     *
     * 末尾的 `true` 让整条命令返回 0：`pidof` 一个都没命中时不该被记成失败
     * （"没有 hostapd 在跑"是**结论**，不是错误）。
     *
     * ## ⚠️ Bug 修复（2026-10-04）：改为 base64 传递
     *
     * 原实现把脚本直接塞进 `sh -c '<脚本>'`，但脚本内部为了 `tr '\000' ' '`
     * 又用了一对**单引号**——它与外层的定界单引号配对，把脚本从中间截断，
     * 导致内层 shell 收到半截命令，实测报：
     * ```
     * < /proc/$p/cmdline)"; done; true: syntax error: unmatched '('
     * /system/bin/sh: syntax error: unexpected 'do'
     * ```
     * 结果"读不到占用者"被误读成"没有 hostapd 在跑"。
     *
     * 现在把整段脚本 **base64 编码**后只走字母数字与 `+/=`，不含任何引号，
     * 由内层 `sh` 解码再执行——三层（Kotlin 字符串 / 设备侧 shell / 内层 sh）
     * 的转义问题一次性消除。
     */
    fun hostapdOwners(prefix: String): String {
        val script = "for p in \$(pidof hostapd); do " +
            "echo \"pid \$p \$(tr '\\000' ' ' < /proc/\$p/cmdline)\"; done; true"
        return "$prefix/system/bin/sh -c 'echo " + base64(script) + " | base64 -d | sh'"
    }

    /**
     * 只读探测**已部署**的 busybox 能不能分发 applet。
     *
     * 注意这里**不带提权前缀**：`/data/local/tmp/busybox` 是 `shell_data_file`
     * 且被 `chmod 0755` 过，shell uid 直接可执行；而且自检承诺"只读"，
     * 不该为了探测去动系统状态。
     */
    fun stagedBusyboxProbe(path: String): String = "$path udhcpd --help"

    /**
     * 把脚本编成 base64（用于跨层传递，见 [hostapdOwners] 的 Bug 修复说明）。
     *
     * 不用 `android.util.Base64`：这个模块要能在纯 JVM 单测里跑，
     * `java.util.Base64`（API 26+）在设备（Android 9 = API 28）上可用。
     */
    private fun base64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- 停止热点（2026-10-11）

    /**
     * **体面地**停 hostapd：发 `SIGTERM`。
     *
     * ## 为什么不能再直接 `killall -s KILL`（车主现场："点了关闭，手机上还能搜到"）
     *
     * hostapd 收到 `SIGTERM` 时会走 nl80211 的 **`stop_ap`** —— 由它把 BSS 拆掉，
     * 驱动随即停止广播（SSID 从手机的扫描列表里消失）。
     * 而 `SIGKILL` **不给它这个机会**：进程是没了，但**驱动仍停在 AP 模式**，
     * beacon 照旧发 —— 表现就是"应用里显示已关闭，手机上还搜得到自建热点"。
     *
     * ⇒ 顺序固定为：先 [stopHostapdGracefully]，等一小段；只有进程还活着才 [killHostapdAndUdhcpd]。
     * 最后的判据是 [interfaceInfo] + [iwDevShowsApMode]（网卡是否真的不再是 AP）。
     */
    fun stopHostapdGracefully(prefix: String): String = "$prefix/system/bin/killall -s TERM hostapd"

    /** 兜底强杀（仅当 TERM 之后进程仍在时用；它**不会**拆 AP，故必须再校验网卡模式）。 */
    fun killHostapdAndUdhcpd(prefix: String): String =
        "$prefix/system/bin/killall -s KILL hostapd busybox"

    /** 还在跑的 hostapd pid；输出为空 = 已经没了。 */
    fun hostapdPids(prefix: String): String = "$prefix/system/bin/pidof hostapd"

    /** 这张网卡当前的模式信息（里面有 `type AP` 就是还在当 AP）。 */
    fun interfaceInfo(prefix: String, iface: String): String = "$prefix/system/bin/iw dev $iface info"

    /**
     * 把网卡放下再抬起 —— **唯一**能强制驱动退出 AP 模式、停掉 beacon 的动作。
     *
     * 只在"hostapd 已被 KILL、而 [interfaceInfo] 仍显示 AP"时才用（那种情况下
     * 除了复位接口没有别的办法把 SSID 从空中收回去）。
     */
    fun bounceInterface(prefix: String, iface: String): String =
        "$prefix/system/bin/ip link set $iface down; $prefix/system/bin/ip link set $iface up"

    /**
     * `iw dev <iface> info` 的输出里是否仍显示这张网卡在当 AP。
     *
     * 纯函数、可单测：**"点了关闭手机还能搜到"这类回归就靠它判**。
     */
    fun iwDevShowsApMode(output: String): Boolean = output.contains("type AP")
}
