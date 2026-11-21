package com.dbtool.carplay.ecarx.power

import android.content.Context
import android.util.Log
import com.dbtool.carplay.api.power.CarPowerListener
import com.dbtool.carplay.api.power.CarPowerMode
import com.dbtool.carplay.api.power.CarPowerSnapshot
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ECARX 电源模式（`framework/ecarx.adaptapi.jar`）的反射实现。
 *
 * ## 为什么必须用反射
 *
 * 厂商类只存在于车机固件里，编译期没有可依赖的 jar；直接 import 会让
 * 非 ECARX 车机上的 APK **类加载失败**。所以这里全程按名字探测，任何一步失败
 * 都只让 [isAvailable] 返回 false，绝不影响应用启动。
 *
 * ## 数值来自反编译（不是猜的）
 *
 * 反编译 `com.ecarx.xui.adaptapi.device.ext.PowerManager` 可见：
 * - 回调 `onSystemModeStateChanged` 的娱乐模式 `mode` 恒为 `1049088`，
 *   `state` 为 `1049089`（OFF）/ `1049090`（ON）；
 * - 信息娱乐模式 `mode` 为 `1052672`，`state` 为 `1052673`（OFF）/ `1052674`（ON）；
 * - `getEntertainmentMode()` 返回的是 `PowerStatus.EM_OFF=0` / `EM_ON=1`。
 *
 * 两个值域**不同**（回调是 ISystemMode 常量、读取是 PowerStatus 常量），
 * 混用会把"熄火"永远判成 ON，见 [EcarxPowerModeValues] 的两个映射函数。
 */
class EcarxCarPowerMode @JvmOverloads constructor(
    private val context: Context,
    private val diagnostics: (String) -> Unit = {},
) : CarPowerMode {

    private val lock = Any()

    private var probed = false
    private var available = false
    private var warned = false

    private var manager: Any? = null
    private var listenerProxy: Any? = null
    private var registerMethod: Method? = null
    private var unregisterMethod: Method? = null
    private var readEntertainmentMethod: Method? = null
    private var readInfotainmentMethod: Method? = null

    @Volatile
    private var listener: CarPowerListener? = null

    private var registered = false
    private var lastSnapshot: CarPowerSnapshot? = null

    override fun isAvailable(): Boolean = ensureProbed()

    override fun start(listener: CarPowerListener) {
        this.listener = listener
        if (!ensureProbed()) return
        val ready = synchronized(lock) {
            if (registered) return
            Triple(manager, listenerProxy, registerMethod)
        }
        val manager = ready.first ?: return
        val proxy = ready.second ?: return
        val register = ready.third ?: return

        // 同一 Proxy 注册两种模式，按回调里的 mode 分派（见 [onSystemModeStateChanged]）。
        // 厂商实现把同一次注册同时挂给了 0/4 两个回调，重复注册是幂等的（Set）。
        var anyRegistered = false
        for (mode in intArrayOf(
            EcarxPowerModeValues.SYSTEM_MODE_ENTERTAINMENT,
            EcarxPowerModeValues.SYSTEM_MODE_INFOTAINMENT,
        )) {
            val ok = runCatching { register.invoke(manager, mode, proxy) as? Boolean ?: true }
                .getOrElse { error ->
                    warnOnce("registerListener($mode) 失败", error)
                    false
                }
            if (ok) anyRegistered = true
        }
        synchronized(lock) { registered = anyRegistered }
        if (!anyRegistered) {
            diagnostics("电源模式：注册监听失败，熄火自动暂停不生效")
        }
    }

    override fun stop() {
        listener = null
        val ready = synchronized(lock) {
            if (!registered) return
            Triple(manager, listenerProxy, unregisterMethod)
        }
        val manager = ready.first ?: return
        val proxy = ready.second ?: return
        val unregister = ready.third ?: return
        runCatching { unregister.invoke(manager, proxy) }
            .onFailure { error -> warnOnce("unregisterListener 失败", error) }
        synchronized(lock) {
            registered = false
            lastSnapshot = null
        }
    }

    override fun current(): CarPowerSnapshot? {
        if (!ensureProbed()) return null
        val manager = synchronized(lock) { manager } ?: return null
        val rawEntertainment = read(readEntertainmentMethod, manager)
        val infotainmentMode = read(readInfotainmentMethod, manager)
        val entertainmentOn = rawEntertainment?.let(EcarxPowerModeValues::entertainmentOnFromPowerStatus)
        if (entertainmentOn == null && infotainmentMode == null) return null
        val snapshot = CarPowerSnapshot(entertainmentOn, infotainmentMode)
        synchronized(lock) { lastSnapshot = snapshot }
        return snapshot
    }

    /** 反射调用无参读方法；失败返回 null（不抛）。 */
    private fun read(method: Method?, manager: Any): Int? {
        val target = method ?: return null
        return runCatching { (target.invoke(manager) as? Number)?.toInt() }.getOrNull()
    }

    /**
     * 回调落地：Binder 线程 → 合并快照 → 交给监听者。
     *
     * 合并而不是每次新建整份快照：一次回调只带一种模式，另一项要沿用上次读到的值，
     * 否则"娱乐 OFF"会把信息娱乐模式冲成 null。
     */
    private fun onSystemModeStateChanged(mode: Int, state: Int) {
        val updated = synchronized(lock) {
            val previous = lastSnapshot
            val next = when (mode) {
                EcarxPowerModeValues.SYSTEM_MODE_ENTERTAINMENT -> {
                    val on = EcarxPowerModeValues.entertainmentOnFromSystemModeState(state)
                        ?: return
                    CarPowerSnapshot(on, previous?.infotainmentMode)
                }
                EcarxPowerModeValues.SYSTEM_MODE_INFOTAINMENT -> {
                    val infotainment = EcarxPowerModeValues.infotainmentModeFromState(state)
                        ?: return
                    CarPowerSnapshot(previous?.entertainmentOn, infotainment)
                }
                else -> return
            }
            if (next == previous) return
            lastSnapshot = next
            next
        }
        // 监听者在锁外调用，避免策略/会话那一侧的回调把我们卡在这里。
        listener?.onChanged(updated)
    }

    /** 探测一次厂商类；失败只 `Log.w` 一次。 */
    private fun ensureProbed(): Boolean {
        synchronized(lock) {
            if (probed) return available
            probed = true
            try {
                val powerManagerClass = Class.forName(POWER_MANAGER_CLASS)
                val instance = powerManagerClass
                    .getMethod("getInstance", Context::class.java)
                    .invoke(null, context)
                    ?: throw IllegalStateException("getInstance 返回 null")
                val systemModeClass = powerManagerClass.interfaces
                    .firstOrNull { it.name == SYSTEM_MODE_CLASS }
                    ?: Class.forName(SYSTEM_MODE_CLASS)
                val listenerClass = systemModeClass.declaredClasses
                    .firstOrNull { it.simpleName == "ISystemModeListener" }
                    ?: Class.forName("$SYSTEM_MODE_CLASS\$ISystemModeListener")

                val proxy = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                    ListenerHandler(),
                )
                manager = instance
                listenerProxy = proxy
                registerMethod = powerManagerClass.getMethod(
                    "registerListener",
                    Int::class.javaPrimitiveType,
                    listenerClass,
                )
                unregisterMethod = powerManagerClass.getMethod(
                    "unregisterListener",
                    listenerClass,
                )
                readEntertainmentMethod = runCatching {
                    powerManagerClass.getMethod("getEntertainmentMode")
                }.getOrNull()
                readInfotainmentMethod = runCatching {
                    powerManagerClass.getMethod("getInfotainmentMode")
                }.getOrNull()
                available = true
            } catch (error: Throwable) {
                warnOnce("ECARX 电源接口不可用", error)
                available = false
            }
            return available
        }
    }

    private fun warnOnce(message: String, error: Throwable) {
        synchronized(lock) {
            if (warned) return
            warned = true
        }
        Log.w(TAG, "$message（类 $POWER_MANAGER_CLASS）", error)
        diagnostics("$message：${error.javaClass.simpleName}: ${error.message}")
    }

    /** 反射回调的落地实现，只认 `onSystemModeStateChanged(int, int)`。 */
    private inner class ListenerHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "onSystemModeStateChanged" -> {
                    val mode = (args?.getOrNull(0) as? Number)?.toInt()
                    val state = (args?.getOrNull(1) as? Number)?.toInt()
                    if (mode != null && state != null) onSystemModeStateChanged(mode, state)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "EcarxCarPowerModeListener"
                else -> null
            }
    }

    private companion object {
        const val TAG = "PowerMode"
        const val POWER_MANAGER_CLASS = "com.ecarx.xui.adaptapi.device.ext.PowerManager"
        const val SYSTEM_MODE_CLASS = "com.ecarx.xui.adaptapi.device.ext.ISystemMode"
    }
}

