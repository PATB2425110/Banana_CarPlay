package com.dbtool.carplay.bridge.developer

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个**滚动落盘**的文本日志文件（PATCH-0095，2026-10-11）。
 *
 * ## 为什么不能"写满就从头覆盖"
 *
 * 上游 `com.shilapi.xcertplay.SessionLogFile` 的写法是：
 *
 * ```kotlin
 * if (bytesWritten + bytes.size > MAX_BYTES) output = file.outputStream()  // 从头覆盖
 * ```
 *
 * 于是文件一旦写满，**最先写进去的那一段（也就是"出事前后"）就被抹掉了** ——
 * 而排查要的恰恰是那一段。这里改成滚动：`name` → `name.1` → `name.2` ……，
 * 每份文件都是一个**完整的时间片**，最老的才丢。
 *
 * 抽成独立类是为了能被纯 JVM 单测覆盖（[CarLogcatRecorder] 要起 `logcat` 进程，
 * 单测里跑不起来）。本类只用 `java.io`，没有任何 Android 依赖。
 *
 * ## flush 节奏
 *
 * 按行数（[FLUSH_EVERY_LINES]）或时间（[FLUSH_INTERVAL_MILLIS]）flush，
 * 而不是每行都 flush：车机刷屏时每秒上千行，每行一次 `write` 系统调用纯属浪费。
 * 代价是最多丢 500ms 的尾巴 —— 可以接受。
 */
internal class RollingLogFileWriter(
    private val logDir: File,
    private val baseName: String,
    private val maxFileBytes: Long,
    private val maxFiles: Int,
) {

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var currentBytes = 0L
    private var pendingSinceFlush = 0
    private var lastFlushAt = 0L
    private var lines = 0L
    private var rotations = 0

    /** 累计写入行数。 */
    fun lineCount(): Long = synchronized(lock) { lines }

    /** 因写满而滚动的次数（不含 [roll] 那次"归档上一进程的尾巴"）。 */
    fun rotationCount(): Int = synchronized(lock) { rotations }

    /** 当前正在写的文件；还没写过东西时为 null。 */
    fun currentFile(): File? = synchronized(lock) { if (lines > 0L) File(logDir, baseName) else null }

    /**
     * 归档：把现有的 `name` 挪成 `.1`，`.1` 挪成 `.2`……最老的丢掉。
     *
     * `CarLogcatRecorder.start()` 会先调一次 —— 上一进程（或上一次会话）留下的
     * 尾巴就变成 `.1` 而不是被新内容盖掉。
     */
    fun roll() = synchronized(lock) { rollLocked(countAsRotation = false) }

    /** 写一行（自动补换行）。写满时自动滚动。 */
    fun appendLine(line: String) = synchronized(lock) { writeLocked(line) }

    fun close() = synchronized(lock) {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    // ------------------------------------------------------------------ 内部

    private fun writeLocked(line: String) {
        if (writer == null) openLocked()
        val size = line.toByteArray(StandardCharsets.UTF_8).size + 1
        // ⚠️ 先滚动**再取 writer**：滚动会关掉旧 writer、换成新文件。
        // 顺序反了就会继续往那个已被重命名的旧句柄里写（内容跑到 `.1` 上）。
        if (currentBytes + size > maxFileBytes) {
            rollLocked(countAsRotation = true)
            openLocked()
        }
        val active = writer ?: return
        runCatching {
            active.write(line)
            active.newLine()
        }.onFailure {
            runCatching { active.close() }
            writer = null
        }
        currentBytes += size
        lines++
        pendingSinceFlush++
        val now = System.currentTimeMillis()
        if (pendingSinceFlush >= FLUSH_EVERY_LINES || now - lastFlushAt >= FLUSH_INTERVAL_MILLIS) {
            runCatching { writer?.flush() }
            pendingSinceFlush = 0
            lastFlushAt = now
        }
    }

    private fun openLocked() {
        val file = File(logDir, baseName)
        runCatching {
            file.parentFile?.mkdirs()
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8),
            )
            currentBytes = 0L
            pendingSinceFlush = 0
            lastFlushAt = System.currentTimeMillis()
        }.onFailure { writer = null }
    }

    private fun rollLocked(countAsRotation: Boolean) {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
        runCatching { File(logDir, "$baseName.${maxFiles - 1}").delete() }
        for (index in maxFiles - 2 downTo 1) {
            val from = File(logDir, "$baseName.$index")
            if (from.isFile) runCatching { from.renameTo(File(logDir, "$baseName.${index + 1}")) }
        }
        val current = File(logDir, baseName)
        if (current.isFile && current.length() > 0L) {
            runCatching { current.renameTo(File(logDir, "$baseName.1")) }
        }
        currentBytes = 0L
        if (countAsRotation) rotations++
    }

    companion object {
        /** 攒够这么多行就 flush 一次。 */
        private const val FLUSH_EVERY_LINES = 64

        /** 或距上次 flush 超过这么久也 flush（保证"最后一句话"能落盘）。 */
        private const val FLUSH_INTERVAL_MILLIS = 500L

        /**
         * 列出某个滚动日志的**全部**文件，**最老的在前**。
         *
         * 诊断包要用它把整族文件按时间顺序拷进 zip（`name.3` → `name.2` →
         * `name.1` → `name`）。放在这里而不是让调用方自己拼文件名，
         * 是因为"索引越大越老"这个约定必须只有一处实现。
         */
        fun familyOf(logDir: File, baseName: String, maxFiles: Int): List<File> =
            (maxFiles - 1 downTo 0).map { index ->
                if (index == 0) File(logDir, baseName) else File(logDir, "$baseName.$index")
            }.filter { it.isFile && it.length() > 0L }
    }
}

