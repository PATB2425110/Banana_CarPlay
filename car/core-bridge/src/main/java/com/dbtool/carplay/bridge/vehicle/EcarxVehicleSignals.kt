package com.dbtool.carplay.bridge.vehicle

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dbtool.carplay.api.vehicle.CarVehicleSignals
import com.dbtool.carplay.api.vehicle.CarVehicleSnapshot
import kotlin.math.roundToInt

/**
 * 把 ECarX 的 int 属性读取抽出来，测试可注入假实现。
 *
 * 默认 [isAvailable] 为 true，便于测试只关心 [int]；真实反射实现会覆写它。
 * 这是实现层的注入点，不是给 UI 使用的车辆数据契约。
 */
fun interface PropertySource {
    fun int(id: Int): Int?

    fun isAvailable(): Boolean = true
}

/**
 * ECarX 车辆信号读取实现。
 *
 * 链路（与 VIN 读取的厂商服务调用同源，但按本任务书使用 `car_service`）：
 * `ServiceManager.getService("car_service")` → `IECarXCar$Stub.asInterface`
 * → `getCarService("car_signal")` → `ECarXCarPropertyManagerBase`
 * → `getIntProperty(id, 1)`。
 *
 * 反射只负责建立连接和取值；所有异常都在本类边界内吞掉，读不到即返回 null。
 * [snapshot] 会同步访问 Binder，调用方必须放在后台线程。
 */
