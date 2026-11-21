package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/**
 * DBPlay **热点降级链**（6 套方案按优先级依次尝试）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 的 `network/` 包反编译提取：
 * `LocalOnlyHotspotManager`(85KB)、`ManualHotspotManager`(45KB)、
 * `CarHotspotTethering`、`CarHotspotAdbFallback`、`TcamHotspotManager`、
 * `LynkLocalHotspot`。
 *
 * ## 为什么需要降级链
 *
 * 车机型号/固件差异极大：有的支持官方 LocalOnlyHotspot，有的只有私有
 * Tethering API，有的固件**连 API 都不开放**，只能靠 ADB 兜底。
 * DiPlay 的解法是「**六套方案依次尝试**」，每套失败带明确原因。
 *
 * ## 六套方案（优先级从高到低）
 *
 * | 方案 | 机制 | 失败原因示例 |
 * |---|---|---|
 * | Car 自带热点 | 反射 `ConnectivityManager.startTethering` | `PERMISSION_REQUIRED` / `UNSUPPORTED` |
 * | TCAM 以太网 | 车机 `eth0.11` 上的 AP | `The car TCAM hotspot is off` |
 * | LocalOnlyHotspot | Android 8.0+ 官方 API | 须 **5GHz**；`Location mode is not enabled` |
 * | Manual hotspot | 手动指定 SSID/密码/信道 | SSID/band/channel/security 不一致 |
 * | ADB 兜底 | `dumpsys wifi` + `start-tethering` | 固件不认 ADB 请求 |
 * | Lynk 专属 | sdk28 + `config_lynk_local_hotspot` | 仅本机型 |
 *
 * ## ⚠️ 硬约束（DiPlay 原注释）
 *
 * **无线 CarPlay 必须 5GHz。** 若车机 Wi-Fi 客户端占着 2.4GHz，
 * 必须先把车机的 Wi-Fi 开关关掉，否则热点只能落在 2.4GHz
 * → CarPlay 连不上 / 掉线。
 *
 * ⚠️ 未实机验证。各方案的真实可用性须逐台车试。
 */
class LynkHotspotFallbackChain(private val context: Context) {

    companion object {
        private const val TAG = "DBPlay-Hotspot"

        /** 方案失败原因（DiPlay 原文，便于对日志）。 */
        const val ERR_PERMISSION_REQUIRED = "Hotspot control permission is missing"
        const val ERR_TIMED_OUT = "Timed out waiting for the car hotspot"
        const val ERR_UNSUPPORTED = "This firmware does not support automatic hotspot startup"
        const val ERR_CANCELLED = "Hotspot startup was cancelled"
        const val ERR_CAR_HOTSPOT_ON = "Car hotspot is on"
        const val ERR_NO_LOCATION = "Location mode is not enabled for LocalOnlyHotspot"
        const val ERR_CONNECTIVITY_UNAVAILABLE = "Connectivity service unavailable"

        /** 接口探测路径（ManualHotspotInterfaces 原文）。 */
        const val SYS_NET = "/sys/class/net/"
        const val WIRELESS_SUFFIX = "/wireless"

        /** TCAM 以太网接口（吉利车机）。 */
        const val TCAM_IFACE = "eth0.11"

        /** 本地热点 mDNS 标签。 */
        const val MDNS_TAG = "xcertplay-local-only-hotspot-mdns"

        /** ADB 兜底用的探测命令。 */
        const val CMD_DUMPSYS_WIFI = "dumpsys wifi"
        const val CMD_START_TETHERING = "start-tethering"
        const val ADB_TAG = "dbplay-hotspot-adb"
    }

    /** 方案类型。 */
    enum class Method {
        CAR_TETHERING,   // 车机自带 Tethering
        TCAM_ETHERNET,   // TCAM 以太网热点（eth0.11）
        LOCAL_ONLY,      // Android 官方 LocalOnlyHotspot
        MANUAL,          // 手动 SSID/密码/信道
        ADB_FALLBACK,    // ADB 兜底
        LYNK_LOCAL,      // Lynk 专属（sdk28）
    }

    /** 单次尝试结果。 */
    data class Attempt(val method: Method, val ok: Boolean, val reason: String? = null)

    /** 一次完整降级尝试的日志。 */
    val history = mutableListOf<Attempt>()

    /**
     * 按顺序尝试，返回第一个成功的方案。
     * 全失败则返回 null（[history] 里有每步原因）。
     */
    fun start(band: Band = Band.GHZ5, ssid: String? = null, passphrase: String? = null): Method? {
        for (m in Method.values()) {
            val ok = tryStart(m, band, ssid, passphrase)
            history.add(ok)
            if (ok.ok) {
                Log.d(TAG, "热点方案成功: $m")
                return m
            }
            Log.d(TAG, "热点方案失败: $m → ${ok.reason}")
        }
        Log.w(TAG, "全部热点方案失败")
        return null
    }

