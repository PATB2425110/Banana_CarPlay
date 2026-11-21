package com.dbtool.carplay.bridge.developer

import android.content.Context
import android.util.Log
import com.dbtool.carplay.api.developer.CarDeveloperEndpoints
import com.dbtool.carplay.api.system.CarSystemAdapter
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车辆档案自动上报（DBPlay 启动时调用一次）。
 *
 * ## 为什么要有它
 *
 * 后台「DBPlay 车辆」页需要一份**按 VIN 组织的车辆清单**，才能按 VIN 做灰度。
 * 而 VIN / 系统版本此前只在**开发者模式手动上传诊断包**时才随包带上——普通用户
 * 永远不会点那个按钮，后台就看不到这台车。
 *
 * 因此这里做一次**轻量、自动、无感**的上报：只发一个小 JSON（不含任何日志内容），
 * 把"这台车是谁、装的哪个版本"告诉服务端。与诊断包上传是两回事：
 * 那个传的是几十 MB 的包，这个是几个字段。
 *
 * ## 与更新检查的关系
 *
 * 两者都在启动时跑，但**各管一段**：`CarUpdateManager` 管"发现/下载/安装"，
 * 本类只管"让后台知道这台车存在"。刻意不合并——更新检查失败要保持安静
 * （车机常没网），而档案上报失败也只是少一条记录，二者失败的处置不同。
 *
 * ## 线程与节流
 *
 * - 联网在自己的单线程池上跑，**不阻塞 `Application.onCreate`**（首帧代价）。
 * - 按 [REPORT_INTERVAL_MILLIS] 节流：车机一天点火很多次，每次启动都上报
 *   对服务端是纯噪声；但比更新检查（6 小时）更频繁，因为车辆信息比版本更值得保鲜。
 *
 * ⚠️ 类本身是 `public`（不是 `internal`）：它经由 [CarAppServices.profile] 对外暴露，
 * 而公开属性不能暴露 internal 类型。构造参数都是 public 类型，因此可以直接公开。
 */
