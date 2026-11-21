package com.dbtool.carplay.bridge.developer

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 诊断包上传（multipart/form-data）。
 *
 * 用 `HttpURLConnection` 手写 multipart，而不是引入 OkHttp：
 * - `:car:core-bridge` 目前**没有任何第三方网络依赖**，为一个手动上传加一个
 *   OkHttp + Okio 不划算（参考实现 DBTool 用了 OkHttp，那是它自己的选择）；
 * - 这里只发一次请求、不需要连接池/拦截器/异步队列。
 *
 * 契约（逐字对齐 `dbapi/interfaces/rest/device_logs.py`）：
 * `POST /api/device/log/upload`，表单字段
 * `deviceId` / `file` 必填，`vin` / `fingerprint` / `appVersion` / `token` /
 * `taskId` / `androidVersion` / `systemVersion` / `deviceModel` / `product` / `source` 可选。
 */
internal class CarDeviceLogUploader {

    data class UploadResult(
        /** HTTP 2xx 且业务 `success=true`。 */
        val success: Boolean,
        /** 服务端业务码：`OK` / `DROPPED_OLD_VERSION` / … */
        val code: String,
        /** 给用户看的中文说明。 */
        val message: String,
        /** 成功时的日志 ID，形如 `LOG-1759...-ab12cd34`。 */
        val logId: String,
        /** 原始响应体，排障时原样显示。 */
        val raw: String = "",
    )

