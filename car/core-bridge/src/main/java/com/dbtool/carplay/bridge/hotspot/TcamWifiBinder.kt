package com.dbtool.carplay.bridge.hotspot

import android.os.IBinder
import android.os.Parcel

/**
 * TCAM 热点状态的**真实 Binder 通道**（`android.net.wifi.ITcamWifiManager`）。
 *
 * ## 为什么需要它
 *
 * 这台车（CS11 / OSN2.0）的 WiFi 被 TCAM 接管：
 * - `WifiManager.isWifiApEnabled()` 会假关闭（热点开着也返回 false）；
 * - 厂商 SDK 的 `WifiAp.create()` 内部找 `...wifiap.impl.Wifi6ApImpl`，
 *   而该 ROM 里**没有这个类**（只有 `WifiApImp`）⇒ 抛 ClassNotFoundException，
 *   我们只能拿到 `vendor=null`。
 *
 * 系统里真正可用的接口是 `android.net.wifi.ITcamWifiManager`（服务名 `tcam_wifi`），
 * 反编译 `framework.jar` 得到的交易码：
 *
 * | 交易码 | 方法 | 返回 |
 * |---|---|---|
 * | 47 | `getWifiApEnabledState()` | int（10 DISABLING / 11 DISABLED / 12 ENABLING / 13 ENABLED / 14 FAILED）|
 * | 48 | `getWifiApConfiguration()` | `WifiConfiguration` |
 * | 84 | `getSoftApConDevInfo()` | `List<SoftApConDevInfo>` |
 *
 * 本类只用 47（状态）——它是最关键、解析最简单的一个；客户端列表仍走现有路径。
 * 全部通过反射访问隐藏 API，拿不到就返回 null，**绝不抛**。
 */
object TcamWifiBinder {

    private const val SERVICE_NAME = "tcam_wifi"
    private const val DESCRIPTOR = "android.net.wifi.ITcamWifiManager"
    private const val TX_AP_ENABLED_STATE = 47

    /** TV 上热点状态的真实值；读不到返回 null。 */
    fun apEnabledState(): Int? {
        val binder = service() ?: return null
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (!binder.transact(TX_AP_ENABLED_STATE, data, reply, 0)) return null
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.getOrNull()
    }

    /** 服务是否可拿（诊断用）。 */
    fun available(): Boolean = service() != null

    private fun service(): IBinder? = runCatching {
        val cls = Class.forName("android.os.ServiceManager")
        val m = cls.getMethod("getService", String::class.java)
        m.invoke(null, SERVICE_NAME) as? IBinder
    }.getOrNull()
}
