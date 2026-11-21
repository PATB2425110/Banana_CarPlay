package com.dbtool.carplay.bridge.developer

import android.content.Context
import android.os.SystemClock
import com.dbtool.carplay.api.developer.CarDeveloperEndpoints
import com.dbtool.carplay.api.system.CarSystemAdapter
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * 服务端对「本机是否被拉黑」的**一次应答**。
 *
 * 与 [CarBlockStatus] 分开，是因为这两者要能分别推理：
 * 前者是"远端说了什么"，后者是"结合本地落盘之后我们最终怎么判"。
 * 把远端三态单独抽出来，[resolveBlockStatus] 才是一个可单测的纯函数。
 */
internal sealed class RemoteBlockStatus {

    /** 服务端明确回「未拉黑」。 */
    object Clear : RemoteBlockStatus()

    /** 服务端明确回「已拉黑」，[reason] 是面向用户的中文。 */
    data class Blocked(val reason: String) : RemoteBlockStatus()

    /**
     * 没问到答案：超时 / 连不上 / 非 2xx / 响应不是我们认识的形状。
     *
     * ⚠️ 刻意**不区分**这些细分原因：它们对判定策略的影响完全一样
     * （都退回落盘缓存），区分的价值只在于日志——而日志在 [CarDeviceStatusSync]
     * 里已经记了原始异常。再分下去只会多出几个没人 switch 的分支。
     */
    object Unreachable : RemoteBlockStatus()
}

/**
 * 结合服务端应答与本地落盘之后，本机最终的拉黑判定。
 *
 * 调用方（`CarPlaySessionBridge`）只关心"要不要拦"，因此这里只暴露
 * 拦截所需的信息，不暴露过程。
 */
sealed class CarBlockStatus {

    /** 未拉黑（服务端明确说的）。可以正常连接。 */
    object Clear : CarBlockStatus()

    /**
     * 本机连 `deviceId` 都还没生成出来，无从查起。
     *
     * 实际上几乎不可能发生（`deviceId` 是纯本地计算 + 落盘，不依赖网络），
     * 但真发生时**必须放行**——把"查不了"当成"已拉黑"就是拿一个本地故障
     * 锁死整台车。与"服务端故障不能锁死车队"是同一条原则。
     */
    object NoIdentity : CarBlockStatus()

    /** 连不上服务端，但本地也没有拉黑记录 ⇒ 放行。 */
    object Unknown : CarBlockStatus()

    /**
     * 已拉黑，必须拦截。
     *
     * @param reason 面向用户的中文原因，可以直接显示。
     * @param fromCache true = 断网时用的本地落盘（sticky 语义），false = 服务端本次说的。
     *        只用于日志与诊断：**判定结果一样**，绝不因为"来源是缓存"就放行。
     */
    data class Blocked(val reason: String, val fromCache: Boolean) : CarBlockStatus()
}

/**
 * 拉黑判定策略：把「远端应答」与「本地落盘」合成一个最终结论。
 *
 * ## 为什么单独抽成一个函数
 *
 * 这是本功能的全部风险所在，而它**必须是纯的**才能进单测——
 * 本仓 `:car:core-bridge` 的单测是纯 JVM（没有 Robolectric），
 * 造不出 `Context`。抽出来之后，四种组合（远端 × 缓存）都能用内存假实现钉死。
 *
 * ## 策略（用户 2026-10-08 选定：sticky —— 断网也拦）
 *
 * | 远端 | 本地缓存 | 结论 |
 * |---|---|---|
 * | 已拉黑 | — | **拦**，并把原因写进缓存 |
 * | 未拉黑 | — | **放行**，并清除缓存（只有明确回 false 才清） |
 * | 没问到 | 已拉黑 | **拦**（沿用缓存，这是 sticky 的关键一格） |
 * | 没问到 | 无标记 | 放行 |
 *
 * 最后一行是刻意的：**服务端故障绝不能把整个车队锁死**，
 * 与 `POST /api/device/heartbeat` 在服务端"任何异常都回 blocked=false"是同一个约定。
 */
internal fun resolveBlockStatus(
    store: CarMfiBlockStore,
    remote: RemoteBlockStatus,
): CarBlockStatus = when (remote) {
    is RemoteBlockStatus.Blocked -> {
        store.cache(remote.reason)
        CarBlockStatus.Blocked(remote.reason, fromCache = false)
    }

    is RemoteBlockStatus.Clear -> {
        store.clear()
        CarBlockStatus.Clear
    }

    is RemoteBlockStatus.Unreachable ->
        if (store.isBlocked) {
            CarBlockStatus.Blocked(
                reason = store.reason.ifEmpty { CarDeveloperEndpoints.BLOCKED_REASON_FALLBACK },
                fromCache = true,
            )
        } else {
            CarBlockStatus.Unknown
        }
}

