package com.dbtool.carplay.ecarx.media

import java.io.File
import java.nio.file.Files

/**
 * 把 loopback 连接的「对端端口」反查成进程（best-effort）。
 *
 * 车机上有多个进程可能来取封面（媒体卡片、仪表、dock……），而
 * `LocalCoverHttpServer` 只看到 `127.0.0.1:<随机端口>`。这里用只读的 `/proc`
 * 做一次反向查找：`/proc/net/tcp{,6}` 里 local_port == 对端端口的 socket
 * → 拿到 inode → 扫 `/proc/<pid>/fd` 找 `socket:[inode]` → 该 pid 就是对端。
 *
 * 约束：
 * - 不执行 `su` / `ps`，只读 `/proc`；
 * - 任何一步失败都返回 null（跨 uid 读 `/proc/<pid>/fd` 在 Android 上
 *   通常被 SELinux 拒绝，这是预期内的）；
 * - 有 pid 数、单文件字节数、fd 探测数和总耗时上限，避免拖慢请求线程。
 *
 * `reader` 可注入，单测因此不需要真的读 `/proc`：约定
 * 目录路径返回「每行一个子项」，符号链接返回链接目标，普通文件返回内容。
 */
internal object LoopbackPeerLookup {

    private const val PROC_ROOT = "/proc"
    private const val MAX_PORT = 65_535
    private const val MAX_PIDS = 400
    private const val MAX_FILE_BYTES = 8 * 1024
    private const val MAX_FDS_PER_PID = 256
    private const val MAX_MILLIS = 300L

    private val WHITESPACE = Regex("\\s+")
    private val PROC_NET_TCP_PATHS = arrayOf("$PROC_ROOT/net/tcp", "$PROC_ROOT/net/tcp6")

    /**
     * 尽量把 (127.0.0.1, peerPort) 映射成 pid/包名。
     *
     * @return 形如 `pid=1234 comm=com.geely.mediacard`；找不到/无权限返回 null。
     */
    fun resolve(peerPort: Int, reader: (String) -> String? = ::readProcPath): String? {
        if (peerPort <= 0 || peerPort > MAX_PORT) return null

        val inodes = findSocketInodes(peerPort, reader)
        if (inodes.isEmpty()) return null

        val pids = reader(PROC_ROOT)
            ?.lineSequence()
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.take(MAX_PIDS)
            ?: return null

        val deadlineNanos = System.nanoTime() + MAX_MILLIS * 1_000_000L
        for (pid in pids) {
            if (System.nanoTime() > deadlineNanos) return null

            val fdDir = "$PROC_ROOT/$pid/fd"
            val fdEntries = reader(fdDir) ?: continue
            var probes = 0
            for (entry in fdEntries.lineSequence()) {
                val fd = entry.trim()
                if (fd.isEmpty()) continue
                if (++probes > MAX_FDS_PER_PID) break

                val target = reader("$fdDir/$fd") ?: continue
                val inode = parseSocketInode(target) ?: continue
                if (inode !in inodes) continue

                return "pid=$pid comm=${processName(pid, reader)}"
            }
        }
        return null
    }

    /** `/proc/net/tcp{,6}` 里 local_port 匹配的 socket inode（可能有多个）。 */
    private fun findSocketInodes(peerPort: Int, reader: (String) -> String?): Set<Long> {
        val inodes = LinkedHashSet<Long>()
        for (path in PROC_NET_TCP_PATHS) {
            val text = reader(path) ?: continue
            for (line in text.lineSequence()) {
                val cols = line.trim().split(WHITESPACE)
                if (cols.size < 10) continue
                val local = cols[1]
                val colon = local.lastIndexOf(':')
                if (colon <= 0) continue
                if (local.substring(colon + 1).toIntOrNull(16) != peerPort) continue
                val inode = cols[9].toLongOrNull() ?: continue
                if (inode > 0L) inodes.add(inode)
            }
        }
        return inodes
    }

    /** 进程名优先取 cmdline（完整包名），拿不到再退回 comm（内核截断到 15 字符）。 */
    private fun processName(pid: Int, reader: (String) -> String?): String {
        val cmdline = reader("$PROC_ROOT/$pid/cmdline")
            ?.substringBefore('\u0000')
            ?.trim()
            .orEmpty()
        if (cmdline.isNotEmpty()) return sanitize(cmdline)

        val comm = reader("$PROC_ROOT/$pid/comm")?.trim().orEmpty()
        return if (comm.isEmpty()) "unknown" else sanitize(comm)
    }

    /** socket 的 fd 链接形如 `socket:[424242]`。 */
    private fun parseSocketInode(target: String): Long? {
        val text = target.trim()
        if (!text.startsWith("socket:[")) return null
        return text.removePrefix("socket:[").removeSuffix("]").toLongOrNull()
    }

    /** 日志是单行 key=value，进程名里的空白/控制字符统一换成 `_`。 */
    private fun sanitize(raw: String): String = buildString {
        for (ch in raw.take(64)) {
            append(if (ch.isWhitespace() || ch.isISOControl()) '_' else ch)
        }
    }

    /**
     * 真实 `/proc` 读取。目录返回「每行一个子项」，符号链接返回链接目标，
     * 普通文件最多读 [MAX_FILE_BYTES] 字节；任何异常都返回 null。
     */
    private fun readProcPath(path: String): String? {
        return try {
            val file = File(path)
            when {
                path == PROC_ROOT -> file.list()?.take(MAX_PIDS)?.joinToString("\n")
                Files.isSymbolicLink(file.toPath()) ->
                    Files.readSymbolicLink(file.toPath()).toString()
                file.isDirectory -> file.list()?.take(MAX_FDS_PER_PID)?.joinToString("\n")
                else -> file.inputStream().use { stream ->
                    val buffer = ByteArray(MAX_FILE_BYTES)
                    var total = 0
                    while (total < buffer.size) {
                        val read = stream.read(buffer, total, buffer.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    if (total <= 0) "" else String(buffer, 0, total, Charsets.UTF_8)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