    /**
     * 把诊断包里的文本日志 POST 到日志查看服务。
     *
     * 服务端认 JSON：`appId` / `kind` / `device` / `app` / `time` / `log`。
     * 单条上限 8MB，这里把正文截到 [MAX_LOG_CHARS]，优先保留清单和最近的 logcat。
     */
    fun uploadDiagnostic(
        url: String,
        appId: String,
        device: String,
        model: String,
        androidRelease: String,
        app: String,
        note: String,
        zipFile: File,
        timeoutSeconds: Int,
        onProgress: (Float) -> Unit,
    ): UploadResult {
        if (!zipFile.exists() || zipFile.length() <= 0L) {
            return UploadResult(false, "FILE_MISSING", "诊断包不存在或为空", "")
        }
        val log = runCatching { readDiagnosticText(zipFile) }.getOrElse { error ->
            return UploadResult(
                false,
                "READ_FAILED",
                "读取诊断包失败：${error.message.orEmpty()}",
                "",
            )
        }
        onProgress(0.2f)
        val payload = JSONObject().apply {
            put("appId", appId)
            put("kind", "diag")
            put("device", device)
            put("model", model)
            put("android", androidRelease)
            put("app", app)
            put("time", STAMP.format(Date()))
            if (note.isNotBlank()) put("note", note)
            put("log", log)
        }.toString()
        return try {
            val connection = (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(URL(url)) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "DBPlay-LogUpload")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }
            onProgress(1f)
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (status in 200..299) {
                UploadResult(true, "OK", "已上传到日志服务", "", body)
            } else {
                UploadResult(false, "HTTP_$status", "日志服务拒绝（HTTP $status）", "", body)
            }
        } catch (error: Throwable) {
            UploadResult(
                success = false,
                code = "NETWORK_ERROR",
                message = "上传失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim(),
                logId = "",
            )
        }
    }

    /**
     * @param onProgress 0f..1f，在调用线程触发。
     */
    fun upload(
        baseUrl: String,
        path: String,
        fields: List<Pair<String, String>>,
        file: File,
        timeoutSeconds: Int,
        onProgress: (Float) -> Unit,
    ): UploadResult {
        if (!file.exists() || file.length() <= 0L) {
            return UploadResult(false, "FILE_MISSING", "诊断包不存在或为空", "")
        }
        val boundary = "----DBPlay${UUID.randomUUID().toString().replace("-", "")}"
        return try {
            val url = URL(baseUrl.trimEnd('/') + path)
            val connection = (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(url) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                // ⚠️ 必须用**固定长度**流式发送，不能用 chunked：
                // Werkzeug（Flask 的 WSGI 实现）对 chunked 请求体的 multipart
                // 解析支持很差，`request.files` 会拿不到文件。包可能几十 MB，
                // 又不能整个读进内存，所以先算出精确长度。
                setFixedLengthStreamingMode(contentLength(boundary, fields, file))
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DBPlay-DeveloperMode")
            }

            connection.outputStream.use { out ->
                fields.forEach { (name, value) ->
                    out.write(partHeader(boundary, name, null, null))
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write(CRLF_BYTES)
                }
                out.write(
                    partHeader(
                        boundary = boundary,
                        name = "file",
                        fileName = file.name,
                        contentType = "application/octet-stream",
                    ),
                )
                writeFileWithProgress(out, file, onProgress)
                out.write(CRLF_BYTES)
                out.write("--$boundary--$CRLF".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()

            parse(status, body)
        } catch (error: Throwable) {
            UploadResult(
                success = false,
                code = "NETWORK_ERROR",
                message = "上传失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim(),
                logId = "",
            )
        }
    }

    /**
     * 解析服务端响应。
     *
     * ⚠️ `success=true` 不等于"日志进库了"：命中老版本门禁时服务端会返回
     * `success=true + code=DROPPED_OLD_VERSION`（这是为了避免老客户端反复重试），
     * 但**包没有落盘**。开发者模式下必须把它当失败报出来，否则用户会以为传成功了，
     * 到后台却找不到记录。
     */
    private fun parse(status: Int, body: String): UploadResult {
        if (body.isBlank()) {
            return UploadResult(
                success = status in 200..299,
                code = "HTTP_$status",
                message = if (status in 200..299) "上传完成（服务端未返回内容）" else "服务端返回 HTTP $status",
                logId = "",
            )
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return UploadResult(
                success = status in 200..299,
                code = "HTTP_$status",
                message = "服务端返回无法解析的内容（HTTP $status）",
                logId = "",
                raw = body,
            )

        val success = json.optBoolean("success", false)
        val code = json.optString("code")
        val serverMessage = json.optString("message")
        val logId = json.optJSONObject("data")?.optString("id").orEmpty()

        return when {
            code == "DROPPED_OLD_VERSION" -> UploadResult(
                success = false,
                code = code,
                message = serverMessage.ifEmpty { "服务端判定当前版本过旧，已丢弃本次上传" },
                logId = "",
                raw = body,
            )
            success -> UploadResult(true, code.ifEmpty { "OK" }, "上传成功", logId, body)
            else -> UploadResult(
                success = false,
                code = code.ifEmpty { "HTTP_$status" },
                message = serverMessage.ifEmpty { "上传被服务端拒绝（HTTP $status）" },
                logId = "",
                raw = body,
            )
        }
    }

    /** 精确算出 multipart 报文体长度，供 `setFixedLengthStreamingMode` 使用。 */
    private fun contentLength(boundary: String, fields: List<Pair<String, String>>, file: File): Long {
        var length = 0L
        fields.forEach { (name, value) ->
            length += partHeader(boundary, name, null, null).size
            length += value.toByteArray(Charsets.UTF_8).size
            length += CRLF.length
        }
        length += partHeader(boundary, "file", file.name, "application/octet-stream").size
        length += file.length()
        length += CRLF.length
        length += "--$boundary--$CRLF".toByteArray(Charsets.UTF_8).size
        return length
    }

    private fun partHeader(
        boundary: String,
        name: String,
        fileName: String?,
        contentType: String?,
    ): ByteArray = buildString {
        append("--").append(boundary).append(CRLF)
        append("Content-Disposition: form-data; name=\"").append(name).append('"')
        if (fileName != null) append("; filename=\"").append(fileName).append('"')
        append(CRLF)
        if (contentType != null) append("Content-Type: ").append(contentType).append(CRLF)
        append(CRLF)
    }.toByteArray(Charsets.UTF_8)

    private fun writeFileWithProgress(out: OutputStream, file: File, onProgress: (Float) -> Unit) {
        val total = file.length().coerceAtLeast(1L)
        var written = 0L
        var lastReported = 0f
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                written += read
                val progress = (written.toFloat() / total).coerceIn(0f, 1f)
                // 每 2% 报一次，避免几百次回调把主线程刷爆
                if (progress - lastReported >= 0.02f) {
                    lastReported = progress
                    onProgress(progress)
                }
            }
        }
        onProgress(1f)
    }

    private fun readDiagnosticText(zipFile: File): String {
        val chunks = ArrayList<String>()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) }
                .sortedBy { entryOrder(it.name) }
                .toList()
            for (entry in entries) {
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                chunks += "===== ${entry.name} =====\n$text"
            }
        }
        val joined = chunks.joinToString("\n\n")
        if (joined.length <= MAX_LOG_CHARS) return joined
        val head = chunks.firstOrNull().orEmpty().take(200_000)
        val rest = chunks.drop(1).joinToString("\n\n")
        val restBudget = (MAX_LOG_CHARS - head.length - 40).coerceAtLeast(0)
        val tail = if (rest.length > restBudget) rest.takeLast(restBudget) else rest
        return head + "\n\n…（中间已省略，保留清单和较新的日志）…\n\n" + tail
    }

    /** 清单和本应用 / 车机 logcat 排在前面，其余文本跟在后面。 */
    private fun entryOrder(name: String): Int = when {
        name.endsWith("collection_manifest.txt") -> 0
        name.endsWith("logcat_app.txt") -> 1
        name.endsWith("logcat.txt") -> 2
        name.endsWith("logcat_filtered.txt") -> 3
        name.endsWith("logcat_system.txt") -> 4
        name.endsWith("logcat_system_filtered.txt") -> 5
        else -> 10
    }

    private companion object {
        const val CRLF = "\r\n"
        val CRLF_BYTES = CRLF.toByteArray(Charsets.UTF_8)
        const val BUFFER_SIZE = 64 * 1024

        /** 留出 JSON 外壳，保证整包低于服务端 8MB。 */
        const val MAX_LOG_CHARS = 6_000_000
        val STAMP: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