/**
 * 解释心跳应答里的**已取值字段**，得出远端三态。
 *
 * ## 为什么参数是三个裸值而不是响应体字符串
 *
 * 本模块的单测是**纯 JVM**，而 `org.json` 在 Android 单测里是 stub（调用即抛）。
 * 把"从 JSON 里取值"与"怎么解释这些值"分开之后，解释这一半（也就是全部风险所在）
 * 就能直接喂值单测，不必为一个 JSON 库去动上游的版本目录。
 *
 * ## 为什么 `blocked` 是可空 Boolean
 *
 * 只有**真正的布尔值**才算"服务端表了态"：字段缺失（老服务端）/ JSON null /
 * 字符串 `"false"` 一律传 null ⇒ 判成"没问到"，再由 [resolveBlockStatus] 退回落盘缓存。
 *
 * ⚠️ 调用方**绝不能**用 `optBoolean("blocked", false)` 预先把空值折叠成 false：
 * 那样"字段缺失"会被读成"明确回未拉黑"，于是一次字段改名就能把已拉黑的车悄悄放行。
 *
 * @param status HTTP 状态码。
 * @param blocked 服务端回的判定；null = 没表态。
 * @param reason 服务端回的原因；null / 空串一律落到兜底文案。
 */
internal fun interpretBlockResponse(
    status: Int,
    blocked: Boolean?,
    reason: String?,
): RemoteBlockStatus {
    if (status !in 200..299) return RemoteBlockStatus.Unreachable
    if (blocked == null) return RemoteBlockStatus.Unreachable
    if (!blocked) return RemoteBlockStatus.Clear
    return RemoteBlockStatus.Blocked(
        reason?.trim().orEmpty().ifEmpty { CarDeveloperEndpoints.BLOCKED_REASON_FALLBACK },
    )
}

/** 判定结果的单行描述（只进日志，不进界面）。 */
internal fun CarBlockStatus.describe(): String = when (this) {
    is CarBlockStatus.Clear -> "未拉黑（服务端明确）"
    is CarBlockStatus.NoIdentity -> "无 deviceId，放行"
    is CarBlockStatus.Unknown -> "未问到且无本地标记，放行"
    is CarBlockStatus.Blocked -> if (fromCache) "已拉黑（本地缓存）" else "已拉黑（服务端）"
}

/**
 * 打一次心跳：报在线 + 取回拉黑判定，并同步本地落盘。
 *
 * ## 放在哪两步
 *
 * 1. `CarPlaySessionBridge.startInternal()` 在**建栈最开始**调一次 —— 连接前的拦截；
 * 2. `CarPresenceHeartbeat` 每 60 秒调一次 —— 后台「在线」列的实时来源。
 *
 * 两处都在后台线程上（不能放主线程：这里有一次网络往返）。
 *
 * ## 为什么不做"多久查一次"的节流
 *
 * 想过加 TTL，最后**不加**，理由是车机没网时 `HttpURLConnection` 通常
 * **立刻**失败（无路由），真正会等满超时的是"网络在、服务端不应答"这种少数情况，
 * 而那正是最该如实暴露的状态。加了节流反而会让"服务端恢复后仍被拦住"变长。
 *
 * 代价（最多 [CarDeveloperEndpoints.HEARTBEAT_TIMEOUT_SECONDS] 秒）被
 * **记进日志**（见 [sync] 里的耗时字段）：如果实车上真的变成卡顿，诊断包里
 * 一眼能看到，届时再按实测值加节流——先量，别猜。
 */