/**
 * 把 `logcat` **持续滚动落盘**，供诊断包取用（PATCH-0095，2026-10-11）。
 *
 * ## 为什么必须有它（实车证据）
 *
 * 1.0.51 那份诊断包里 `logcat_system.txt` **只覆盖 5 秒**
 * （`17:08:15~17:08:20`），而断连发生在 `17:06:45` —— 排查"为什么断"时
 * 系统侧**一条证据都没有**。
 *
 * 原因不是采集写错了，而是 `logcat -d` 的语义：
 *
 * ```
 * logcat -d -b main,system,crash -t 8000   # 只 dump 环形缓冲区的尾部
 * ```
 *
 * 车机上 `VehicleHal` 高频刷屏，环形缓冲区（每 buffer 几百 KB）**几秒就被冲光**，
 * 于是"采集那一刻往前几秒"之外什么都不剩。这不是把 `-t` 调大能解决的：
 * 缓冲区里没有的东西，参数再大也取不出来。
 *
 * ⇒ 唯一可靠的做法是**在问题发生的同时就把日志写进文件**（文件是线性的，
 * 不会被后来的行冲掉），诊断包再从文件里取。
 *
 * ## 两个实例、两种用途
 *
 * 由 `CarAppServices` 起两个：
 *
 * | 实例 | 过滤 | 文件 | 跑多久 | 用途 |
 * | --- | --- | --- | --- | --- |
 * | 应用日志 | `--pid=<本进程>` | `logs/xcertplay.log` | **随进程常驻** | 本 App 自己说了什么（不被刷屏标签淹没，能覆盖很久） |
 * | 系统日志 | 不过滤 | `logs/logcat_system_rolling.txt` | **仅会话期间** | 含 `system_server` / SurfaceFlinger 的系统视角 |
 *
 * ⚠️ 两份**必须分开**：系统那一份在 `VehicleHal` 刷屏时只能覆盖几十秒，
 * 而应用那一份只有我们自己的行、能覆盖很久。合成一份必然二选一丢东西。
 *
 * ⚠️ 两份的**运行时长**也不同，理由见 `CarAppServices.startAppLogging()` 的成本表：
 * 应用日志稀疏（几 KB/s）⇒ 常驻，否则空闲期的后台静默更新**永远采不到**；
 * 系统日志约 100KB/s ⇒ 只在会话期间跑，否则等于持续磨损车机的 eMMC。
 *
 * ## 权限与代价
 *
 * - 应用 uid 默认只读得到"自己那部分"日志；本车 `ro.debuggable=1`，
 *   所以**系统日志也读得到**（这也是 `logcat_system.txt` 能有 8010 行的原因）。
 *   非 debuggable 的车机上系统那一份只会剩我们自己的行 —— 那是如实的，不是 bug。
 * - 单线程 + 缓冲读，实测代价可忽略。
 *
 * ## 失败边界
 *
 * `logcat` 起不来（被 SELinux 拒 / 二进制不存在）**只记一行日志并退出**，
 * 绝不重试风暴、绝不抛异常。诊断包那边会看到文件不存在，如实报"没采到"。
 */
