package com.dbtool.carplay.bridge.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import com.dbtool.carplay.api.hotspot.CarHotspotProbeSource
import com.dbtool.carplay.api.hotspot.CarHotspotSubnet
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 不经 ADB、直接在本进程里读车机 SoftAP 现状。
 *
 * ## 定位
 *
 * 这是 [CarNativeHotspotProbe] 的**次选来源**：ADB 走不通（车机没开 ADB、
 * 没有 su、二进制 ABI 不符）时，至少还能靠 `WifiManager` 拿到一点东西。
 *
 * 但它有明确的先天缺陷，必须如实对待：
 * - `getSoftApConfiguration()` 在 Android 11+ 需要 `NETWORK_SETTINGS` 系统权限，
 *   三方应用调用通常抛 `SecurityException`；
 * - 即使拿到了，`getPassphrase()` 在不少 ROM 上被置空；
 * - `isWifiApEnabled()` 是隐藏 API，Android 12+ 部分 ROM 已移除。
 *
 * ⇒ 结论常常是"部分字段读不到"。**不要用默认值填坑**，读不到就留 null，
 * 让调用方知道该去要 ADB。
 *
 * ## 为什么单独成类
 *
 * 这段反射原来写在 `CarHotspotResolver` 里（私有方法），现在探测链路也要用。
 * 抄一份意味着两处口径迟早不一致——"设置页说复用了车机热点、开发者页说没探到"
 * 这种自相矛盾最难查。
 */
internal object LocalSoftApReader {

    @SuppressLint("PrivateApi", "MissingPermission")
    fun read(
        context: Context,
        interfaces: List<CarHotspotApInterface.Iface>? = null,
    ): CarNativeHotspotInfo {
        val appContext = context.applicationContext
        val wifiManager = runCatching {
            appContext.getSystemService(WifiManager::class.java)
        }.getOrNull()

        // 调用方已经枚举过网卡就直接用那份快照：真车上一次枚举约 1 秒（日志 8），
        // 复用预检里它是 5 次枚举之一。判据与 [activeApInterfaceName] 一致。
        // ⚠️ 传了快照就**不再回退**去枚举：Ecarx 的 AP 在外置 T-Box 上，本机根本没有 Wi-Fi AP 网卡，
        // "找不到"正是这台车的常态；回退等于每次白白再枚举一次（约 1 秒）。
        val interfaceName = if (interfaces != null) {
            apInterfaceNameFrom(interfaces)
        } else {
            activeApInterfaceName(appContext)
        }
        val enabledByReflection = if (wifiManager == null) {
            false
        } else {
            runCatching {
                WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifiManager) as? Boolean
            }.getOrNull() == true
        }
        val enabled = enabledByReflection || interfaceName != null

