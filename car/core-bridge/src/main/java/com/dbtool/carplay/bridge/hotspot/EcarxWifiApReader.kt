package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import java.io.File

/**
 * 厂商热点客户端快照（[EcarxWifiApSnapshot.clients] 的元素）。
 *
 * 只保留任务书列出的三个只读字段；读不到就是 null，不用空串/占位值填坑，
 * 让调用方能区分"没有客户端"和"读不到客户端"。
 */
data class EcarxWifiApClient(
    val ip: String?,
    val mac: String?,
    val name: String?,
)

/**
 * `com.ecarx.xui.adaptapi.wifiap.ExternalWifiAP` 的一次性只读快照。
 *
 * @param state `getWifiApState()` 的原值（10 DISABLING / 11 DISABLED /
 *        12 ENABLING / 13 ENABLED / 14 FAILED）；读不到为 null。
 * @param enabled `isWifiApEnabled()`；读不到为 null。
 * @param clients `WifiAp.getWifiApClients()`；读不到为 emptyList()。
 */
data class EcarxWifiApSnapshot(
    val state: Int?,
    val enabled: Boolean?,
    val clients: List<EcarxWifiApClient>,
) {
    /** 厂商 SDK 明确说"开着"：状态 13 或 enabled == true。 */
    val connected: Boolean get() = state == 13 || enabled == true
}

/**
 * 反射读 Ecarx（亿咖通）TCAM 平台的原厂热点开关状态与客户端。
 *
 * ## 为什么需要它
 *
 * CS11 车机上 `WifiManager.isWifiApEnabled()` 会**假关闭**：热点实际开着
 * （手机已拿到 `192.168.15.x`），反射却返回 false。`TcamHotspotManager` 以前
 * 据此直接抛 `The car TCAM hotspot is off`，根本没走到复用连接。
 * 厂商 `ExternalWifiAP` 才报告真实状态，所以这里补一条只读通道。
 *
 * ## 约束
 *
 * - 全部走反射（`Class.forName` + `getMethod`），**不在编译期引用 adaptapi 类**；
 * - 只读：不开/关热点、不改配置；
 * - 任何异常都不上抛：读不到就是 null / 空列表，由 [TcamHotspotGate] 决定要不要拦；
 * - 每次 [read] 结束都关闭拿到的实例（`AutoCloseable` 或带 `close()` 的对象）。
 *
 * ## 类加载
 *
 * 回落顺序与 [EcarxHotspotReader] 的 `apiLoader` 一致：当前 ClassLoader →
 * `/system/framework` 等目录下名字含 `ecarx` / `adaptapi` 的 jar →
 * `com.geely.settings` 的 `sharedLibraryFiles` / `sourceDir`。
 * 只借类进本进程，运行时权限检查仍归本进程 UID。
 */
object EcarxWifiApReader {

    private const val EXTERNAL_API = "com.ecarx.xui.adaptapi.wifiap.ExternalWifiAP"
    private const val WIFI_AP_API = "com.ecarx.xui.adaptapi.wifiap.WifiAp"
    private const val CLIENT_API = "com.ecarx.xui.adaptapi.wifiap.IWifiApClient"
    private const val SETTINGS_PACKAGE = "com.geely.settings"

    private val API_NAMES = listOf(EXTERNAL_API, WIFI_AP_API)

    @Volatile
    private var cachedLoader: ClassLoader? = null

    /** 读一次厂商热点状态；任何失败都返回"全 null / 空列表"，绝不上抛。 */
    fun read(context: Context): EcarxWifiApSnapshot =
        readWith(
            context,
            lookup = { name -> loadClass(context, name) },
            serviceSnapshot = { TcamWifiServiceClient.read(context) },
        )

    /**
     * 可注入类查找的读取实现（单测用；生产入口是 [read]）。
     *
     * `lookup` 返回 null 表示类不存在；返回的类按厂商签名反射调用。
     */
    internal fun readWith(
        context: Context?,
        lookup: (String) -> Class<*>?,
        binderState: () -> Int? = { TcamWifiBinder.apEnabledState() },
        serviceSnapshot: (() -> TcamWifiServiceClient.Snapshot?)? = null,
    ): EcarxWifiApSnapshot {
        // ① 厂商门面（getSystemService("tcam_wifi") → TcamWifiManager）：最可靠，且带客户端列表
        runCatching { serviceSnapshot?.invoke() }.getOrNull()?.let { s ->
            if (s.state != null || s.enabled != null || s.clients.isNotEmpty()) {
                return EcarxWifiApSnapshot(
                    state = s.state,
                    enabled = s.enabled ?: s.state?.let { it == 13 },
                    clients = s.clients.map {
                        EcarxWifiApClient(ip = it.ip, mac = it.mac, name = it.name)
                    },
                )
            }
        }

        var external: Any? = null
        var wifiAp: Any? = null
        return try {
            // ② 厂商 SDK（ExternalWifiAP.getWifiApState）
            val sdkState = runCatching {
                external = create(loadQuietly(lookup, EXTERNAL_API), context)
                invoke(external, "getWifiApState")?.let { (it as? Number)?.toInt() }
            }.getOrNull()
            val sdkEnabled = runCatching {
                invoke(external, "isWifiApEnabled") as? Boolean
            }.getOrNull()

            // ③ SDK 不可用（本车缺 Wifi6ApImpl）时走 TCAM 真实 Binder（交易码 47）
            val state = sdkState ?: runCatching { binderState() }.getOrNull()
            // 🔴 2026-10-20 回归修复：回落 Binder 时**只有 13 能确认"开"**，
            // 10/11/14 在 TCAM 上是「空载 / 正在重配 / 失败」的中间态，不能据此判"关"。
            // 现场实测（VIN 381425，1.1.33 能连、1.1.34 及以后连不上）：
            // Binder 在"热点开着但还没客户端"时稳定返回 11，旧写法 `it == 13`
            // 于是判成关 → 直接拦死，iPhone 完全连不上。
            // 判不准就返回 null —— null 的语义是"不拦"，交给后续真实连接去说话。
            // 这与 1.1.32 及以前（SDK 拿不到 ⇒ null ⇒ 不拦）的行为一致。
            val enabled = sdkEnabled ?: when (state) {
                13 -> true
                else -> null
            }

            // ④ 客户端列表（SDK 可用时）
            val clients = runCatching {
                wifiAp = create(loadQuietly(lookup, WIFI_AP_API), context)
                readClients(wifiAp, lookup)
            }.getOrDefault(emptyList())

            EcarxWifiApSnapshot(state, enabled, clients)
        } catch (_: Throwable) {
            EcarxWifiApSnapshot(state = null, enabled = null, clients = emptyList())
        } finally {
            closeQuietly(external)
            closeQuietly(wifiAp)
        }
    }