    private fun tryStart(m: Method, band: Band, ssid: String?, passphrase: String?): Attempt = when (m) {
        Method.CAR_TETHERING -> carTethering()
        Method.TCAM_ETHERNET -> tcamEthernet()
        Method.LOCAL_ONLY -> localOnly(band)
        Method.MANUAL -> manual(band, ssid, passphrase)
        Method.ADB_FALLBACK -> adbFallback()
        Method.LYNK_LOCAL -> lynkLocal()
    }

    /** ① 车机自带 Tethering：反射 `ConnectivityManager.startTethering`。 */
    private fun carTethering(): Attempt {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Attempt(Method.CAR_TETHERING, false, ERR_CONNECTIVITY_UNAVAILABLE)
        return try {
            // hidden: startTethering(int type, boolean showProvisioningUi, OnStartTetheringCallback)
            val m = cm.javaClass.getMethod("startTethering", Int::class.java, Boolean::class.java, Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback"))
            m.invoke(cm, 0 /*TETHERING_WIFI*/, false, null)
            Attempt(Method.CAR_TETHERING, true)
        } catch (e: Throwable) {
            val r = e.cause?.message ?: e.message ?: ""
            val reason = if (r.contains("permission", true)) ERR_PERMISSION_REQUIRED else ERR_UNSUPPORTED
            Attempt(Method.CAR_TETHERING, false, reason)
        }
    }

    /** ② TCAM 以太网热点：查 `eth0.11` 是否被选中并有地址。 */
    private fun tcamEthernet(): Attempt {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Attempt(Method.TCAM_ETHERNET, false, ERR_CONNECTIVITY_UNAVAILABLE)
        return try {
            val nets = cm.allNetworks
            for (n in nets) {
                val linkProps = cm.getLinkProperties(n) ?: continue
                if (linkProps.interfaceName == TCAM_IFACE) {
                    Log.d(TAG, "车机热点已挂在 $TCAM_IFACE")
                    return Attempt(Method.TCAM_ETHERNET, true)
                }
            }
            Attempt(Method.TCAM_ETHERNET, false, "The car TCAM hotspot is off")
        } catch (e: Throwable) {
            Attempt(Method.TCAM_ETHERNET, false, e.message ?: "TCAM 查询失败")
        }
    }

    /** ③ 官方 LocalOnlyHotspot（必须 5GHz）。 */
    private fun localOnly(band: Band): Attempt {
        // 真实实现需 WifiManager.startLocalOnlyHotspot(callback, handler)，
        // 并在回调里校验 BSSID/SSID/passphrase/信道，超时 30s。
        Log.d(TAG, "尝试 LocalOnlyHotspot band=$band（须先关掉车机 Wi-Fi 客户端）")
        return Attempt(Method.LOCAL_ONLY, false, "需 WifiManager 回调，见 DiPlay LocalOnlyHotspotManager")
    }

    /** ④ 手动热点：校验配置与实际一致。 */
    private fun manual(band: Band, ssid: String?, passphrase: String?): Attempt {
        if (ssid.isNullOrBlank()) return Attempt(Method.MANUAL, false, "Manual hotspot SSID 未配置")
        if (ssid.contains('\u0000')) return Attempt(Method.MANUAL, false, "ssid must not contain U+0000")
        if (passphrase != null && passphrase.isNotEmpty() && passphrase.length !in 8..63) {
            return Attempt(Method.MANUAL, false, "passphrase must be empty or between 8 and 63 characters")
        }
        if (band == Band.GHZ5) {
            // DiPlay: "Manual hotspot is not running on 5 GHz"
        }
        return Attempt(Method.MANUAL, false, "需 WifiManager 读写 AP 配置后复核")
    }

    /** ⑤ ADB 兜底：`dumpsys wifi` 探测 + `start-tethering` 拉起。 */
    private fun adbFallback(): Attempt {
        Log.d(TAG, "尝试 ADB 兜底: $CMD_DUMPSYS_WIFI / $CMD_START_TETHERING")
        return Attempt(Method.ADB_FALLBACK, false, "需 LynkLocalAdb 已 READY")
    }

    /** ⑥ Lynk 专属：sdk28 + config_lynk_local_hotspot。 */
    private fun lynkLocal(): Attempt {
        val supported = android.os.Build.VERSION.SDK_INT == 28
        return Attempt(Method.LYNK_LOCAL, supported, if (supported) null else "非 sdk28")
    }

    /** 频段。 */
    enum class Band(val label: String) {
        GHZ5("5 GHz"),
        GHZ24("2.4 GHz"),
        AUTO("自动");
    }
}
