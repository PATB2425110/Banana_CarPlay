package com.dbtool.carplay.bridge.mfi

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 [CarMfiSigner] 暴露成一个只监听 `127.0.0.1` 的最小 HTTP 服务，
 * 以匹配 upstream `RemoteMfiAuthenticationClient` 的契约。
 *
 * ## 为什么要绕这一圈
 *
 * 上游 `CarPlayController.startMfi()` 的 `when (config.mfiTarget)` 只有三个分支，
 * 而且 `mfiSession` 是 `private`，没有任何外部注入入口。
 * 但 `MfiTarget.REMOTE` 的实现 `RemoteMfiAuthenticationClient` **只是一个普通 HTTP 客户端**——
 * 于是可以把它当作注入点：在自己这边起一个本地服务，把"远程"指到 `127.0.0.1`。
 *
 * 这样做的收益是**上游零改动**：不用改枚举、不用改 `when`、不用改任何上游文件，
 * 将来 `git merge upstream/master` 依然零冲突。
 *
 * ## 安全：为什么令牌是强制的
 *
 * 监听 `127.0.0.1` 并不能阻止**车机上的其他 App**连接过来。
 * 一个不带鉴权的本地 MFi 服务等于把配件私钥变成一个"签名预言机"——
 * 同车机上任何一个应用都能拿它对任意摘要签名。
 *
 * 所以本类**每次启动都生成一个新的 256 位随机令牌**（不接受外部传入、不允许为空），
 * 并要求请求带 `Authorization: Bearer <token>`。
 * 令牌只存在于本进程内存与 `CarPlayRuntimeConfig.remoteMfiToken` 里，不落盘、不写日志。
 * 上游客户端本来就会发这个头，正好用上。
 *
 * 另外：
 * - 单线程 `accept` 循环天然把并发压到 1，配合 socket 读超时，
 *   恶意连接无法长期占用服务；
 * - 请求头与请求体都有字节上限。
 *
 * ## 线程与生命周期
 *
 * 后台守护线程，`close()` 后不可重启（换端口重启一个新的实例）。
 */
class CarMfiLoopbackServer(private val signer: CarMfiSigner) : Closeable {

