package com.dbtool.carplay.bridge.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarManualHotspotPrecheck

/**
 * 读车机自带 Wi-Fi 热点**当前**是开还是关。
 *
 * 2026-10-08 移植自 DiPlay 的 `CarHotspotStatus`（`shared/.../network/CarHotspotStatus.kt`）。
 * 用途只有一个：给「复用车机热点」做连接前的快速检查（见 [CarManualHotspotPrecheck]）——
 * 热点关着时**立刻**给出可执行的报错，而不是让上游空转数十秒后抛一句看不懂的超时。
 *
 * ## 三条纪律（每条都对应一种"错杀"）
 *
 * 1. **读不到就 [CarHotspotEnabledState.UNKNOWN]**，调用方据此**放行**。
 *    车机 ROM 把隐藏 API 裁掉是常态，把"读不到"当"关着"会让本来能用的热点连不上。
 * 2. **不用网卡标志位**。DiPlay 原文：*"Interface flags are not used: BYD keeps
 *    wlan1 up with an address while tethering is off."* —— 网卡 UP 且有地址
 *    **不等于**热点开着（本工程的 `LocalSoftApReader` 正是用这个宽松判据，
 *    所以它的 `enabled` 不能拿来做拦截，只能拿来做"放行"的旁证）。
 * 3. **不做任何写操作**。只读，不注册长期监听（`registerReceiver(null, …)` 取的是
 *    粘性广播，随取随走，不留监听器）。
 *
 * ## 三个信号，从强到弱
 *
 * | 顺序 | 手段 | 说明 |
 * |------|------|------|
 * | ① | `WifiManager.getWifiApState()`（隐藏 API，反射） | 直接查询当前状态机，取值 `10..14`，`13` = 开 |
 * | ② | `WifiManager.isWifiApEnabled()`（隐藏 API，反射） | 直接查询布尔值 |
 * | ③ | 粘性广播 `android.net.wifi.WIFI_AP_STATE_CHANGED` | 最后一次状态变化的回放 |
 *
 * ①② 都是**当前状态**的直接查询，任意一个读通就以它为准；③ 只在两者都读不通时兜底。
 */
internal object CarHotspotEnabledReader {
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val EXTRA_WIFI_AP_MODE = "wifi_ap_mode"

    /** 粘性广播里 `wifi_ap_mode` 为 1 = 普通共享热点；其余（含 local-only）不表态。 */
    private const val AP_MODE_TETHERED = 1

    @SuppressLint("PrivateApi", "MissingPermission")
    fun read(context: Context): CarHotspotEnabledState {
        val appContext = context.applicationContext
        val ecarxSnapshot = readEcarxSnapshot(appContext)
        val ecarxPlatform = EcarxClientRoute.isEcarxPlatform(
            android.os.Build.VERSION.SDK_INT,
            android.os.Build.MODEL,
            android.os.Build.DEVICE,
        )
        return read(appContext, ecarxSnapshot, ecarxPlatform)
    }

    /** Read one Ecarx sample so the status and endpoint decision can use the same moment in time. */
    internal fun readEcarxSnapshot(context: Context): EcarxHotspotReader.Snapshot? {
        val appContext = context.applicationContext
        if (!runCatching { EcarxHotspotReader.available(appContext) }.getOrDefault(false)) return null
        return runCatching { EcarxHotspotReader(appContext).use { it.snapshot() } }.getOrNull()
    }