class CarDeviceStatusSync(
    context: Context,
    private val store: CarMfiBlockStore,
    system: CarSystemAdapter,
    /**
     * 日志出口。由调用方接到 `platform.diagnostics`（写文件，进诊断包），
     * 而不是 `android.util.Log`——这条链只在现场排障时才需要看。
     */
    private val log: (String) -> Unit = {},
) {

    private val appContext = context.applicationContext
    /**
     * 最近一次**可对外表态**的判定。`NoIdentity` / `Unknown` 不写入，
     * 因为它们既不是封禁，也不是服务端明确解封。
     */
    private val lastPublishedBlocked = AtomicReference<Boolean?>(null)

    /**
     * 判定变化回调。可能从心跳线程或会话建栈线程调用，注册方负责切主线程。
     *
     * 只有 [CarBlockStatus.Blocked] 与 [CarBlockStatus.Clear] 会触发：
     * 前者表示「刚被封禁」，后者表示服务端已经明确解封。
     */
    @Volatile
    var onRemoteBlockChanged: ((Boolean) -> Unit)? = null

    private val deviceInfo = CarDeviceInfo(
        appContext,
        system,
        CarDeveloperPrefs(appContext),
    )

    /**
     * 查一次并落盘，返回最终判定。
     *
     * 本方法**不抛异常**：任何失败都变成 [CarBlockStatus.Unknown]（放行）或
     * 缓存里的 [CarBlockStatus.Blocked]。查个状态把连接搞崩是不可接受的。
     */
    fun sync(): CarBlockStatus {
        val deviceId = deviceInfo.deviceId()
        if (deviceId.isEmpty()) {
            log("拉黑状态：本机尚无 deviceId，跳过查询")
            return CarBlockStatus.NoIdentity
        }
        // VIN 走缓存（`dbplay_vin_cache`）+ 首屏预算：VIN 不会变，读到过一次之后
        // 这里就是毫秒级命中；没读到过也只是几条快命令，不会跑那条 12 秒的 logcat。
        val vin = deviceInfo.vin(briefBudget = true)

        val startedAt = SystemClock.elapsedRealtime()
        val remote = runCatching { query(deviceId, vin) }.getOrElse { error ->
            log("拉黑状态查询异常：${error.javaClass.simpleName} ${error.message.orEmpty()}")
            RemoteBlockStatus.Unreachable
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val status = resolveBlockStatus(store, remote)
        publishBlockChange(status)
        log(
            "拉黑状态：deviceId=$deviceId vin=${vin.ifEmpty { "-" }} " +
                "远端=${remote.describe()} 判定=${status.describe()} 耗时=${elapsed}ms",
        )
        return status
    }

    private fun publishBlockChange(status: CarBlockStatus) {
        val blocked = when (status) {
            is CarBlockStatus.Blocked -> true
            CarBlockStatus.Clear -> false
            CarBlockStatus.NoIdentity, CarBlockStatus.Unknown -> return
        }
        if (lastPublishedBlocked.getAndSet(blocked) == blocked) return
        val listener = onRemoteBlockChanged ?: return
        runCatching { listener(blocked) }.onFailure { error ->
            log("拉黑状态回调异常：${error.javaClass.simpleName} ${error.message.orEmpty()}")
        }
    }

    private fun query(deviceId: String, vin: String): RemoteBlockStatus {
        val url = URL(
            CarDeveloperEndpoints.BASE_URL.trimEnd('/') + CarDeveloperEndpoints.HEARTBEAT_PATH,
        )
        // VIN 只是**加分项**：deviceId 已能定位这台车，VIN 用来兜住
        // "清应用数据换了 deviceId"这种情况（VIN 不会变，见 `CarVinReader`）。
        // 读不到就不带，服务端按 deviceId 查。
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            if (vin.isNotEmpty()) put("vin", vin)
            put("product", CarDeveloperEndpoints.PRODUCT)
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(url) as HttpURLConnection).apply {
                // POST 而不是 GET：这个接口**有副作用**（刷新后台的在线时间），
                // 用 GET 会被中间代理缓存 —— 一旦缓存住，后台看到的在线时间就
                // 永远停在第一次，拉黑判定也会被缓存成旧的。
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CarDeveloperEndpoints.HEARTBEAT_TIMEOUT_SECONDS * 1000
                readTimeout = CarDeveloperEndpoints.HEARTBEAT_TIMEOUT_SECONDS * 1000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DBPlay-Heartbeat")
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            // 取值用 `opt(...) as? T` 而不是 `optBoolean` / `optString`：
            // 前者在"字段缺失 / JSON null / 类型不对"时**原样给出 null**，
            // 后者会把它们悄悄折叠成 false 或字面量 "null"，两种都会把
            // "服务端没表态"伪装成"服务端说没拉黑"。解释逻辑见 interpretBlockResponse。
            val json = runCatching { JSONObject(body) }.getOrNull()
            interpretBlockResponse(
                status = status,
                blocked = json?.opt("blocked") as? Boolean,
                reason = json?.opt("reason") as? String,
            )
        } finally {
            connection?.disconnect()
        }
    }
}

private fun RemoteBlockStatus.describe(): String = when (this) {
    is RemoteBlockStatus.Clear -> "未拉黑"
    is RemoteBlockStatus.Unreachable -> "未问到"
    is RemoteBlockStatus.Blocked -> "已拉黑"
}
