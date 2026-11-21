package com.dbtool.carplay.bridge.update

import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线更新的服务端客户端。
 *
 * 用 `HttpURLConnection` 而不是引入 OkHttp——与 [com.dbtool.carplay.bridge.developer.CarDeviceLogUploader]
 * 同一个理由：`:car:core-bridge` 目前没有任何第三方网络依赖，
 * 两个接口都不需要连接池 / 拦截器 / 异步队列。
 *
 * 契约逐字对齐 `dbapi/interfaces/rest/app_update.py`：
 * - `GET  /api/app/check_update`  → `{ok, has_update, force_update, is_targeted,
 *    latest_version_code, latest_version_name, release_notes, download_url, …}`
 * - `POST /api/app/update_status` → `{ok, msg, data}`，`status` 必须是
 *    `pushed` / `downloaded` / `installed` / `failed` 之一，否则服务端 400。
 *
 * 两个接口都**免登录**（车机侧没有账号）。
 */
internal class CarUpdateApi {

    /** 服务端返回的发布信息。 */
    data class ReleaseInfo(
        val hasUpdate: Boolean,
        val forceUpdate: Boolean,
        /**
         * 是否允许**后台自动更新**（2026-10-11 加）。
         *
         * 来自后台「应用更新」页的开关，随 `check_update` 下发。
         * 服务端未配置时**必须**回落 `false` —— 静默装错包的代价是车队集体重启，
         * 不能因为"服务端没返回这个字段"就默认允许。
         */
        val autoUpdate: Boolean,
        val isTargeted: Boolean,
        val latestVersionCode: Long,
        val latestVersionName: String,
        val releaseNotes: List<String>,
        val downloadUrl: String,
        val minSupportedVersionCode: Long,
        val publishTime: String,
    )

    data class CheckResult(
        val success: Boolean,
        /** 失败时给用户看的中文原因；成功时为空。 */
        val message: String,
        val info: ReleaseInfo?,
    )

    data class StatusReportResult(
        val success: Boolean,
        val message: String,
    )

    /**
     * 查询是否有新版本。
     *
     * @param deviceId 带上它才能命中后台的**定向灰度**（`app_update_targets`）。
     *        服务端只在 `targeted_only` 或该设备命中目标时才用得上，
     *        但对普通发布也无害——所以一律带上。
     */
    fun check(
        baseUrl: String,
        path: String,
        appId: String,
        currentVersionCode: Long,
        currentVersionName: String,
        deviceId: String,
        timeoutSeconds: Int,
    ): CheckResult {
        val query = buildString {
            append("app_id=").append(encode(appId))
            append("&current_version_code=").append(currentVersionCode)
            append("&current_version_name=").append(encode(currentVersionName))
            append("&platform=android&channel=stable")
            if (deviceId.isNotEmpty()) append("&device_id=").append(encode(deviceId))
        }
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + path + "?" + query)
            connection = (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(url) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DBPlay-Updater")
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                return CheckResult(false, "服务端返回 HTTP $status", null)
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return CheckResult(false, "服务端返回无法解析的内容", null)
            if (!json.optBoolean("ok", false)) {
                return CheckResult(false, json.optString("msg").ifEmpty { "服务端拒绝了本次查询" }, null)
            }
            val info = parseRelease(json)
            if (info.hasUpdate && info.latestVersionCode <= 0L) {
                return CheckResult(false, "服务端更新信息缺少有效 versionCode", null)
            }
            CheckResult(true, "", info)
        } catch (error: Throwable) {
            CheckResult(
                success = false,
                message = "检查失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim(),
                info = null,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 上报升级状态。
     *
     * 刻意**不抛异常也不返回失败细节**：这是一条"顺带告诉后台"的记录，
     * 它失败不该影响用户装不装得上新版本。
     */
    fun reportStatus(
        baseUrl: String,
        path: String,
        appId: String,
        deviceId: String,
        status: String,
        detail: String,
        timeoutSeconds: Int,
    ): StatusReportResult {
        if (deviceId.isEmpty()) return StatusReportResult(false, "deviceId 为空")
        var connection: HttpURLConnection? = null
        return try {
            val payload = JSONObject().apply {
                put("app_id", appId)
                put("device_id", deviceId)
                put("status", status)
                if (detail.isNotEmpty()) put("detail", detail)
            }.toString()
            val url = URL(baseUrl.trimEnd('/') + path)
            connection = (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(url) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DBPlay-Updater")
                // 定长而不是 chunked：与日志上传同一个坑（Werkzeug 对 chunked 支持差）。
                val bytes = payload.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { out: OutputStream -> out.write(bytes) }
            }
            val httpStatus = connection.responseCode
            // 必须把响应读掉，否则连接不会释放；HTTP 错误则读 errorStream。
            val responseBody = (if (httpStatus in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(responseBody) }.getOrNull()
            val accepted = httpStatus in 200..299 && (json?.optBoolean("ok", false) == true)
            StatusReportResult(
                success = accepted,
                message = if (accepted) "状态已接收" else "HTTP $httpStatus，响应未确认接收",
            )
        } catch (error: Throwable) {
            StatusReportResult(
                success = false,
                message = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim(),
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val notes = mutableListOf<String>()
        json.optJSONArray("release_notes")?.let { array ->
            for (index in 0 until array.length()) {
                val line = array.optString(index).trim()
                if (line.isNotEmpty()) notes.add(line)
            }
        }
        return ReleaseInfo(
            hasUpdate = json.optBoolean("has_update", false),
            forceUpdate = json.optBoolean("force_update", false),
            autoUpdate = json.optBoolean("auto_update", false),
            isTargeted = json.optBoolean("is_targeted", false),
            latestVersionCode = json.optLong("latest_version_code", 0L),
            latestVersionName = json.optString("latest_version_name"),
            releaseNotes = notes,
            downloadUrl = json.optString("download_url"),
            minSupportedVersionCode = json.optLong("min_supported_version_code", 0L),
            publishTime = json.optString("publish_time"),
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