    @SuppressLint("PrivateApi", "MissingPermission")
    internal fun read(
        context: Context,
        ecarxSnapshot: EcarxHotspotReader.Snapshot?,
        ecarxPlatform: Boolean = false,
    ): CarHotspotEnabledState {
        val appContext = context.applicationContext
        val wifiManager = runCatching {
            appContext.getSystemService(WifiManager::class.java)
        }.getOrNull()

        return read(
            state = {
                wifiManager?.let {
                    WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int
                }
            },
            enabled = {
                wifiManager?.let {
                    WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean
                }
            },
            sticky = {
                appContext.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED))
            },
            // Ecarx OEM APs are hosted outside the Android SoftAP stack. An explicit enabled=true
            // is authoritative; with enabled=null, one unique verified client route is also
            // positive evidence that the factory network is active. Do not treat no-client or
            // ambiguous snapshots as ON.
            nativeEnabled = {
                if (!ecarxPlatform) null else nativeEnabled(
                    ecarxSnapshot,
                    android.os.Build.VERSION.SDK_INT,
                    android.os.Build.MODEL,
                    android.os.Build.DEVICE,
                )
            },
        ).let { state -> applyEcarxUnknownFallback(state, ecarxSnapshot, ecarxPlatform) }
    }

    internal fun nativeEnabled(
        snapshot: EcarxHotspotReader.Snapshot?,
        sdkInt: Int,
        model: String,
        device: String,
    ): Boolean? = snapshot?.enabled ?: snapshot?.takeIf {
        hasVerifiedEcarxClientRoute(it, sdkInt, model, device)
    }?.let { true }

    internal fun applyEcarxUnknownFallback(
        state: CarHotspotEnabledState,
        snapshot: EcarxHotspotReader.Snapshot?,
        ecarxPlatform: Boolean,
    ): CarHotspotEnabledState = if (
        ecarxPlatform && state == CarHotspotEnabledState.OFF &&
            (snapshot == null || snapshot.enabled == null &&
                snapshot.routeStatus != EcarxHotspotReader.RouteStatus.VERIFIED)
    ) {
        CarHotspotEnabledState.UNKNOWN
    } else {
        state
    }

    @SuppressLint("PrivateApi", "MissingPermission")
    internal fun readStandardWifiApState(context: Context): Int? = runCatching {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        WifiManager::class.java.getMethod("getWifiApState").invoke(manager) as? Int
    }.getOrNull()

    internal fun hasVerifiedEcarxClientRoute(
        snapshot: EcarxHotspotReader.Snapshot,
        sdkInt: Int,
        model: String,
        device: String,
    ): Boolean {
        if (snapshot.routeStatus != EcarxHotspotReader.RouteStatus.VERIFIED) return false
        val (name, source) = snapshot.routeSources.entries.singleOrNull()
            ?.let { it.key to it.value } ?: return false
        return EcarxClientRoute.kx11Source(sdkInt, model, device, name, source)
    }

    /**
     * 纯决策部分（可脱机单测）。
     *
     * 三个入参都是 lambda 而不是值：这样"读不到"（抛异常 / 返回 null）能被**逐级降级**，
     * 而不是在调用前就把三次读取全做掉——做了全读，一次 `SecurityException`
     * 就会把后面本来能用的信号一起废掉。
     *
     * @param nativeEnabled 厂商（Ecarx）原厂热点的三态旁证：`true` = SDK 明确说开着，
     *        `false` = SDK 明确说关着，`null` = SDK 不存在或读不到（**不参与判定**）。
     */
    internal fun read(
        state: () -> Int?,
        enabled: () -> Boolean?,
        sticky: () -> Intent?,
        nativeEnabled: () -> Boolean? = { null },
    ): CarHotspotEnabledState {
        // 🟢 厂商 SDK 的结论**优先于**标准 Android API：原厂热点不进标准 API，
        // 它的"关闭"是假状态（见上面 PATCH-0088 说明）。SDK 说开着 ⇒ 直接 ON。
        if (runCatching(nativeEnabled).getOrNull() == true) {
            return CarHotspotEnabledState.ON
        }
        val standard = CarManualHotspotPrecheck.fromWifiApState(runCatching(state).getOrNull())
            .takeIf { it != CarHotspotEnabledState.UNKNOWN }
            ?: runCatching(enabled).getOrNull()
                ?.let { if (it) CarHotspotEnabledState.ON else CarHotspotEnabledState.OFF }
            ?: runCatching(sticky).getOrNull()?.let { intent ->
                runCatching {
                    fromSticky(
                        hasApMode = intent.hasExtra(EXTRA_WIFI_AP_MODE),
                        apMode = intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1),
                        rawState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1),
                    )
                }.getOrDefault(CarHotspotEnabledState.UNKNOWN)
            }
            ?: CarHotspotEnabledState.UNKNOWN
        // 标准判据说「关」，但厂商 SDK 存在且**没能**说开着（null / false）——这不足以推翻
        // 标准判据（可能真的关着），照原样返回，保持原有拦截能力。
        return standard
    }

    /**
     * 粘性广播的三字段 → 三态（纯函数，可脱机单测）。
     *
     * 单独抽出来的理由：`Intent` 在纯 JVM 单测里是 stub（构造与取值都会抛），
     * 而这段"该不该采信这条广播"的判断恰恰是最需要被钉住的部分。
     */
    internal fun fromSticky(
        hasApMode: Boolean,
        apMode: Int,
        rawState: Int,
    ): CarHotspotEnabledState {
        // local-only 那张 AP 的状态**说明不了**车机共享热点的状态（多 AP 的车机上尤其如此）
        // —— DiPlay 原文：*"Local-only AP state says nothing about the car's tethered hotspot,
        // including on multi-AP firmware."*
        if (hasApMode && apMode != AP_MODE_TETHERED) return CarHotspotEnabledState.UNKNOWN
        return CarManualHotspotPrecheck.fromWifiApState(rawState.takeIf { it >= 0 })
    }
}