/**
 * ECARX 电源接口的数值与映射（纯常量 + 纯函数，可 JVM 单测）。
 *
 * 两个值域务必分清：
 * - [entertainmentOnFromPowerStatus] 吃 `getEntertainmentMode()` 的 0/1；
 * - [entertainmentOnFromSystemModeState] 吃回调里的 1049089/1049090。
 */
internal object EcarxPowerModeValues {
    const val SYSTEM_MODE_ENTERTAINMENT = 1049088
    const val SYSTEM_MODE_INFOTAINMENT = 1052672

    const val ENTERTAINMENT_MODE_OFF = 1049089
    const val ENTERTAINMENT_MODE_ON = 1049090

    const val INFOTAINMENT_MODE_OFF = 1052673
    const val INFOTAINMENT_MODE_ON = 1052674

    const val POWER_STATUS_EM_OFF = 0
    const val POWER_STATUS_EM_ON = 1

    /** `getEntertainmentMode()` 的 0/1 → 开关；其它值读不懂。 */
    fun entertainmentOnFromPowerStatus(rawMode: Int): Boolean? = when (rawMode) {
        POWER_STATUS_EM_OFF -> false
        POWER_STATUS_EM_ON -> true
        else -> null
    }

    /** 娱乐模式回调的 1049089/1049090 → 开关；其它值读不懂。 */
    fun entertainmentOnFromSystemModeState(state: Int): Boolean? = when (state) {
        ENTERTAINMENT_MODE_OFF -> false
        ENTERTAINMENT_MODE_ON -> true
        else -> null
    }

    /** 信息娱乐模式回调的 1052673/1052674 → 0/1（与 `getInfotainmentMode()` 同值域）。 */
    fun infotainmentModeFromState(state: Int): Int? = when (state) {
        INFOTAINMENT_MODE_OFF -> 0
        INFOTAINMENT_MODE_ON -> 1
        else -> null
    }
}
