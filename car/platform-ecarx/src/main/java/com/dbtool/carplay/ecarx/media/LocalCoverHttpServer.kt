package com.dbtool.carplay.ecarx.media

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 内嵌 HTTP 服务器 —— 把当前封面以 `http://127.0.0.1:<port>/cover.jpg` 暴露出去。
 *
 * ## 为什么非要有它（这是仪表盘封面能不能出来的关键）
 *
 * 车机媒体中心（MediaCenterUI，跑在**另一个进程**、uid 10026）去取封面时，
 * 只认 `http(s)://` 或 `content://`。`file://` **跨进程读不到**——
 * 它读不了我们 `com.dbtool.carplay` 私有目录下的任何东西。
 *
 * 参考实现（DBTOOL2.0，吉利车机实车验证）确认帆书走的是公网
 * `https://cdn-bs-images.dushu365.com/...`；而它自己用**本地 loopback HTTP**
 * 同样可行：车机端从 `127.0.0.1` 拉本机端口，Android 允许同设备跨进程访问。
 *
 * ## URL 为什么要带 `?v=<tag>`
 *
 * 车机端用 Glide 之类的库加载封面，会按 URL 做缓存。切歌时若 URL 不变，
 * 它会**直接把上一首的封面缓存拿出来**——现象就是"歌名变了、封面还是上一首"。
 * 所以 URL 带一个由调用方给的版本标记：**同一首歌 tag 不变**（URL 稳定，
 * 不会反复重载），**切歌时 tag 变**（URL 变，强制重新拉）。
 *
 * ## 实现
 *
 * 纯 JDK `ServerSocket`，零依赖。只实现最小 HTTP/1.0：
 * 读请求行 + 丢掉请求头，然后回 200/404/503。
 *
 * ⚠️ 只监听 `127.0.0.1`，**不是** `0.0.0.0`：封面没有任何理由暴露给车机局域网。
 */
internal class LocalCoverHttpServer {

    private val currentCover = AtomicReference<ByteArray?>(null)
    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = -1

    @Volatile
    private var acceptThread: Thread? = null

    /**
     * 启动服务器，监听 `127.0.0.1` 上的随机端口。
     *
     * 幂等：已在跑时直接返回当前端口。
     *
     * @return 实际绑定的端口（> 0）；失败返回 -1。
     */
    @Synchronized
    fun start(): Int {
        if (running.get()) return port
        return try {
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
            serverSocket = socket
            port = socket.localPort
            running.set(true)
            Log.i(TAG, "封面 HTTP 服务已启动：http://$LOOPBACK:$port$COVER_PATH")
            acceptThread = Thread({ acceptLoop(socket) }, "dbplay-cover-http").apply {
                isDaemon = true
                start()
            }
            port
        } catch (error: Throwable) {
            Log.w(TAG, "封面 HTTP 服务启动失败：$error")
            port = -1
            -1
        }
    }