class CarVehicleProfileReporter(
    context: Context,
    private val system: CarSystemAdapter,
    private val appVersionName: String,
    private val appVersionCode: Long,
) {

    private val appContext = context.applicationContext
    private val prefs = CarProfilePrefs(appContext)
    private val deviceInfo = CarDeviceInfo(appContext, system, CarDeveloperPrefs(appContext))

    /**
     * 拉黑状态的本地落盘。
     *
     * 与 `CarPlaySessionBridge` 里那份**各持一份实例**（同一个 prefs 文件，
     * 见 `CarMfiBlockStore` 的线程说明）。这里用它做两件事：
     * 上报时把"本机认为自己在不在黑名单里"告诉服务端（诊断用），
     * 以及**把服务端在响应里回的权威判定写回本地**。
     *
     * 后者是有价值的一条补充路径：档案上报每小时自动跑一次，于是
     * 即使司机一直没点「连接」，被拉黑的车也能在下次启动后学到这件事；
     * 解除同理，不必等他先撞一次墙。
     */
    private val blockStore: CarMfiBlockStore = CarMfiBlockPrefs(appContext)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-profile").apply { isDaemon = true }
    }

    /** 一个进程只自动上报一次（[CarUpdateManager.maybeAutoCheck] 同理）。 */
    private val attempted = AtomicBoolean(false)

    /**
     * 启动时自动上报，受 [REPORT_INTERVAL_MILLIS] 节流。
     *
     * 失败**静默**：车机常没网，每次启动都喊一句只会变噪声。用户想手动补一次，
     * 可以在开发者页调 [reportNow]。
     *
     * ⚠️ 走**首屏预算**（`briefBudget = true`）：这是应用启动路径，VIN 读取里
     * 那条 12 秒的 `logcat` 兜底不能在这里跑（见 `CarVinReader.Deps.shellFallback`）。
     * 真读不到也没关系——1 小时后下一次启动会重试，缓存也会在命中后一直生效。
     */
    fun maybeAutoReport() {
        if (!attempted.compareAndSet(false, true)) return
        val elapsed = System.currentTimeMillis() - prefs.lastReportAtMillis
        if (prefs.lastReportAtMillis > 0L && elapsed < REPORT_INTERVAL_MILLIS) return
        report(briefBudget = true)
    }

    /**
     * 立即上报一次（开发者页手动触发）。
     *
     * 用**宽裕预算**：用户已经在盯着页面等，多花十几秒读全 VIN 是值得的，
     * 而且这一趟之后 VIN 会进缓存，后续启动都是毫秒级命中。
     */
    fun reportNow() = report(briefBudget = false)

    private fun report(briefBudget: Boolean) {
        worker.execute {
            val result = runCatching { doReport(briefBudget) }
            result.onFailure { Log.w(TAG, "车辆档案上报异常：${it.message}") }
            if (result.getOrDefault(false)) {
                prefs.lastReportAtMillis = System.currentTimeMillis()
            }
        }
    }

    /** @return 服务端是否确认接收。 */
    private fun doReport(briefBudget: Boolean): Boolean {
        val deviceId = deviceInfo.deviceId()
        if (deviceId.isEmpty()) {
            Log.w(TAG, "deviceId 为空，跳过车辆档案上报")
            return false
        }
        // 系统信息走缓存（12h TTL），不会每次都跑 dumpsys。
        val info = deviceInfo.systemInfo(forceRefresh = false)
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("fingerprint", deviceId)
            put("appVersion", appVersionName)
            put("appVersionCode", appVersionCode)
            put("product", CarDeveloperEndpoints.PRODUCT)
            deviceInfo.vin(briefBudget).takeIf { it.isNotEmpty() }?.let { put("vin", it) }
            info.androidVersion.takeIf { it.isNotEmpty() }?.let { put("androidVersion", it) }
            info.systemVersion.takeIf { it.isNotEmpty() }?.let { put("systemVersion", it) }
            info.deviceModel.takeIf { it.isNotEmpty() }?.let { put("deviceModel", it) }
            // 本机**自己认为**的拉黑状态。服务端**不据此判定**（客户端说的不算数，
            // 那只是"自己报自己"），仅作诊断信息：后台能看出"这台车已经知道自己被停用了"，
            // 从而区分"拦住了但车不知道"与"车知道但连不上服务端"这两种现场。
            put("blocked", blockStore.isBlocked)
            blockStore.reason.takeIf { it.isNotEmpty() }?.let { put("blockedReason", it) }
        }

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                CarDeveloperEndpoints.BASE_URL.trimEnd('/') + CarDeveloperEndpoints.PROFILE_PATH,
            )
            connection = (com.shilapi.xcertplay.network.CarPlayInternetConnection.open(url) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CarDeveloperEndpoints.PROFILE_TIMEOUT_SECONDS * 1000
                readTimeout = CarDeveloperEndpoints.PROFILE_TIMEOUT_SECONDS * 1000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DBPlay-Profile")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            val ok = status in 200..299 && json?.optBoolean("success", false) == true
            if (ok) {
                json?.let(::applyBlockVerdict)
                Log.i(TAG, "车辆档案已上报：deviceId=$deviceId vin=${payload.optString("vin")}")
            } else {
                Log.w(TAG, "车辆档案上报被拒：HTTP $status $body")
            }
            ok
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 把服务端在**档案上报响应**里回的权威拉黑判定写回本地。
     *
     * 这是"sticky"语义的第二条来源（第一条是 `CarDeviceStatusSync` 打的心跳，
     * `POST /api/device/heartbeat`）。它每小时自动跑一次，所以即使司机一直没点
     * 「连接」、心跳也恰好没打上，被拉黑的车仍会在下次启动后学到这件事；解除同理。
     *
     * ⚠️ **只有服务端明确回 `blocked=false` 才清除**本地标记——这是用户选定的
     * sticky 口径。因此这里必须是"真正的布尔值"才算表了态：
     * 字段缺失（老服务端）/ JSON null / 字符串，一律**不动**本地标记。
     * 用 `optBoolean("blocked", false)` 会把"没表态"读成"明确回未拉黑"，
     * 于是一次字段改名就能把一台已拉黑的车悄悄放行。
     */
    private fun applyBlockVerdict(json: JSONObject) {
        val verdict = json.opt("blocked") as? Boolean ?: return
        if (verdict) {
            val reason = (json.opt("blockedReason") as? String).orEmpty()
            blockStore.cache(reason)
            Log.w(TAG, "服务端标记本机已拉黑：${reason.ifEmpty { "（未给原因）" }}")
        } else if (blockStore.isBlocked) {
            blockStore.clear()
            Log.i(TAG, "服务端已解除本机拉黑，本地标记已清除")
        }
    }

    private companion object {
        const val TAG = "dbplay-profile"

        /**
         * 自动上报最小间隔。
         *
         * 取 1 小时：VIN/系统版本不像版本号那样天天变，但换车机、刷系统、
         * 升级 App 之后后台都该尽快看到——比更新检查的 6 小时更密一档。
         * 用户手动触发（[reportNow]）不受此限制。
         */
        const val REPORT_INTERVAL_MILLIS: Long = 60L * 60 * 1000
    }
}

/**
 * 车辆档案上报的本地状态。
 *
 * 与 [CarDeveloperPrefs] / [CarUpdatePrefs] 一样单独立一个 store：
 * 它参与的是"上报节流"这一件事，恢复出厂设置不该把"上次上报时间"清掉
 * （清掉只会导致下一次启动立刻补发一次，没有害处，但也没必要）。
 */
internal class CarProfilePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次成功上报的时间戳（毫秒），0 = 从未成功过。 */
    var lastReportAtMillis: Long
        get() = prefs.getLong(KEY_LAST_REPORT_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_REPORT_AT, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "car_profile"
        const val KEY_LAST_REPORT_AT = "last_report_at"
    }
}
