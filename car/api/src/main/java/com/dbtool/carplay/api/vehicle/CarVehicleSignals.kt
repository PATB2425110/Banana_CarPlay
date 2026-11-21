package com.dbtool.carplay.api.vehicle

/**
 * 一次车机数据快照；读不到的项为 null（不要用 0 冒充）。
 *
 * [atElapsedMs] 由实现按自己的单调时钟填写，供调用方判断数据新鲜度。
 */
data class CarVehicleSnapshot(
    val speedKph: Int? = null,
    val rpm: Int? = null,
    val gear: String? = null,
    val outsideTempC: Int? = null,
    val atElapsedMs: Long = 0L,
) {
    val hasAny: Boolean
        get() = speedKph != null || rpm != null || gear != null || outsideTempC != null
}

/**
 * 车机数据源。实现必须线程安全、失败降级、不抛。
 *
 * [snapshot] 是同步读取，调用方负责把它放到后台线程；不可在主线程直接调用。
 */
interface CarVehicleSignals {
    fun isAvailable(): Boolean

    /** 同步读一次；读不到返回空快照。 */
    fun snapshot(): CarVehicleSnapshot
}
