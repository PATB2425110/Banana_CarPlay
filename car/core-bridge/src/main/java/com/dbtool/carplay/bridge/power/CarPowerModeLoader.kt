package com.dbtool.carplay.bridge.power

import android.content.Context
import com.dbtool.carplay.api.power.CarPowerListener
import com.dbtool.carplay.api.power.CarPowerMode
import com.dbtool.carplay.api.power.CarPowerSnapshot

/**
 * 按类名装载具体车机的电源实现。
 *
 * `:car:core-bridge` **不能**依赖 `:car:platform-ecarx`（依赖方向是
 * platform → api，bridge 与 platform 是兄弟模块），而 Product App 同时打包了两者。
 * 所以这里按名字装载：类不在（非 ECARX 车机 / 该模块没进包）就退回
 * [UnavailableCarPowerMode]，与没有这个功能时完全一致。
 */
internal object CarPowerModeLoader {

    private const val ECARX_IMPLEMENTATION =
        "com.dbtool.carplay.ecarx.power.EcarxCarPowerMode"

    fun create(context: Context, log: (String) -> Unit): CarPowerMode =
        runCatching {
            val loader = Class.forName(ECARX_IMPLEMENTATION)
            loader.getConstructor(Context::class.java).newInstance(context) as CarPowerMode
        }.getOrElse { error ->
            log(
                "电源模式：ECARX 实现不可装载" +
                    "（${error.javaClass.simpleName}: ${error.message}），熄火自动暂停不启用",
            )
            UnavailableCarPowerMode
        }
}

/** 没有电源接口的车机：一律"不可用"，所有动作都是空操作。 */
internal object UnavailableCarPowerMode : CarPowerMode {
    override fun start(listener: CarPowerListener) {}
    override fun stop() {}
    override fun current(): CarPowerSnapshot? = null
    override fun isAvailable(): Boolean = false
}
