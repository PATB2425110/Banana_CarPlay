package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.navigation.CarLocationFix
import com.dbtool.carplay.api.navigation.CarNavigationAdapter
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder

/**
 * 把二开侧的 [CarNavigationAdapter] 适配成 upstream 需要的 [Iap2LocationProvider]。
 *
 * upstream 的契约是"拉模型"：controller 需要位置时调用 [latestNmea]，
 * 由 provider 返回最近一次编码好的 `$GPGGA`+`$GPRMC`。
 * 因此这里在 [start] 时订阅车机定位，把每个 fix 转成 NMEA 缓存在 volatile 字段里。
 *
 * 坐标校验：upstream `CarPlayLocationFix` 在 `init` 里 `require` 经纬度合法。
 * 车机定位数据质量参差（未定位时常见 0/NaN/超范围），直接构造会抛异常打断定位链路，
 * 因此这里先做有效性过滤，非法 fix 直接丢弃并计数，供诊断查看。
 */
class UpstreamLocationBridge(
    private val navigation: CarNavigationAdapter,
    private val onDroppedFix: (String) -> Unit = {},
) : Iap2LocationProvider {

    @Volatile
    private var latest: String? = null

    @Volatile
    private var started = false

    @Volatile
    private var droppedFixes = 0

    /** 被丢弃的非法定位数量（诊断用）。 */
    fun droppedFixCount(): Int = droppedFixes

    override fun start(): Boolean {
        if (!navigation.available) return false
        if (started) return true
        started = true
        navigation.start { fix ->
            encode(fix)?.let { latest = it }
        }
        return true
    }

    override fun stop() {
        if (!started) return
        started = false
        navigation.stop()
    }

    override fun latestNmea(): String? = latest

    private fun encode(fix: CarLocationFix): String? {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) {
            countDropped("non-finite coordinates")
            return null
        }
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) {
            countDropped("out-of-range coordinates")
            return null
        }
        return runCatching {
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitudeMeters,
                    bearingDegrees = fix.courseDegrees?.toDouble(),
                    speedMetersPerSecond = fix.speedMetersPerSecond?.toDouble(),
                    accuracyMeters = fix.horizontalAccuracyMeters?.toDouble(),
                    timestampMillis = fix.timestampMillis,
                ),
            )
        }.onFailure { countDropped(it.javaClass.simpleName) }.getOrNull()
    }

    private fun countDropped(reason: String) {
        droppedFixes++
        onDroppedFix(reason)
    }
}