    private fun readClients(
        wifiAp: Any?,
        lookup: (String) -> Class<*>?,
    ): List<EcarxWifiApClient> {
        val raw = invoke(wifiAp, "getWifiApClients") as? Collection<*> ?: return emptyList()
        val clientApi = loadQuietly(lookup, CLIENT_API)
        return raw.mapNotNull { client ->
            if (client == null) return@mapNotNull null
            val api = clientApi?.takeIf { it.isInstance(client) } ?: client.javaClass
            EcarxWifiApClient(
                ip = invoke(api, client, "getIP") as? String,
                mac = invoke(api, client, "getMac") as? String,
                name = invoke(api, client, "getName") as? String,
            )
        }
    }

    private fun loadQuietly(lookup: (String) -> Class<*>?, name: String): Class<*>? =
        runCatching { lookup(name) }.getOrNull()

    private fun create(api: Class<*>?, context: Context?): Any? {
        if (api == null) return null
        return runCatching {
            api.getMethod("create", Context::class.java).invoke(null, context)
        }.getOrNull()
    }

    private fun invoke(target: Any?, method: String): Any? {
        if (target == null) return null
        return runCatching { target.javaClass.getMethod(method).invoke(target) }.getOrNull()
    }

    private fun invoke(api: Class<*>, target: Any, method: String): Any? =
        runCatching { api.getMethod(method).invoke(target) }.getOrNull()

    /** 用完即弃：`AutoCloseable` 或带 `close()` 的厂商对象都关掉；失败不上抛。 */
    private fun closeQuietly(instance: Any?) {
        if (instance == null) return
        if (instance is AutoCloseable) {
            runCatching { instance.close() }
            return
        }
        runCatching { instance.javaClass.getMethod("close").invoke(instance) }
    }

    private fun loadClass(context: Context, name: String): Class<*>? =
        runCatching { Class.forName(name, false, loader(context)) }.getOrNull()

    /**
     * 三级回落加载 Ecarx SDK，顺序与 `EcarxHotspotReader.apiLoader` 保持一致
     * （那个方法是 private，无法跨文件借用；这里沿用同一策略，不引入第二套判据）。
     */
    private fun loader(context: Context): ClassLoader {
        cachedLoader?.let { return it }
        val parent = context.classLoader
        if (API_NAMES.any { runCatching { Class.forName(it, false, parent) }.isSuccess }) {
            return parent.also { cachedLoader = it }
        }
        // 有些 ROM 只对系统 App 暴露 SDK，未加进普通 App 的 loader。
        val jars = listOf("/system/framework", "/system_ext/framework", "/vendor/framework")
            .flatMap { directory ->
                runCatching { File(directory).listFiles()?.toList() }.getOrNull().orEmpty()
            }
            .filter { file ->
                file.isFile && file.canRead() && file.extension == "jar" &&
                    (file.name.contains("ecarx", true) || file.name.contains("adaptapi", true))
            }
            .sortedBy { it.absolutePath }
        val frameworkLoader = if (jars.isEmpty()) parent else runCatching {
            PathClassLoader(jars.joinToString(File.pathSeparator) { it.absolutePath }, parent)
        }.getOrDefault(parent)
        // 部分固件把 SDK 塞在吉利系统设置 App 里：只加载它的 SDK，
        // 保持本进程 UID 与权限检查。
        val loader = if (API_NAMES.any {
                runCatching { Class.forName(it, false, frameworkLoader) }.isSuccess
            }
        ) {
            frameworkLoader
        } else runCatching {
            val app = context.packageManager.getApplicationInfo(
                SETTINGS_PACKAGE,
                PackageManager.GET_SHARED_LIBRARY_FILES,
            )
            check(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            val paths = (app.sharedLibraryFiles.orEmpty().toList() + listOf(app.sourceDir) +
                app.splitSourceDirs.orEmpty().toList()).filter { File(it).canRead() }
            if (paths.isEmpty()) frameworkLoader
            else PathClassLoader(paths.joinToString(File.pathSeparator), frameworkLoader)
        }.getOrDefault(frameworkLoader)
        return loader.also { cachedLoader = it }
    }
}
