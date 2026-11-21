package com.dbtool.carplay.api.power

/**
 * 车机电源状态快照。
 *
 * 字段刻意保留 `null`：读不到时**不能**当成"没熄火"或"熄火了"中的任何一个，
 * 调用方必须把 `null` 当作"本次没有结论"。部分车机只提供其中一项，另一项就是 `null`。
 *
 * @param entertainmentOn 娱乐模式是否开启（ECARX `getEntertainmentMode()` 的 1/0）。
 * @param infotainmentMode 信息娱乐模式原始值（ECARX `getInfotainmentMode()`：0=关、1=开、
 *   2=部分、3=迎宾）。
 */
data class CarPowerSnapshot(
    val entertainmentOn: Boolean?,
    val infotainmentMode: Int?,
)

/** 电源状态变化回调。实现方应保证回调安全、不抛异常。 */
fun interface CarPowerListener {
    fun onChanged(snapshot: CarPowerSnapshot)
}

/**
 * 车机电源模式的读取与订阅。
 *
 * 放在 `:car:api` 是为了让 `:car:core-bridge` 只依赖契约，具体反射实现留在
 * `:car:platform-ecarx`。没有该能力的车机应当返回一个 `isAvailable() == false`
 * 的实现，调用方据此整条链路不启用。
 */
interface CarPowerMode {
    /** 开始订阅。可重复调用；实现方自行去重。 */
    fun start(listener: CarPowerListener)

    /** 停止订阅并释放监听器。可重复调用。 */
    fun stop()

    /** 当前电源状态；读不到返回 `null`。 */
    fun current(): CarPowerSnapshot?

    /** 厂商接口是否可用（类 / 方法探测成功）。探测失败不得抛异常。 */
    fun isAvailable(): Boolean
}