    /** 停止服务器。幂等。 */
    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = -1
        acceptThread = null
        currentCover.set(null)
    }

    /** 当前封面 URL；未启动返回 null。 */
    fun coverUrl(tag: String): String? {
        if (!running.get() || port <= 0) return null
        return "http://$LOOPBACK:$port$COVER_PATH?v=$tag"
    }

    /**
     * 写入当前封面字节。
     *
     * @param bytes 原始图片字节（iPhone 给的一般就是 JPEG）。null = 清空。
     * @param tag URL 版本标记（建议用"歌曲身份"或切歌计数）。
     * @return 写入后的 URL；失败或 [bytes] 为 null 时返回 null。
     */
    fun updateCover(bytes: ByteArray?, tag: String): String? {
        if (bytes == null || bytes.isEmpty()) {
            currentCover.set(null)
            return null
        }
        if (!running.get() && start() < 0) return null
        currentCover.set(bytes)
        val url = coverUrl(tag)
        Log.i(TAG, "封面已写入：$url（${bytes.size} 字节）")
        return url
    }

    // --------------------------------------------------------------- 内部

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (error: Throwable) {
                // close() 会让 accept 抛异常，那是正常退出路径。
                if (running.get()) Log.w(TAG, "accept 失败：$error")
                return
            }
            // 每个请求一个线程：车机端可能并发拉几次（主卡片 + 仪表 + dock）。
            Thread({ handleClient(client) }, "dbplay-cover-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MILLIS
            // 先偷看请求头再原样退回去：TLS ClientHello 与明文 HTTP 都只碰
            // 最前面这几个字节，协议处理逻辑完全不变。
            val input = PushbackInputStream(client.inputStream, HEAD_PEEK_BYTES)
            logConnection(client, input)
            val requestLine = readLine(input)
            if (requestLine == null) {
                client.close()
                return
            }
            // 丢掉剩下的请求头（读到空行为止）。
            while (true) {
                val line = readLine(input)
                if (line == null || line.isEmpty()) break
            }
            if (requestLine.startsWith("GET $COVER_PATH") || requestLine.startsWith("GET /cover")) {
                val bytes = currentCover.get()
                if (bytes == null || bytes.isEmpty()) {
                    writeAndClose(client, RESPONSE_503)
                    return
                }
                val header = String.format(RESPONSE_200_TEMPLATE, bytes.size)
                val out = client.outputStream
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
                Log.d(TAG, "200 OK $COVER_PATH（${bytes.size} 字节）")
            } else {
                writeAndClose(client, RESPONSE_404)
                Log.d(TAG, "404 Not Found：$requestLine")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "处理请求异常：$error")
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * 每个连接打一行（取证用）：来源、是否像 TLS、请求头前 16 字节 hex。
     *
     * 只读 `available()` 允许的字节并全部 unread，不阻塞等满 40 字节，
     * 因此不会改变「客户端发多少就读多少」的既有行为。
     */
    private fun logConnection(client: Socket, input: PushbackInputStream) {
        val head = ByteArray(HEAD_PEEK_BYTES)
        var count = 0
        try {
            val first = input.read(head, 0, 1)
            if (first <= 0) {
                Log.i(TAG, "封面连接：src=${client.inetAddress.hostAddress}:${client.port} tls=false head= ascii=")
                return
            }
            count = first
            while (count < HEAD_PEEK_BYTES && input.available() > 0) {
                val read = input.read(head, count, HEAD_PEEK_BYTES - count)
                if (read <= 0) break
                count += read
            }
        } catch (error: Throwable) {
            // 偷看失败不影响后面的正常读取路径。
            Log.i(TAG, "封面连接：src=${client.inetAddress.hostAddress}:${client.port} tls=false head= ascii=（读取异常：$error）")
            return
        } finally {
            if (count > 0) runCatching { input.unread(head, 0, count) }
        }

        val tls = count >= 2 && head[0] == TLS_RECORD_HANDSHAKE && head[1] == TLS_VERSION_MAJOR
        Log.i(
            TAG,
            "封面连接：src=${client.inetAddress.hostAddress}:${client.port} tls=$tls " +
                "head=${hexOf(head, minOf(count, HEAD_HEX_BYTES))} ascii=${printableOf(head, count)}",
        )

        // 放连接线程里做，且内部有 pid 数/耗时上限；解析不到就是 unknown。
        val peer = runCatching { LoopbackPeerLookup.resolve(client.port) }.getOrNull()
        Log.i(TAG, if (peer != null) "封面连接对端：$peer" else "封面连接对端：peer=unknown")
    }

    private fun hexOf(bytes: ByteArray, count: Int): String = buildString(count * 2) {
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    /** 不可打印字节替换成 `.`，避免把控制字符灌进 logcat。 */
    private fun printableOf(bytes: ByteArray, count: Int): String = buildString(count) {
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xFF
            append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
    }

    private fun writeAndClose(socket: Socket, response: String) {
        try {
            val out: OutputStream = socket.getOutputStream()
            out.write(response.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {
            // 客户端提前断开是常态，忽略。
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        return try {
            val builder = StringBuilder(128)
            var c = input.read()
            while (c != -1) {
                if (c == '\n'.code) {
                    var line = builder.toString()
                    if (line.endsWith("\r")) line = line.dropLast(1)
                    return line
                }
                builder.append(c.toChar())
                c = input.read()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val TAG = "DBPlay-CoverHttp"
        const val LOOPBACK = "127.0.0.1"
        const val COVER_PATH = "/cover.jpg"
        const val BACKLOG = 4
        const val SOCKET_TIMEOUT_MILLIS = 2_000
        const val HEAD_PEEK_BYTES = 40
        const val HEAD_HEX_BYTES = 16
        const val HEX_DIGITS = "0123456789abcdef"

        const val TLS_RECORD_HANDSHAKE: Byte = 0x16
        const val TLS_VERSION_MAJOR: Byte = 0x03

        const val RESPONSE_200_TEMPLATE =
            "HTTP/1.0 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                // 车机端自己会按 URL 缓存；这里再声明 no-cache 是双保险——
                // 换歌时 URL 已经变了，不该有中间层拿旧响应糊弄它。
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Connection: close\r\n" +
                "Content-Length: %d\r\n\r\n"

        const val RESPONSE_404 =
            "HTTP/1.0 404 Not Found\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 9\r\n\r\nNot Found"

        /** 还没拿到封面（歌刚切、封面还在传）。车机端据此可以稍后重试。 */
        const val RESPONSE_503 =
            "HTTP/1.0 503 Service Unavailable\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n\r\n"
    }
}