class EcarxVehicleSignals(
    private val propertySource: PropertySource = ReflectivePropertySource(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : CarVehicleSignals {

    private val lock = Any()
    private var consecutiveFailures = 0
    private var retryAfterMs = 0L
    private var lastRpmSourceId: Int? = null

    override fun isAvailable(): Boolean = synchronized(lock) {
        runCatching {
            val now = clock()
            if (isMainThread() || inBackoff(now)) {
                false
            } else {
                propertySource.isAvailable()
            }
        }.getOrDefault(false)
    }

    /**
     * 读一次完整快照。
     *
     * 五项属性分别取值，任一项失败都不影响其他项。若整次快照一项都没读到，
     * 视为本轮建链/读数失败，30 秒内不再访问 Binder；任一项读到数据即视为成功，
     * 清空失败计数并解除退避。
     */
    override fun snapshot(): CarVehicleSnapshot = synchronized(lock) {
        val now = runCatching { clock() }.getOrDefault(0L)
        if (isMainThread() || inBackoff(now)) {
            return@synchronized CarVehicleSnapshot(atElapsedMs = now)
        }

        val rawSpeed = readInt(SIGNAL_VEH_SPEED)
        val speedUnit = readInt(SIGNAL_SPEED_UNIT)
        // 转速优先读安全真实值 31612；服务端用 -1 表示无效，此时按读不到处理，
        // 继续回落仪表显示值 31534。31495 在本车 ROM 的信号表中不存在（会抛
        // Invalid property Id），已移除。
        val safeRpm = readValidRpm(SIGNAL_ENGINE_SPEED_SAFE)
        val rpm = safeRpm ?: readValidRpm(SIGNAL_ENGINE_SPEED)
        if (rpm != null) {
            val sourceId = if (safeRpm != null) SIGNAL_ENGINE_SPEED_SAFE else SIGNAL_ENGINE_SPEED
            if (sourceId != lastRpmSourceId) {
                lastRpmSourceId = sourceId
                // 仅源切换时打点；JVM 单测里 android.util.Log 不可用，降级吞掉即可。
                runCatching { Log.d(TAG, "转速源切换：signalId=$sourceId value=$rpm") }
            }
        }
        val gearCode = readInt(SIGNAL_GEAR)
        val outsideTempC = readInt(SIGNAL_OUTSIDE_TEMP)

        val speedKph = rawSpeed?.let {
            if (speedUnit == SPEED_UNIT_MPH) mphToKph(it) else it
        }
        val gear = gearCode?.let(::gearLetter)
        val snapshot = CarVehicleSnapshot(
            speedKph = speedKph,
            rpm = rpm,
            gear = gear,
            outsideTempC = outsideTempC,
            atElapsedMs = now,
        )

        if (snapshot.hasAny) {
            consecutiveFailures = 0
            retryAfterMs = 0L
        } else {
            consecutiveFailures++
            retryAfterMs = now + FAILURE_BACKOFF_MILLIS
        }
        snapshot
    }

    /** 当前连续失败次数；供同模块诊断与单测观察退避状态。 */
    internal val failureCount: Int
        get() = synchronized(lock) { consecutiveFailures }

    private fun readInt(id: Int): Int? =
        runCatching { propertySource.int(id) }.getOrNull()

    /** 转速专用读取：负值（服务端常见的 -1 无效值）一律视为读不到。 */
    private fun readValidRpm(id: Int): Int? = readInt(id)?.takeIf { it >= 0 }

    private fun inBackoff(now: Long): Boolean = now < retryAfterMs

    /** [snapshot] / [isAvailable] 可能碰同步 Binder，主线程一律降级为空数据。 */
    private fun isMainThread(): Boolean = runCatching {
        val current = Looper.myLooper()
        current != null && current == Looper.getMainLooper()
    }.getOrDefault(false)

    /**
     * 档位码映射。
     *
     * ⚠️ 码值未验证，上车要核。当前按常见 ECarX 档位指示顺序 1→P、2→R、
     * 3→N、4→D、5→S、6→M 解释；0 和未知值一律返回 null，不用猜测值冒充档位。
     */
    private fun gearLetter(code: Int): String? = GEAR_CODES[code]

    private fun mphToKph(mph: Int): Int = (mph * MPH_TO_KPH).roundToInt()

    /**
     * 真实实现：每次读取都走 ECarX 的同步 Binder 接口。
     *
     * 连接在第一次需要时建立并复用；取值抛异常时丢弃连接，下一次重新建链。
     */
    private class ReflectivePropertySource : PropertySource {
        private val lock = Any()
        private var connection: Connection? = null

        override fun int(id: Int): Int? = synchronized(lock) {
            val current = runCatching { connection ?: connect() }.getOrNull()
            if (current == null) {
                connection = null
                return@synchronized null
            }

            runCatching {
                current.getIntProperty.invoke(current.manager, id, PROPERTY_AREA) as? Int
            }.getOrElse {
                // 连接失效或厂商接口异常：丢掉 manager，下一轮重新建链。
                connection = null
                logFailure("读取信号 $id 失败", it)
                null
            }
        }

        override fun isAvailable(): Boolean = synchronized(lock) {
            runCatching { connection ?: connect() }.getOrNull() != null
        }

        private fun connect(): Connection? {
            val getService = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
            val raw = (getService.invoke(null, CAR_SERVICE) as? IBinder)
                ?: (getService.invoke(null, CAR_SERVICE_LEGACY) as? IBinder)
                ?: return null

            val car = Class.forName("ecarx.car.IECarXCar\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, raw) ?: return null

            val carService = findMethod(car.javaClass, "getCarService", String::class.java)
                ?.invoke(car, CAR_SIGNAL) as? IBinder ?: return null

            val managerClass = Class.forName("ecarx.car.hardware.property.ECarXCarPropertyManagerBase")
            val manager = instantiatePropertyManager(managerClass, carService) ?: return null
            val getter = findMethod(
                managerClass,
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ) ?: return null

            return Connection(manager, getter).also { connection = it }
        }

        private fun instantiatePropertyManager(cls: Class<*>, binder: IBinder): Any? {
            val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull() ?: return null
            for (constructor in cls.declaredConstructors) {
                val candidate = runCatching {
                    constructor.isAccessible = true
                    val args = constructor.parameterTypes.map { type ->
                        when {
                            type == IBinder::class.java -> binder
                            type == Handler::class.java -> handler
                            type == Boolean::class.javaPrimitiveType -> DBG
                            type == String::class.java -> TAG
                            type == Int::class.javaPrimitiveType -> 0
                            type == Long::class.javaPrimitiveType -> 0L
                            else -> throw IllegalArgumentException("unsupported type: $type")
                        }
                    }.toTypedArray()
                    constructor.newInstance(*args)
                }.getOrNull()
                if (candidate != null) return candidate
            }
            return null
        }

        private fun findMethod(cls: Class<*>, name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method? {
            runCatching {
                return cls.getMethod(name, *parameterTypes).also { it.isAccessible = true }
            }
            for (method in cls.methods) {
                if (method.name == name && method.parameterTypes.contentEquals(parameterTypes)) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }

        private fun logFailure(message: String, throwable: Throwable) {
            runCatching {
                Log.d(TAG, "$message: ${throwable.javaClass.simpleName}")
            }
        }

        private data class Connection(
            val manager: Any,
            val getIntProperty: java.lang.reflect.Method,
        )
    }

    companion object {
        private const val TAG = "dbplay-vehicle"
        /**
         * ⚠️ 2026-10-21 实车（CS11）：仪表显示 `--`，读不到任何信号。
         * 从 `ecarx.car.jar` 的 `classes.dex` 里提取到的**真实** Binder 服务名是
         * `ecarxcar_service`；`car_service` 在该 dex 中根本不存在
         * （`strings classes.dex | grep -E "^car_service$"` 无输出）。
         * 之前写 `car_service` ⇒ `getService()` 返回 null ⇒ 建链必失败。
         * 保留旧名作兜底，兼容可能存在的其它机型。
         */
        private const val CAR_SERVICE = "ecarxcar_service"
        private const val CAR_SERVICE_LEGACY = "car_service"
        private const val CAR_SIGNAL = "car_signal"
        private const val PROPERTY_AREA = 1
        private const val DBG = true

        /**
         * 车速（仪表指示值）。
         * 来源：ecarx.car.jar，
         * `SignalId_VehSpdIndcdVehSpdIndcd = 31095`。
         */
        internal const val SIGNAL_VEH_SPEED = 31095

        /**
         * 车速单位。
         * 来源：ecarx.car.jar，
         * `SignalId_VehSpdIndcdVeSpdIndcdUnit = 31094`；0=km/h，1=mph。
         */
        internal const val SIGNAL_SPEED_UNIT = 31094

        /**
         * 转速 · 仪表**显示值**（`SignalId_EngSpdDispd = 31534`）。
         *
         * ⚠️ 2026-10-22 现场反馈「转速与实际不符」后查清：这是 DIM 想显示什么就报什么，
         * 不是真实发动机转速，所以偶尔会跳/对不上。
         * 保留它只作兜底。
         */
        internal const val SIGNAL_ENGINE_SPEED = 31534

        /**
         * 转速 · **安全真实发动机转速**（`SignalId_EngNSafeEngN = 31612`）。
         *
         * 2026-10-24 实测本车（CS11 ROM）：`31495` 在 `ecarx.car.jar` 信号表中不存在，
         * `getIntProperty` 抛 `Invalid property Id: 0x7b07`，导致每次都回落 31534 的显示值，
         * 仪表主题转速与实际发动机转速对不上。真实转速族是 `31612~31615`
         * （EngNSafeEngN / Chks / Cntr / Grdt），改为优先读 31612。
         * 服务端用 -1 表示无效，读不到或负值再回落 [SIGNAL_ENGINE_SPEED]。
         */
        internal const val SIGNAL_ENGINE_SPEED_SAFE = 31612

        /**
         * 档位指示。
         * 来源：ecarx.car.jar，
         * `SignalId_GearIndcnRecGearIndcn = 31628`。
         */
        internal const val SIGNAL_GEAR = 31628

        /**
         * 车外温度。
         * 来源：ecarx.car.jar，
         * `SignalId_AmbTIndcdWithUnitAmbTIndcd = 31057`。
         */
        internal const val SIGNAL_OUTSIDE_TEMP = 31057

        internal const val SPEED_UNIT_MPH = 1
        internal const val FAILURE_BACKOFF_MILLIS = 30_000L

        private const val MPH_TO_KPH = 1.609344

        /**
         * 档位码 → 显示字母。
         *
         * ⚠️ 码值未验证，上车要核。
         */
        private val GEAR_CODES: Map<Int, String> = mapOf(
            1 to "P",
            2 to "R",
            3 to "N",
            4 to "D",
            5 to "S",
            6 to "M",
        )
    }
}
