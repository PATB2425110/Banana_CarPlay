package com.dbtool.carplay.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import com.dbtool.carplay.api.navigation.CarLocationFix
import com.dbtool.carplay.api.navigation.CarNavigationAdapter

/**
 * 默认定位适配：基于 `LocationManager`，与 upstream
 * `AndroidCarPlayLocationProvider` 同一数据口径（GPS 优先，回落网络定位）。
 *
 * 说明：本模块刻意不依赖 `:shared`，因此这里独立实现而不是复用 upstream 的类；
 * 两者的字段语义保持一致（见 upstream `Iap2LocationClient.CarPlayLocationFix`）。
 *
 * 车机适配点：车机常用专有 GNSS 服务或 CAN 总线定位，继承覆写 [start]/[stop] 即可。
 * 坐标为 GCJ-02 的车机需要覆写 [usesNonWgs84Coordinates] 返回 true。
 */
open class AndroidCarNavigationAdapter(
    protected val context: Context,
) : CarNavigationAdapter {

    @Volatile
    private var listener: ((CarLocationFix) -> Unit)? = null

    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            listener?.invoke(location.toCarLocationFix())
        }

        @Deprecated("Required by the Android SDK interface signature.")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    override val available: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ||
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.allProviders?.isNotEmpty() == true

    @SuppressLint("MissingPermission")
    override fun start(listener: (CarLocationFix) -> Unit) {
        this.listener = listener
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        // 权限由产品 App 负责申请；这里捕获异常而不是崩溃，定位不可用不应影响 CarPlay。
        runCatching {
            val provider = when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: return
            manager.requestLocationUpdates(
                provider,
                MIN_TIME_MILLIS,
                MIN_DISTANCE_METERS,
                locationListener,
                Looper.getMainLooper(),
            )
        }
    }

    override fun stop() {
        listener = null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { manager.removeUpdates(locationListener) }
    }

    override fun usesNonWgs84Coordinates(): Boolean = false

    companion object {
        const val MIN_TIME_MILLIS = 1_000L
        const val MIN_DISTANCE_METERS = 0f
    }
}

private fun android.location.Location.toCarLocationFix(): CarLocationFix = CarLocationFix(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    courseDegrees = if (hasBearing()) bearing else null,
    timestampMillis = time,
    horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
)
