package com.dbtool.carplay.api.navigation

/**
 * 一次定位结果。
 *
 * 只保留 upstream iAP2 位置回传真正会用到、且各车机都能提供的字段，
 * 避免把 Android `Location` 类型泄漏进契约层。
 */
data class CarLocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val courseDegrees: Float? = null,
    /** 绝对时间戳（epoch millis）。 */
    val timestampMillis: Long,
    val horizontalAccuracyMeters: Float? = null,
)

/**
 * 导航 / 定位适配。
 *
 * 对应 upstream 的 `Iap2LocationProvider`：CarPlay 可以把车机定位回传给 iPhone，
 * 让 iPhone 在车机联通期间使用车机的 GNSS（隧道、地库等场景差异很大）。
 *
 * 新增车机通常只需换一个数据源（车机专有 GNSS 服务、CAN 总线、融合定位），
 * 因此独立成接口。
 */
interface CarNavigationAdapter {
    /** 车机是否提供可用定位。false 时 UI 应禁用"上报位置"开关。 */
    val available: Boolean

    /** 开始接收定位。实现需保证可重复调用是幂等的。 */
    fun start(listener: (CarLocationFix) -> Unit)

    fun stop()

    /**
     * 车机定位是否使用自己的坐标系（例如某些国内车机的 GCJ-02）。
     * 返回 true 表示需要在回传前做坐标转换。
     */
    fun usesNonWgs84Coordinates(): Boolean = false
}