internal class CarLogcatRecorder(
    private val logDir: File,
    /** 主文件名（滚动时变成 `$baseName.1`、`$baseName.2`…）。 */
    private val baseName: String,
    /**
     * 追加给 `logcat` 的参数。
     *
     * 应用日志传 `--pid=<本进程>`；系统日志传空表。
     */
    private val extraArgs: List<String> = emptyList(),
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val log: (String) -> Unit = {},
) {

    private val writer = RollingLogFileWriter(logDir, baseName, maxFileBytes, maxFiles)

    /** 记录器当前是否在跑。 */
    private val running = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var thread: Thread? = null

    /** 最近一次异常（null = 没出过）。 */
    @Volatile
    private var lastError: String? = null

    /** 本次启动时刻（毫秒，0 = 还没起过）。 */
    @Volatile
    private var startedAtMillis = 0L

    /**
     * 开始记录。**幂等**：已经在跑时直接返回。
     *
     * 启动时会先把上一进程留下的同名文件归档成 `.1`（见 [RollingLogFileWriter.roll]），
     * 这样"上次运行的最后一段"不会被新内容盖掉。
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        logDir.mkdirs()
        writer.roll()
        thread = Thread({ readLoop() }, "dbplay-logcat-$baseName").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 停止记录并回收子进程。**幂等**。
     *
     * 必须先 `destroy()` 让 `readLine()` 拿到 EOF —— 否则读线程会一直阻塞在
     * `readLine()` 上，`join()` 也等不回来。
     */
    fun stop() {
        running.set(false)
        runCatching { process?.destroy() }
        thread?.let { runCatching { it.join(STOP_JOIN_MILLIS) } }
        thread = null
        writer.close()
    }

    fun isRunning(): Boolean = running.get() && thread?.isAlive == true

    /** 诊断包清单要的运行时快照（纯文本，直接进 `collection_manifest.txt`）。 */
    fun describe(): String = buildString {
        append("$baseName: ")
        append(if (isRunning()) "运行中" else "未运行")
        append("，累计 ${writer.lineCount()} 行")
        val rotations = writer.rotationCount()
        if (rotations > 0) append("，滚动 $rotations 次")
        if (startedAtMillis > 0L) {
            append("，本次启动于 ${formatStamp(startedAtMillis)}")
        }
        lastError?.let { append("，最近异常：$it") }
    }

    // ------------------------------------------------------------------ 内部

    private fun readLoop() {
        var proc: Process? = null
        try {
            val command = buildList {
                add("logcat")
                add("-v")
                add("threadtime")
                add("-b")
                add("main,system,crash")
                addAll(extraArgs)
            }
            proc = ProcessBuilder(command).redirectErrorStream(true).start()
            process = proc
            startedAtMillis = System.currentTimeMillis()
            writer.appendLine("===== ${formatStamp(startedAtMillis)} 开始记录：${command.joinToString(" ")} =====")
            log("滚动日志启动：${command.joinToString(" ")} → ${logDir.absolutePath}/$baseName")

            BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    writer.appendLine(line)
                }
            }
        } catch (error: Throwable) {
            lastError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
            log("滚动日志异常退出（$baseName）：$lastError")
        } finally {
            running.set(false)
            writer.close()
            proc?.let { runCatching { it.destroy() } }
            process = null
            log("滚动日志停止：$baseName（累计 ${writer.lineCount()} 行）")
        }
    }

    companion object {
        /**
         * **应用日志**的文件名。
         *
         * 刻意沿用上游那个名字（`xcertplay.log`）：它是 `CarPlayHostActivity`
         * 时代就在文档与排障笔记里出现的名字，改名只会让"这个文件到底是哪个"
         * 多一层歧义。注意**内容的生产者变了** —— 旧版由上游宿主写（且那个宿主
         * 在本产品里从未被实例化 ⇒ 文件从不存在），现在由本类写。
         */
        const val APP_LOG_NAME = "xcertplay.log"

        /**
         * 全系统滚动日志的文件名。
         *
         * 刻意**不叫** `logcat_system.txt`：那个名字是诊断包里"采集那一刻的
         * 缓冲区快照"，两者语义不同，同名会让人分不清拿到的是哪一种。
         */
        const val SYSTEM_LOG_NAME = "logcat_system_rolling.txt"

        /** 应用日志单个文件上限：只有本 App 的行，1MB 能覆盖很久。 */
        const val APP_MAX_FILE_BYTES = 1L * 1024 * 1024

        /** 系统日志单个文件上限：刷屏时 2MB 大约几十秒，保留 4 份就是几分钟。 */
        const val SYSTEM_MAX_FILE_BYTES = 2L * 1024 * 1024

        /** 保留的文件份数（含当前那一份）。 */
        const val DEFAULT_MAX_FILES = 4

        private const val DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024

        /** `stop()` 等待读线程收尾的上限。 */
        private const val STOP_JOIN_MILLIS = 1_000L

        private const val STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"

        /**
         * 毫秒时间戳 → `yyyy-MM-dd HH:mm:ss.SSS`。
         *
         * ⚠️ **每次都新建** `SimpleDateFormat`，不复用静态实例：本方法有两个
         * 调用方在**不同线程**上 —— [readLoop]（读线程，写文件头）与
         * [describe]（诊断包采集线程，报"本次启动于…"）。
         * `SimpleDateFormat` 不是线程安全的，共用一个实例会偶发打出乱码或抛异常。
         */
        private fun formatStamp(millis: Long): String =
            SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(millis))
    }
}
