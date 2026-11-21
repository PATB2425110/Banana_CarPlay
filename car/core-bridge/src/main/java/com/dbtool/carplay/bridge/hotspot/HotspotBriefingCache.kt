package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarTransportMode
import com.dbtool.carplay.bridge.CarHotspotBriefing

/**
 * 「热点简报」结果的 TTL 缓存（PATCH-0133）。
 *
 * 简报本身不做新的 ADB 探测，但仍要做 `WifiManager` binder 调用、网卡枚举与
 * 车机原生热点凭据读取。实车诊断包实测单次 2.0–4.0 秒，而一次连接会触发 6–8 次
 * 界面刷新 ⇒ 同一份结论被反复计算，累计 18–32 秒。这里按
 * 「模式 + 会改变结论的设置指纹」缓存 30 秒，把重复的界面刷新收敛成一次计算。
 *
 * ## 为什么是 30 秒
 *
 * - 单次计算 2–4 秒，一次连接 6–8 次调用；
 * - 30 秒覆盖一次连接窗口内的连续刷新，又不至于让用户切完热点再切回来时
 *   长时间看到旧结论；
 * - 用户手动刷新与模式切换走 `force`，不受 TTL 限制。
 *
 * ## 缓存键
 *
 * 只放**会改变简报结论**的字段：热点模式、SSID/密码、频段/信道/加密、
 * 复用地址、ADB 网卡名、SAME_LAN 的地址与 Wi-Fi 凭据。设备名、UI 偏好等
 * 与简报无关的字段不进键，避免无意义的失效。
 *
 * 纯 Kotlin、不依赖 Android：时钟由构造参数注入，单测可以直接推进时间。
 *
 * ⚠️ 本类放在 `:car:core-bridge` 而不是 `:car:app`：单测属于本模块。
 * 它的可见性是 `public`（不是 `internal`），因为 `:car:app` 的 `CarAppController`
 * 需要直接持有它；Kotlin 的 `internal` 只在本 Gradle 模块内可见，跨模块用不了。
 */
class HotspotBriefingCache(
    private val ttlMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** 一次命中：快照本身 + 距离过期还剩多少毫秒（日志用）。 */
    data class Hit(
        val briefing: CarHotspotBriefing,
        val remainingMillis: Long,
    ) {
        val remainingSeconds: Long get() = remainingMillis / 1_000L
    }

    private data class Key(
        val transport: CarTransportMode,
        val hotspotMode: CarHotspotMode,
        val manualHotspotSsid: String,
        val manualHotspotPassphrase: String,
        val manualHotspotBand: CarHotspotBand,
        val manualHotspotChannel: Int,
        val manualHotspotSecurity: CarHotspotSecurity,
        val manualHotspotHostAddress: String,
        val adbHotspotInterface: String,
        val sameLanHostAddress: String,
        val sameLanWifiSsid: String,
        val sameLanWifiPassphrase: String,
    ) {
        companion object {
            fun of(settings: CarSessionSettings) = Key(
                transport = settings.transport,
                hotspotMode = settings.hotspotMode,
                manualHotspotSsid = settings.manualHotspotSsid,
                manualHotspotPassphrase = settings.manualHotspotPassphrase,
                manualHotspotBand = settings.manualHotspotBand,
                manualHotspotChannel = settings.manualHotspotChannel,
                manualHotspotSecurity = settings.manualHotspotSecurity,
                manualHotspotHostAddress = settings.manualHotspotHostAddress,
                adbHotspotInterface = settings.adbHotspotInterface,
                sameLanHostAddress = settings.sameLanHostAddress,
                sameLanWifiSsid = settings.sameLanWifiSsid,
                sameLanWifiPassphrase = settings.sameLanWifiPassphrase,
            )
        }
    }

    private data class Entry(
        val key: Key,
        val briefing: CarHotspotBriefing,
        val storedAtMillis: Long,
    )

    private var entry: Entry? = null

    /**
     * 读取缓存。
     *
     * [force] = true 表示调用方要求强制重算（用户手动刷新 / 模式切换）：
     * 即使指纹相同、TTL 未到也返回 null，让调用方走真实计算路径。
     * 过期条目会被顺手清掉。
     */
    @Synchronized
    fun get(settings: CarSessionSettings, force: Boolean = false): Hit? {
        if (force) return null
        val current = entry ?: return null
        if (current.key != Key.of(settings)) return null
        val elapsed = clock() - current.storedAtMillis
        if (elapsed >= ttlMs) {
            entry = null
            return null
        }
        // 时钟回拨（极少见）按"刚写入"处理，剩余时间不放大到 TTL 之外。
        val remaining = (ttlMs - elapsed.coerceAtLeast(0L)).coerceIn(0L, ttlMs)
        return Hit(briefing = current.briefing, remainingMillis = remaining)
    }

    /** 写入一次计算结果；TTL 从**结果算完**这一刻开始算。 */
    @Synchronized
    fun put(settings: CarSessionSettings, briefing: CarHotspotBriefing) {
        entry = Entry(
            key = Key.of(settings),
            briefing = briefing,
            storedAtMillis = clock(),
        )
    }

    /** 强制失效（模式切换 / 用户手动刷新）。 */
    @Synchronized
    fun clear() {
        entry = null
    }
}