        val dump = if (wifiManager == null) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ⚠️ 2026-10-10：`SoftApConfiguration` 是 **API 30** 才有的类，
            // 而本产品要跑在 Android 9（28）上。这里写成显式版本分支（而不是
            // 只在方法体里 early-return）——**判据写在调用点上**，lint 才能静态确认，
            // 人也一眼看得出"新 API 只在 30+ 走"。30+ 仍保留旧的兜底顺序。
            readSoftApConfiguration(wifiManager) ?: readLegacyApConfiguration(wifiManager)
        } else {
            readLegacyApConfiguration(wifiManager)
        }

        val hostAddress = interfaceName?.let { ipv4Of(it) }
        val prefix = hostAddress?.let { 24 }
        val detected = enabled && (dump?.ssid != null || interfaceName != null)

        return CarNativeHotspotInfo(
            detected = detected,
            enabled = enabled,
            ssid = dump?.ssid,
            interfaceName = interfaceName,
            hostAddress = hostAddress,
            prefixLength = prefix,
            subnet = hostAddress?.let { CarHotspotSubnet.networkOf(it, 24) },
            // 网关在 AP 场景下就是 AP 自己的地址：客户端把 AP 当默认网关。
            // 这是**定义**而不是"读到的值"，所以只在拿到 hostAddress 时才给。
            gateway = hostAddress,
            dhcpStart = null,
            dhcpEnd = null,
            dns = emptyList(),
            band = dump?.band ?: CarHotspotBand.AUTO,
            channel = dump?.channel ?: 0,
            security = dump?.security ?: CarHotspotSecurity.WPA2,
            passphrase = dump?.passphrase,
            source = if (detected || enabled) {
                CarHotspotProbeSource.LOCAL_REFLECTION
            } else {
                CarHotspotProbeSource.NONE
            },
        )
    }

    // ---------------------------------------------------------------- 网卡

    /**
     * 找一张"像 AP"的 Wi-Fi 网卡。
     *
     * 判据刻意收紧：**只认 Wi-Fi 系网卡名**（`wlan*` / `ap*` / `softap*`），
     * 且排除默认路由网卡（那张一定是正在用的上行，拿它当 AP 会掐断车机自己的网络）。
     *
     * ⚠️ 早期版本写的是 `wlan* 且 != wlan0`——那是错的：**部分车机的 AP 网卡
     * 就叫 `wlan0`**，而 STA 侧叫别的。按名字排除 `wlan0` 会直接把正确的那张漏掉。
     * 正确的判据是"是不是默认路由网卡"，不是"叫什么名字"。
     */
    internal fun apInterfaceNameFrom(interfaces: List<CarHotspotApInterface.Iface>): String? =
        interfaces.firstOrNull { iface ->
            looksLikeWifiInterface(iface.name) && !iface.isUplink &&
                iface.addresses.any { !it.startsWith("169.254.") }
        }?.name

    private fun activeApInterfaceName(context: Context): String? {
        val uplink = uplinkInterface(context)
        // `NetworkInterface.isUp()` 在真机上对热点网卡会抛 SocketException，
        // 所以整段都要包住，不能只包 getNetworkInterfaces()。
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        return interfaces.asSequence()
            .mapNotNull { network ->
                val name = runCatching { network.name }.getOrNull() ?: return@mapNotNull null
                if (!looksLikeWifiInterface(name)) return@mapNotNull null
                if (name == uplink) return@mapNotNull null
                val hasIpv4 = runCatching {
                    network.inetAddresses.toList().any { it is Inet4Address && !it.isLinkLocalAddress }
                }.getOrDefault(false)
                name.takeIf { hasIpv4 }
            }
            .firstOrNull()
    }

    /**
     * 只认 Wi-Fi 系网卡名。
     *
     * 刻意**不**把 `eth*` / `rmnet*` / `usb*` / `rndis*` / `tun*` 算进来：
     * 它们有 IPv4 很常见（蜂窝、USB 网卡），但都不是 AP，误判会让"探测到车机热点"
     * 变成假阳性，进而走 attach 通路一路等到超时。
     */
    private fun looksLikeWifiInterface(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap")

    private fun uplinkInterface(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val active = manager.activeNetwork ?: return@runCatching null
        manager.getLinkProperties(active)?.interfaceName
    }.getOrNull()

    private fun ipv4Of(name: String): String? = runCatching {
        NetworkInterface.getByName(name)?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    // ------------------------------------------------------------ 配置读取

    private class Dump(
        val ssid: String?,
        val passphrase: String?,
        val band: CarHotspotBand,
        val channel: Int,
        val security: CarHotspotSecurity,
    )

    @SuppressLint("PrivateApi", "MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readSoftApConfiguration(wifiManager: WifiManager): Dump? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val configuration = WifiManager::class.java
                .getMethod("getSoftApConfiguration")
                .invoke(wifiManager) as? SoftApConfiguration
                ?: return@runCatching null
            val ssid = configuration.ssid ?: return@runCatching null
            Dump(
                ssid = ssid,
                // `getPassphrase()` 是 API 30 起的公开方法；WPA3-SAE 与部分厂商实现
                // 会返回 null，这里如实保留 null，由调用方决定能不能复用。
                passphrase = runCatching { configuration.passphrase }.getOrNull(),
                band = softApBand(configuration),
                channel = softApChannel(configuration),
                security = mapSoftApSecurity(configuration.securityType),
            )
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun softApBand(configuration: SoftApConfiguration): CarHotspotBand {
        if (Build.VERSION.SDK_INT >= 36) {
            val channels = runCatching { configuration.channels }.getOrNull()
            if (channels != null && channels.size() > 0) return bandOf(channels.keyAt(0))
            return CarHotspotBand.AUTO
        }
        val band = runCatching {
            (SoftApConfiguration::class.java.getMethod("getBand").invoke(configuration) as? Number)
                ?.toInt()
        }.getOrNull()
        return band?.let(::bandOf) ?: CarHotspotBand.AUTO
    }

    @SuppressLint("PrivateApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun softApChannel(configuration: SoftApConfiguration): Int {
        if (Build.VERSION.SDK_INT >= 36) {
            val channels = runCatching { configuration.channels }.getOrNull()
            if (channels != null && channels.size() > 0) return channels.valueAt(0)
            return 0
        }
        return runCatching {
            (SoftApConfiguration::class.java.getMethod("getChannel").invoke(configuration) as? Number)
                ?.toInt()
        }.getOrNull() ?: 0
    }

    @SuppressLint("PrivateApi", "MissingPermission")
    private fun readLegacyApConfiguration(wifiManager: WifiManager): Dump? = runCatching {
        val configuration = WifiManager::class.java
            .getMethod("getWifiApConfiguration")
            .invoke(wifiManager) as? WifiConfiguration
            ?: return@runCatching null
        val ssid = unquote(configuration.SSID) ?: return@runCatching null
        Dump(
            ssid = ssid,
            passphrase = unquote(configuration.preSharedKey),
            band = runCatching {
                WifiConfiguration::class.java.getField("apBand").getInt(configuration)
            }.getOrNull()?.let(::bandOf) ?: CarHotspotBand.AUTO,
            channel = runCatching {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            }.getOrDefault(0),
            security = mapWifiConfigurationSecurity(configuration),
        )
    }.getOrNull()

    private fun mapSoftApSecurity(securityType: Int): CarHotspotSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> CarHotspotSecurity.OPEN
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> CarHotspotSecurity.WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> CarHotspotSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> CarHotspotSecurity.WPA3
        // 未知取值按最保守的 WPA2 处理：宁可让用户填一次密码，
        // 也不要按"开放"处理导致 iPhone 侧握手直接失败。
        else -> CarHotspotSecurity.WPA2
    }

    private fun mapWifiConfigurationSecurity(configuration: WifiConfiguration): CarHotspotSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return CarHotspotSecurity.OPEN
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> CarHotspotSecurity.OPEN
            wpa2 && sae -> CarHotspotSecurity.WPA3_TRANSITION
            wpa2 -> CarHotspotSecurity.WPA2
            sae -> CarHotspotSecurity.WPA3
            else -> CarHotspotSecurity.WPA2
        }
    }

    /** Android 的 band 常量：1 = 2.4GHz、2 = 5GHz、3 = 6GHz。 */
    private fun bandOf(value: Int): CarHotspotBand = when (value) {
        1 -> CarHotspotBand.GHZ_2_4
        2 -> CarHotspotBand.GHZ_5
        else -> CarHotspotBand.AUTO
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
