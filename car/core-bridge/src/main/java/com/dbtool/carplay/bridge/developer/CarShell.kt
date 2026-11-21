package com.dbtool.carplay.bridge.developer

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 执行 shell 命令并取回 stdout（best-effort）。
 *
 * 为什么不用 `android.os.ShellCommand` / `Runtime.exec("getprop")` 直接写：
 * - 需要**超时**。`getprop` / `logcat` / `dumpsys` 在车机上偶发卡死，
 *   采集流程不能被一条命令拖住（参考实现为此专门设了整体采集预算）。
 * - 需要**不抛异常**。车机上 SELinux 会直接拒绝一部分命令，
 *   返回空串让上层降级，比抛异常中断整趟采集好得多。
 */
internal object CarShell {

    /**
     * @param timeoutMillis 超时后强杀进程并返回已读到的内容（可能为空）。
     * @return stdout 文本；命令不存在 / 被拒 / 超时都返回已读内容（通常为空串）。
     */
    fun run(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            // 读完再 waitFor：直接 waitFor 会在输出量大于管道缓冲区时死锁
            // （logcat -d 的输出轻松超过 64KB）。
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val buffer = CharArray(8192)
                val deadline = System.currentTimeMillis() + timeoutMillis
                while (true) {
                    if (!reader.ready()) {
                        if (System.currentTimeMillis() > deadline) break
                        if (!process.isAlive && !reader.ready()) break
                        Thread.sleep(READ_POLL_MILLIS)
                        continue
                    }
                    val read = reader.read(buffer)
                    if (read < 0) break
                    output.append(buffer, 0, read)
                    if (System.currentTimeMillis() > deadline) break
                }
            }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            output.toString()
        } catch (error: Throwable) {
            process?.destroyForcibly()
            ""
        }
    }

    /** 读取 `getprop <key>`；读不到返回空串。 */
    fun getProp(key: String): String =
        run("getprop $key", PROP_TIMEOUT_MILLIS).trim()

    /**
     * 这段输出是不是**权限拒绝**（而不是真的内容）。
     *
     * ## 为什么必须认出来
     *
     * 本工程跑 shell 用的是**应用自己的 uid**（不是那条有 root 的 adb 通道），
     * 所以 `dumpsys package` 这类需要 `android.permission.DUMP` 的命令**必然被拒**：
     *
     * ```
     * Permission Denial: can't dump PackageManager from pid=…, uid=… due to
     * missing android.permission.DUMP permission
     * ```
     *
     * 而拒绝文本会被原样写进 `system/package_status.txt`，`collection_manifest.txt`
     * 还把它当有效字节数报出去 —— 服务端拿不到任何包版本，**却看不出是失败**。
     * 2026-09-29 的实车包里这个文件 310 字节全是这两行拒绝，是典型的
     * "错了也不报错"。认出来之后由调用方**保留原文并附一句说明**，
     * 既不再伪装成数据，也不丢掉"到底是哪种失败"的证据。
     */
    internal fun looksLikePermissionDenial(output: String): Boolean =
        output.contains("Permission Denial") &&
            (output.contains("android.permission.DUMP") || output.contains("permission"))

    private const val DEFAULT_TIMEOUT_MILLIS = 8_000L
    private const val PROP_TIMEOUT_MILLIS = 4_000L
    private const val CLOSE_TIMEOUT_MILLIS = 1_000L
    private const val READ_POLL_MILLIS = 20L
}
