package com.dbtool.carplay.bridge.hotspot

import android.content.Context

/**
 * 通过**公开系统服务名**读 TCAM 热点（`framework.jar` 里厂商改造的真正入口）。
 *
 * ## 依据（2026-10-20 反编译 framework.jar）
 *
 * ```
 * Context.TCAM_WIFI_SERVICE = "tcam_wifi"
 * WifiManager 内部：mTcamWifiManager = getSystemService(Context.TCAM_WIFI_SERVICE)
 * TcamWifiManager#getWifiApState()      → 13 = ENABLED
 * TcamWifiManager#getSoftApConDevInfo() → List<SoftApConDevInfo>{devName, macAddr, devIpAddr, status}
 * ```
 *
 * 相比手写 `ITcamWifiManager.transact(47 …)`，这条路径：
 * - 不用猜交易码（厂商改 AIDL 时不会静默失效）；
 * - 拿得到**客户端列表**（复用判定的关键证据）；
 * - 走的是厂商自己维护的门面，行为与系统设置页一致。
 *
 * ## 纪律
 *
 * `TcamWifiManager` 是隐藏 API：编译期不引用，全部反射；任何异常都退化返回 null / 空列表，
 * **绝不上抛**。调用方必须保证在**非主线程**执行（Binder 往返）。
 */
object TcamWifiServiceClient {

    /** 服务名，与 `Context.TCAM_WIFI_SERVICE` 一致（编译期拿不到该常量时用字面量）。 */
    const val SERVICE_NAME = "tcam_wifi"

    data class Client(val name: String?, val mac: String?, val ip: String?, val status: Int)

    data class Snapshot(val state: Int?, val enabled: Boolean?, val clients: List<Client>) {
        val connected: Boolean get() = state == 13 || enabled == true
    }

    /** 服务是否可拿（诊断用，不抛）。 */
    fun available(context: Context): Boolean = manager(context) != null

    fun read(context: Context): Snapshot {
        val mgr = manager(context) ?: return Snapshot(null, null, emptyList())
        val cls = mgr.javaClass
        val state = runCatching {
            cls.getMethod("getWifiApState").invoke(mgr) as? Int
        }.getOrNull()
        val enabled = runCatching {
            cls.getMethod("isWifiApEnabled").invoke(mgr) as? Boolean
        }.getOrNull() ?: state?.let { it == 13 }
        val clients = runCatching { readClients(mgr) }.getOrDefault(emptyList())
        return Snapshot(state, enabled, clients)
    }

    private fun manager(context: Context): Any? = runCatching {
        context.getSystemService(SERVICE_NAME)
    }.getOrNull()

    private fun readClients(mgr: Any): List<Client> {
        val list = mgr.javaClass.getMethod("getSoftApConDevInfo").invoke(mgr) as? List<*>
            ?: return emptyList()
        return list.mapNotNull { item ->
            item ?: return@mapNotNull null
            val c = item.javaClass
            Client(
                name = field(c, item, "devName"),
                mac = field(c, item, "macAddr"),
                ip = field(c, item, "devIpAddr"),
                status = field(c, item, "status")?.toIntOrNull() ?: -1,
            )
        }
    }

    private fun field(cls: Class<*>, target: Any, name: String): String? = runCatching {
        cls.getField(name).get(target)?.toString()
    }.getOrNull()
}
