package com.dbtool.carplay.bridge.bluetooth

/**
 * 构造「定向断蓝牙链路」的 `app_process` 命令（**纯函数**，单测钉引号/顺序）。
 *
 * ## 命令形状
 *
 * ```
 * <prefix>/system/bin/sh -c 'CLASSPATH=<apk> /system/bin/app_process /system/bin <类> <verb> <mac>'
 * ```
 *
 * - `prefix` = `""`（shell uid 2000）或 `"su 0 "`（root uid 0）；
 * - 与仓库既有手法一致：P2P 的 `app_process` / `LynkCapabilityProbe.appProcessCommand`
 *   （`PATCHES.md:4543-4560`、PATCH-0072）；
 * - 整个 `sh -c` 脚本用**单引号**包住；脚本内部的单引号按 POSIX 规则
 *   写成 `'\''`，避免 APK 路径里出现空格/引号时被拆词。
 *
 * ## 为什么顺序不能变
 *
 * `CLASSPATH=` 必须是 `app_process` 的**环境变量前缀**，`app_process` 的
 * 第一个参数固定是 `/system/bin`（父目录），随后才是主类名与工具参数。
 * 调换任何一处都会变成「找不到主类」或「把 /system/bin 当成主类」。
 */
internal object BtLinkReleaseCommand {

    /** 随 APK 发出的工具入口（`app_process` 用 `CLASSPATH=<apk>` 加载）。 */
    const val TOOL_CLASS = "com.dbtool.carplay.bridge.bluetooth.BtLinkReleaseTool"

    fun probe(prefix: String, apkPath: String, mac: String?): String =
        build(prefix, apkPath, verb = "probe", mac = mac)

    fun disconnect(prefix: String, apkPath: String, mac: String): String =
        build(prefix, apkPath, verb = "disconnect", mac = mac)

    fun forbid(prefix: String, apkPath: String, mac: String): String =
        build(prefix, apkPath, verb = "forbid", mac = mac)

    fun allow(prefix: String, apkPath: String, mac: String): String =
        build(prefix, apkPath, verb = "allow", mac = mac)

    fun status(prefix: String, apkPath: String, mac: String?): String =
        build(prefix, apkPath, verb = "status", mac = mac)

    fun build(prefix: String, apkPath: String, verb: String, mac: String?): String {
        // 参数**不在脚本内部单独加引号**：整个脚本已经由 `sh -c '...'` 包住，
        // 里面的空格天然安全；脚本内部再套一层单引号会被外层转义成 `'\''`，
        // 形状变成 `CLASSPATH='\''/path'\''`（虽然能跑，但与方案/排查口径不一致）。
        // 路径中真的含单引号时，[shellQuote] 会在外层统一转义。
        val script = buildString {
            append("CLASSPATH=")
            append(apkPath)
            append(" /system/bin/app_process /system/bin ")
            append(TOOL_CLASS)
            append(' ')
            append(verb)
            if (!mac.isNullOrBlank()) {
                append(' ')
                append(mac)
            }
        }
        return prefix + "/system/bin/sh -c " + shellQuote(script)
    }

    /** POSIX shell 单引号转义：`'` → `'\''`。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
