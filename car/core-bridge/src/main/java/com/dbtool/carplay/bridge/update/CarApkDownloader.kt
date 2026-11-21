package com.dbtool.carplay.bridge.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 下载。
 *
 * 三条必须守住的规则：
 *
 * 1. **先写临时文件、下载完整后再改名**。否则一次断网会留下一个
 *    长度不对的 `.apk`，而它看起来"存在"，用户点安装得到的是
 *    "解析包时出现问题"——错误现场离真正的原因非常远。
 * 2. **校验长度**。服务端返回 `Content-Length` 时必须逐字节对上，
 *    这是能拿到的最便宜的完整性校验（服务端没提供 sha256 字段）。
 * 3. **进度按已写字节算**。拿不到 `Content-Length` 时（服务端没给）
 *    不假装有进度：回调 0 后直接给 1，而不是编一个假百分比。
 */
internal class CarApkDownloader {

    data class Result(
        val success: Boolean,
        val file: File?,
        val message: String,
    )

    /**
     * @param onProgress 0f..1f，在**调用线程**触发（实现方负责切主线程）。
     */
    fun download(
        url: String,
        targetFile: File,
        timeoutSeconds: Int,
        onProgress: (Float) -> Unit,
    ): Result {
        if (url.isBlank()) return Result(false, null, "服务端没有提供下载地址")
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return Result(false, null, "无法创建下载目录")
        }
        val tempFile = File(targetFile.parentFile, targetFile.name + ".part")
        tempFile.delete()

        var activeConnection: HttpURLConnection? = null
        return try {
            val opened = openWithRedirects(url, timeoutSeconds)
            val connection = opened.connection
            activeConnection = connection
            val status = opened.status
            if (status !in 200..299) {
                return Result(false, null, "下载失败：服务端返回 HTTP $status")
            }

            val total = connection.contentLengthLong
            var written = 0L
            var lastReported = 0f
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0L) {
                            val progress = (written.toFloat() / total).coerceIn(0f, 1f)
                            // 每 2% 报一次，避免几百次回调把主线程刷爆
                            if (progress - lastReported >= 0.02f) {
                                lastReported = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            connection.disconnect()
            activeConnection = null

            if (total > 0L && written != total) {
                tempFile.delete()
                return Result(false, null, "下载不完整：期望 $total 字节，实际 $written 字节")
            }
            if (written <= 0L) {
                tempFile.delete()
                return Result(false, null, "下载到的文件为空")
            }
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            onProgress(1f)
            Result(true, targetFile, "下载完成（${targetFile.length() / 1024} KB）")
        } catch (error: Throwable) {
            tempFile.delete()
            Result(
                success = false,
                file = null,
                message = "下载失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim(),
            )
        } finally {
            activeConnection?.disconnect()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** 建立连接的超时。APK 可能在很慢的网络上，但"连上"本身不该等太久。 */
        const val CONNECT_TIMEOUT_SECONDS = 20

        /** 跳转最多次数。正常最多 1 跳（http→https），给 5 跳纯粹是防环。 */
        const val MAX_REDIRECTS = 5
    }

    /**
     * 打开连接，**手动跟随重定向**。
     *
     * ## 为什么不能只靠 `instanceFollowRedirects = true`
     *
     * `HttpURLConnection` 的自动跟随**只在同协议内生效**：`http→http`、`https→https` 会跟，
     * 但 `http→https`（跨协议）**默认不跟** —— `getInstanceFollowRedirects()` 为真也一样，
     * 它直接把 301 原样返回。2026-10-09 线上事故就是这条：定向推送里存的是
     * `http://apps.sosun.cc/...`，服务器 301 跳到 `https://`，车机拿到 301 就报
     * 「下载失败：服务端返回 HTTP 301」。
     *
     * 这里显式读 `Location` 自己跳一次，顺带也覆盖 302/303/307/308。
     * 相对 `Location`（如 `/a/b.apk`）按 RFC 3986 相对当前 URL 解析。
     */
    private fun openWithRedirects(url: String, timeoutSeconds: Int): Opened {
        var current = url
        var hops = 0
        while (true) {
            val connection = openOnce(current, timeoutSeconds)
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            val isRedirect = status in 301..303 || status == 307 || status == 308
            if (isRedirect && !location.isNullOrBlank() && hops < MAX_REDIRECTS) {
                val next = resolveLocation(current, location)
                if (isHttpsDowngrade(current, next)) {
                    connection.disconnect()
                    throw SecurityException("拒绝不安全的 HTTPS → HTTP 下载跳转")
                }
                connection.disconnect()
                current = next
                hops++
                continue
            }
            return Opened(connection, status)
        }
    }

    private fun openOnce(url: String, timeoutSeconds: Int): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("http", ignoreCase = true) || parsed.protocol.equals("https", ignoreCase = true)) {
            "仅允许 HTTP(S) 下载地址"
        }
        return (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(parsed) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_SECONDS * 1000
            readTimeout = timeoutSeconds * 1000
            // 同协议跳转仍交给它自动处理；跨协议由 openWithRedirects 兜。
            // **不要设成 false** —— 那会把同协议跳转也变成不跟随。
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            setRequestProperty("User-Agent", "DBPlay-Updater")
        }
    }

    /**
     * 把 `Location` 解析成绝对 URL。
     *
     * 服务端给的可能是绝对地址（`https://…`）或绝对路径（`/static/…`），
     * 甚至相对路径。用 `java.net.URI.resolve()` 处理，别自己拼字符串
     * （会漏掉端口、查询串、基准路径）。
     */
    private fun resolveLocation(base: String, location: String): String = try {
        java.net.URI(base).resolve(location).toString()
    } catch (_: Throwable) {
        location // 解析不了就按绝对地址试一次，交给下一轮的响应码兜底
    }

    private data class Opened(val connection: HttpURLConnection, val status: Int)
}

/** Prevent a TLS-protected APK request from being redirected to cleartext HTTP. */
internal fun isHttpsDowngrade(fromUrl: String, toUrl: String): Boolean = try {
    URL(fromUrl).protocol.equals("https", ignoreCase = true) &&
        URL(toUrl).protocol.equals("http", ignoreCase = true)
} catch (_: Throwable) {
    false
}
