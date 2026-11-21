package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarHotspotBand

/**
 * 把「ADB 侧探测」与「本地反射」两份结论合起来：ADB 优先，缺的字段用本地补。
 *
 * ## 为什么单独成一个纯函数
 *
 * 它原来在 `CarNativeHotspotProbe` 里（那个类要 `Context` 与 adb 会话，
 * 纯 JVM 单测里构造不了 ⇒ **这段取值顺序一直没有用例兜着**）。但它其实是纯数据逻辑，
 * 而下面两处优先级都是**踩过坑才定下来的**，必须钉住：
 *
 * 1. `ssid` 取 `adb ?: local ?: adb.runningSsid ?: local.runningSsid` ——
 *    **存储配置优先于实际广播名**。上游 `ManualHotspotManager` 会拿存储配置校验
 *    我们发出去的名字，把"实际广播名"盖上去只会立刻被拒；
 *    只有两边都读不到存储配置时才退到实际名（它至少是**真实存在**的网络，
 *    而"名字都读不到 ⇒ 不可复用 ⇒ 自建"会把一张正在跑的热点白白放弃）。
 * 2. `source` 与 [CarNativeHotspotInfo.credentialSource] 必须分开 ——
 *    实车上 SSID/密码来自本地反射，而网卡名与地址来自 adb，
 *    只打一个 `source=ADB_SHELL` 会让读包的人以为凭据也来自 adb
 *    （磁盘上那几份配置文件其实全是过期的）。
 */
object CarNativeHotspotMerge {

    fun merge(
        adb: CarNativeHotspotInfo,
        local: CarNativeHotspotInfo,
    ): CarNativeHotspotInfo {
        val detected = adb.detected || local.detected
        val enabled = adb.enabled || local.enabled
        if (!detected && !enabled) {
            // 两边都没探到。返回 adb 那份（它的 source 是 NONE，语义正确）。
            return adb
        }
        val source = if (adb.detected || adb.enabled) adb.source else local.source
        return CarNativeHotspotInfo(
            detected = detected,
            enabled = enabled,
            // 存储配置读不到时退到"实际在广播的名字"：它至少是**真实存在**的网络，
            // 而"名字都读不到 ⇒ 不可复用 ⇒ 自建"会把一张正在跑的热点白白放弃。
            // 只在两边都读不到时才退，避免"实际名"盖掉更权威的存储配置值
            // （上游会拿存储配置校验我们发出去的名字，盖掉它只会立刻被拒）。
            ssid = adb.ssid ?: local.ssid ?: adb.runningSsid ?: local.runningSsid,
            runningSsid = adb.runningSsid ?: local.runningSsid,
            interfaceName = adb.interfaceName ?: local.interfaceName,
            hostAddress = adb.hostAddress ?: local.hostAddress,
            prefixLength = adb.prefixLength ?: local.prefixLength,
            subnet = adb.subnet ?: local.subnet,
            gateway = adb.gateway ?: local.gateway,
            dhcpStart = adb.dhcpStart ?: local.dhcpStart,
            dhcpEnd = adb.dhcpEnd ?: local.dhcpEnd,
            dns = (adb.dns + local.dns).distinct(),
            occupiedAddresses = adb.occupiedAddresses.ifEmpty { local.occupiedAddresses },
            band = if (adb.band != CarHotspotBand.AUTO) adb.band else local.band,
            channel = if (adb.channel > 0) adb.channel else local.channel,
            security = adb.security,
            passphrase = adb.passphrase ?: local.passphrase,
            source = source,
            // 凭据（SSID / 密码）实际是从哪读到的 —— 与 source 分开记。
            // 实车上 ADB 侧那几份配置文件全是过期的，真正给出 SSID 的是本地反射，
            // 而 source 照样是 ADB_SHELL（网卡名、地址确实是 adb 读的）。
            credentialSource = when {
                adb.ssid != null || adb.passphrase != null -> adb.source
                local.ssid != null || local.passphrase != null -> local.source
                else -> source
            },
        )
    }
}