    /** 本次实例的随机访问令牌。调用方必须把它填进 `remoteMfiToken`。 */
    val token: String = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).toBase64()

    private val serverSocket: ServerSocket =
        ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST)).apply {
            // 让 accept 循环能周期性醒来检查关闭标志，而不是永久阻塞。
            soTimeout = ACCEPT_POLL_MILLIS
        }

    /** 系统分配的端口。用 0 让内核选，避免与其他 App 抢占固定端口。 */
    val port: Int get() = serverSocket.localPort

    /** 直接可以填进 `CarPlayRuntimeConfig.remoteMfiServer` 的地址。 */
    val endpoint: String get() = "http://$LOOPBACK_HOST:$port"

    @Volatile
    private var closed = false

    private val servedRequests = AtomicInteger(0)

    private val worker = Thread(::serve, "car-mfi-loopback").apply {
        isDaemon = true
        start()
    }

    /** 已应答的请求数（含错误应答），仅用于诊断日志。 */
    val requestCount: Int get() = servedRequests.get()

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        runCatching { worker.join(CLOSE_JOIN_MILLIS) }
    }

    // ------------------------------------------------------------------ 服务循环

    private fun serve() {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                // close() 会关掉 ServerSocket，accept 随之抛异常——这是正常退出路径。
                return
            }
            try {
                socket.use(::handleConnection)
            } catch (_: Throwable) {
                // 单个连接出错不能影响后续连接。
                //
                // 这里刻意吞掉 Throwable 而不是 IOException：一旦这个线程死了，
                // 后续所有请求都会变成"连接被拒绝"，而上游只会报一句
                // 看不出原因的 MFi 失败。宁可漏掉一个连接的应答，也不能让服务下线。
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MILLIS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val head = readHead(input) ?: return
        val request = parseRequest(head) ?: return respond(output, 400, "Bad Request", detail("请求行无法解析"))

        if (request.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        // 请求体提前结束说明客户端中断了连接；超限的情况在 parseRequest 里已经拦掉。
        val body = readBody(input, request.contentLength)
            ?: return respond(output, 400, "Bad Request", detail("请求体不完整"))

        if (!authorized(request)) {
            return respond(output, 401, "Unauthorized", detail("缺少或错误的访问令牌"))
        }

        when {
            request.path == PATH_RESET && request.method == "POST" ->
                respond(output, 200, "OK", """{"ok":true}""")

            request.path == PATH_CERTIFICATE && request.method == "GET" ->
                respondCertificate(output)

            request.path == PATH_SIGN && request.method == "POST" ->
                respondSignature(output, body)

            request.path in ROUTED_PATHS ->
                respond(output, 405, "Method Not Allowed", detail("${request.method} 不被 ${request.path} 接受"))

            else ->
                respond(output, 404, "Not Found", detail("未知端点 ${request.path}"))
        }
    }

    private fun authorized(request: ParsedRequest): Boolean =
        request.headers["authorization"] == "Bearer $token"

    private fun respondCertificate(output: OutputStream) {
        val certificate = try {
            signer.certificatePayload()
        } catch (error: CarMfiIdentityException) {
            // 身份缺失/不可用。用 503 而不是 500：这是"暂时不可用"，
            // 而且上游会把 detail 原样带进异常消息给用户看。
            return respondUnavailable(output, error)
        }
        val protocolMajor = try {
            signer.protocolMajor
        } catch (error: CarMfiIdentityException) {
            return respondUnavailable(output, error)
        }
        val body = buildString(certificate.size * 2 + 160) {
            append("{\"protocolMajor\":").append(protocolMajor)
            append(",\"type\":\"mfi\"")
            append(",\"certificate\":\"").append(certificate.toBase64()).append('"')
            append(",\"certificateSha256\":\"").append(certificate.sha256Hex()).append('"')
            append('}')
        }
        respond(output, 200, "OK", body)
    }

    private fun respondSignature(output: OutputStream, body: String) {
        val challenge = extractChallenge(body)
            ?: return respond(output, 400, "Bad Request", detail("请求体缺少 challenge 字段"))
        val signature = try {
            signer.signRaw(challenge)
        } catch (error: CarMfiIdentityException) {
            return respondUnavailable(output, error)
        } catch (error: IllegalArgumentException) {
            return respond(output, 400, "Bad Request", detail(error.message ?: "challenge 无效"))
        } catch (error: Exception) {
            return respond(output, 500, "Internal Server Error", detail("签名失败：${error.javaClass.simpleName}"))
        }
        respond(output, 200, "OK", "{\"signature\":\"${signature.toBase64()}\"}")
    }

    /**
     * 身份不可用时的统一应答。
     *
     * 消息直接来自 [CarMfiIdentityException]，是面向用户的中文说明
     * （例如"未安装本地认证身份"），可以原样显示。
     */
    private fun respondUnavailable(output: OutputStream, error: CarMfiIdentityException) =
        respond(
            output,
            503,
            "Service Unavailable",
            detail(error.message ?: "本地认证身份不可用"),
        )

    // ------------------------------------------------------------------ 协议解析

    private class ParsedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val contentLength: Int,
    )

    /**
     * 读到空行为止（`CRLF CRLF`）。
     *
     * 逐个字节扫描而不是用 `BufferedReader.readLine()`：后者会在缓冲里多读，
     * 把本该属于请求体的字节吃掉，导致 `Content-Length` 读不满。
     */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(512)
        var matched = 0
        while (buffer.size() <= MAX_HEAD_BYTES) {
            val byte = input.read()
            if (byte < 0) return null
            buffer.write(byte)
            matched = when {
                byte == CR && matched == 0 -> 1
                byte == LF && matched == 1 -> 2
                byte == CR && matched == 2 -> 3
                byte == LF && matched == 3 -> return buffer.toString(Charsets.ISO_8859_1.name())
                byte == CR -> 1
                else -> 0
            }
        }
        throw IOException("请求头超过 $MAX_HEAD_BYTES 字节上限")
    }

    private fun parseRequest(head: String): ParsedRequest? {
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
            if (name == "content-length") {
                contentLength = value.toIntOrNull() ?: return null
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
            }
        }
        return ParsedRequest(method, path, headers, contentLength)
    }

    private fun readBody(input: InputStream, length: Int): String? {
        if (length == 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) return null
            read += count
        }
        return String(buffer, Charsets.UTF_8)
    }

    /**
     * 从 `{"challenge":"<base64>","requestId":"..."}` 里取出 challenge。
     *
     * 刻意不写通用 JSON 解析器：这是回环服务，请求体由上游客户端生成，
     * 字段值一定是纯 base64 字母表（`A-Za-z0-9+/=`），不含任何转义。
     * 用严格正则反而更安全——带反斜杠或引号的输入直接判为非法。
     */
    private fun extractChallenge(body: String): ByteArray? {
        val match = CHALLENGE_PATTERN.find(body) ?: return null
        val decoded = try {
            Base64.getDecoder().decode(match.groupValues[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        return decoded.takeIf { it.isNotEmpty() && it.size <= MAX_CHALLENGE_BYTES }
    }

    // ------------------------------------------------------------------ 应答写出

    private fun respond(output: OutputStream, status: Int, reason: String, body: String) {
        servedRequests.incrementAndGet()
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString(160) {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            // 上游客户端每个请求都新建连接，明确关闭可以避免连接复用带来的状态纠缠。
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
    }

    private fun detail(message: String): String = "{\"detail\":\"${message.escapeJson()}\"}"

    private fun String.escapeJson(): String = buildString(length + 8) {
        for (character in this@escapeJson) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append("%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun ByteArray.sha256Hex(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        /** 只监听回环地址，不暴露到局域网。 */
        const val LOOPBACK_HOST = "127.0.0.1"

        const val PATH_RESET = "/mfi/reset"
        const val PATH_CERTIFICATE = "/mfi/certificate"
        const val PATH_SIGN = "/mfi/sign"

        private val ROUTED_PATHS = setOf(PATH_RESET, PATH_CERTIFICATE, PATH_SIGN)

        /** 令牌长度。256 位足够，且不会让请求头变得离谱。 */
        private const val TOKEN_BYTES = 32

        private const val BACKLOG = 4
        private const val ACCEPT_POLL_MILLIS = 500
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024
        private const val MAX_CHALLENGE_BYTES = 128

        private const val CR = '\r'.code
        private const val LF = '\n'.code

        /** 严格匹配纯 base64 值的 challenge 字段。 */
        private val CHALLENGE_PATTERN =
            Regex("\"challenge\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"")
    }
}
