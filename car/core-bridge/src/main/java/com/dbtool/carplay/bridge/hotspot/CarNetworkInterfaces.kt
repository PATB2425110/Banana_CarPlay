package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.net.ConnectivityManager
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 「网卡与地址」的唯一读取点（PATCH-0068，2026-10-11）。
 *
 * ## 为什么要有这个类
 *
 * 复用模式要认的、残留判据要等的、诊断包要报的，全都是**同一份**"当前有哪些网卡、
 * 各自带什么地址"。这些读法过去散在 `CarHotspotModeInit`、诊断包与上游
 * `ManualHotspotManager` 里各写一份（上游按网卡名打分挑、我们按规划地址比对），
 * **同一台机读出不同结论** —— 真车日志 2026-10-11 08:57 就是这么把自建残留
 * `192.168.176.1` 当成车机热点通告出去的。
 *
 * 这里收成一处：只做"读"（枚举网卡、取 IPv4、判上行），判据仍由
 * [CarHotspotApInterface] 与 [com.dbtool.carplay.api.hotspot.CarHotspotResidue]
 * 这些纯函数负责（它们能在 JVM 上被单测穷举）。
 *
 * ⚠️ 全部只读、不阻塞：`NetworkInterface` 枚举与 `LinkProperties` 读的都是本地状态。
 */
internal object CarNetworkInterfaces {

    /** 当前可见网卡与地址的快照（`isUplink` = 默认路由那张网卡）。 */
    fun snapshot(context: Context): List<CarHotspotApInterface.Iface> = runCatching {
        val uplink = uplinkInterfaceName(context)
        Collections.list(NetworkInterface.getNetworkInterfaces()).map { networkInterface ->
            CarHotspotApInterface.Iface(
                name = networkInterface.name,
                addresses = ipv4Of(networkInterface),
                isUp = runCatching { networkInterface.isUp }.getOrDefault(false),
                isUplink = networkInterface.name == uplink,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 识别「车机自带热点」的网口与地址；识别不到返回 null（**不猜**）。
     *
     * @param selfAddresses 我们自建热点**实际配上去**的地址（可能没有 ⇒ 空表）。
     */
    fun identifyCarHotspot(context: Context, selfAddresses: List<String>): CarHotspotApInterface.Found? =
        CarHotspotApInterface.identify(snapshot(context), selfAddresses)

    /** 一行诊断（识别的网口/地址 + 全部候选 + 我方自建地址）。 */
    fun debugLine(
        context: Context,
        selfAddresses: List<String>,
        interfaces: List<CarHotspotApInterface.Iface>? = null,
    ): String = CarHotspotApInterface.debugLine(interfaces ?: snapshot(context), selfAddresses)

    /**
     * 某个地址此刻是否挂在**任意**网卡上（= 残留还在）。返回 `(网卡名, 地址)`；不在则 null。
     *
     * @param interfaces 调用方已经读好的快照；传了就**不再枚举**。真车上每次枚举约 1 秒，
     *        复用预检原来要枚举 5 次（日志 8：预检固定 5.37 秒），同一次预检内共用一份即可。
     */
    fun addressPresentAnywhere(
        context: Context,
        address: String,
        interfaces: List<CarHotspotApInterface.Iface>? = null,
    ): Pair<String, String>? {
        val target = address.trim()
        if (target.isEmpty()) return null
        return (interfaces ?: snapshot(context)).asSequence()
            .flatMap { iface -> iface.addresses.asSequence().map { iface.name to it.trim() } }
            .firstOrNull { (_, candidate) -> candidate == target }
    }

    /**
     * 任意网卡上落在**我方候选网段**里的地址（= 自建残留）；返回 `(网卡名, 地址)`，没有则 null。
     *
     * 🔴 2026-10-11：这条判据现在**安全**了 —— 车主明确本车车机热点固定
     * `192.168.15.0/24`（网关 `.1`），而我们的候选池是 `192.168.175~179` / `172.20` / `10.42`，
     * **两者不相交** ⇒ 落在池子里的一定是我们自建留下的。
     *
     * 它比"只认落盘记下的那一个地址"更强：进程被杀、足迹丢失后，照样能把残留认出来并清掉
     * （那正是复用被污染的根因）。
     */
    fun selfPoolAddressAnywhere(
        context: Context,
        interfaces: List<CarHotspotApInterface.Iface>? = null,
    ): Pair<String, String>? =
        (interfaces ?: snapshot(context)).asSequence()
            .flatMap { iface -> iface.addresses.asSequence().map { iface.name to it.trim() } }
            .firstOrNull { (_, address) ->
                CarAdbHotspotDefaults.SUBNET_CANDIDATES.any {
                    CarHotspotApInterface.inSubnet(address, it)
                }
            }

    /** 某张网卡上的 IPv4 字符串（读不到返回空表）。 */
    fun ipv4On(name: String): List<String> = runCatching {
        NetworkInterface.getByName(name)?.let(::ipv4Of).orEmpty()
    }.getOrDefault(emptyList())

    /** 默认路由那张网卡的名字（读不到返回 null）。 */
    fun uplinkInterfaceName(context: Context): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.interfaceName }
    }.getOrNull()

    private fun ipv4Of(networkInterface: NetworkInterface): List<String> =
        Collections.list(networkInterface.inetAddresses)
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
}
