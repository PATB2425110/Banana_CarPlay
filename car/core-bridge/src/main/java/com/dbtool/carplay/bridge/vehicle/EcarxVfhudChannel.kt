package com.dbtool.carplay.bridge.vehicle

import android.content.Context
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * HUD 通道可用性（三态）。
 *
 * [Unknown] 与 [Unavailable] 的区别是**有没有试过**：
 * - [Unknown]：本进程还没建立过反射链（例如 `hudPushEnabled=false`，从未探测）；
 * - [Unavailable]：试过，且失败；[Unavailable.reason] 指出卡在哪一步。
 *
 * ⚠️ `Available` 只表示**反射链打通、拿到了 VfhudManager**，不表示
 * `CB_VF_HUD_ActvReq(1)` 一定能让 HUD 点亮 —— 后者只能实车验证。
 */
sealed interface HudChannelAvailability {
    data object Unknown : HudChannelAvailability

    data object Available : HudChannelAvailability

    /** @param reason 面向排查的原因，带步骤名与异常摘要。 */
    data class Unavailable(val reason: String) : HudChannelAvailability
}

/**
 * `CB_VF_HUD_ActvReq(int)` 的调用结果。
 *
 * 刻意**保留厂商返回值**：HudBridge 用 `Objects.toString()` 后直接丢弃，
 * 现场无法区分「调用成功但 HUD 没亮」与「调用被厂商拒绝」。
 */
sealed interface HudCallResult {
    /** 反射调用已送达厂商接口；[raw] 原样保留返回值对象。 */
    data class Returned(
        val requestValue: Int,
        val raw: Any?,
        val returnText: String,
    ) : HudCallResult {
        /**
         * 只认厂商 `ApiResult.SUCCEED`。
         *
         * ⚠️ 不同 ROM 的枚举名可能不同；这里不把非 SUCCEED 当成链不可用，
         * 只作为结果字段交给调用方/日志判断。
         */
        val apiSucceeded: Boolean get() = returnText == "SUCCEED"
    }

    /** 通道没建立或反射调用抛异常。 */
    data class Failed(val requestValue: Int, val reason: String) : HudCallResult
}

/** 反射链的步骤，失败原因里必须点名卡在哪一步。 */
internal enum class HudChainStep(val label: String) {
    SERVICE_MANAGER("ServiceManager.getService(\"ecarxcar_service\")"),
    CAR_INTERFACE("IECarXCar\$Stub.asInterface"),
    CREATE_CAR("ECarXCar.createCar"),
    SET_MANAGER("ECarXCar.getCarManager(\"car_publicattribute\")"),
    VFHUD_MANAGER("ECarXCarSetManager.getECarXCarVfhudManager"),
}

internal class HudChainException(
    val step: HudChainStep,
    cause: Throwable,
) : Exception("${step.label}: ${describeThrowable(cause)}", cause)

/**
 * 反射调用接缝：单测注入假实现，永远不在 JVM 单测里碰真 Binder。
 *
 * [open] 失败时必须抛异常（真实实现抛 [HudChainException]），由
 * [EcarxVfhudChannel] 在边界内吞掉并转成 [HudChannelAvailability.Unavailable]。
 */
internal interface VfhudAccess {
    /** 建立完整反射链并返回 VfhudManager（不透明对象）。 */
    fun open(): Any

    /** 调 `CB_VF_HUD_ActvReq(value)`，返回厂商 ApiResult（或 null）。 */
    fun request(manager: Any, value: Int): Any?
}

/**
 * ECarX HUD 使能通道。
 *
 * ## 链路（每一步都在 `car/platform-ecarx/libs/ecarx.car.jar` 里核实过）
 *
 * ```
 * android.os.ServiceManager.getService("ecarxcar_service")
 *   → ecarx.car.IECarXCar$Stub.asInterface(IBinder)
 *   → ecarx.car.ECarXCar.createCar(Context, IECarXCar)
 *   → ECarXCar.getCarManager("car_publicattribute")   // 实际返回 ECarXCarSetManager
 *   → ECarXCarSetManager.getECarXCarVfhudManager()
 *   → ECarXCarVfhudManager.CB_VF_HUD_ActvReq(int)
 * ```
 *
 * 依据（dex 签名，非反编译猜测）：
 * - `ECarXCar.SERVICE_NAME = "ecarxcar_service"`、`ECarXCar.PA_SERVICE = "car_publicattribute"`；
 * - `IECarXCar$Stub.asInterface(Landroid/os/IBinder;)Lecarx/car/IECarXCar;`；
 * - `ECarXCar.createCar(Landroid/content/Context;Lecarx/car/IECarXCar;)Lecarx/car/ECarXCar;`；
 * - `ECarXCar.getCarManager(Ljava/lang/String;)Ljava/lang/Object;`；
 * - `ECarXCarSetManager.getECarXCarVfhudManager()Lecarx/car/hardware/vehicle/ECarXCarVfhudManager;`；
 * - `ECarXCarVfhudManager.CB_VF_HUD_ActvReq(I)Lecarx/car/hardware/annotation/ApiResult;`；
 * - `ManagerId_cbvfhudactvreq = 32928`、`VehicleArea_Global = 1`（见常量 [HUD_ACTV_PROPERTY]）。
 *
 * ## 与 HudBridge 的两处刻意差异
 *
 * 1. **停止时调 `CB_VF_HUD_ActvReq(0)`**。HudBridge 的停止路径只推导航结束、
 *    从不发 0，HUD 可能一直保持最后一次使能状态。
 * 2. **返回值必须落日志**。HudBridge 把 `Objects.toString()` 的结果直接扔掉；
 *    本类保留 `raw` 并把 `SUCCEED/FAILED/...` 写进 `xcertplay-hud`。
 *
 * ## 线程与异常
 *
 * 反射/ Binder 调用可能阻塞，调用方应放在后台线程。所有异常都在本类边界内
 * 吞掉并转成三态/结果对象，**绝不向主流程抛**。
 */
class EcarxVfhudChannel internal constructor(
    private val access: VfhudAccess,
    private val logger: (String) -> Unit,
) {
    constructor(context: Context) : this(
        access = ReflectiveVfhudAccess(
            context.applicationContext,
            logger = { line -> Log.i(TAG, line) },
        ),
        logger = { line -> Log.i(TAG, line) },
    )

    private val lock = Any()
    private var manager: Any? = null
    private var state: HudChannelAvailability = HudChannelAvailability.Unknown

    /** 当前可用性（只读缓存，不触发反射）。 */
    fun availability(): HudChannelAvailability = synchronized(lock) { state }

    /**
     * 主动探测整条链。
     *
     * - 已拿到 manager ⇒ 直接返回 [HudChannelAvailability.Available]；
     * - 否则重试一次建链；失败返回 [HudChannelAvailability.Unavailable]。
     *
     * 不抛异常。诊断探针 [HudChannelProbe] 会逐步打日志；本方法只打结论。
     */
    fun probe(): HudChannelAvailability = synchronized(lock) {
        ensureManagerLocked()
        state
    }

    /** HUD 使能：`CB_VF_HUD_ActvReq(1)`。 */
    fun activate(): HudCallResult = request(HUD_ACTIVATION_ON)

    /** HUD 关闭：`CB_VF_HUD_ActvReq(0)`（HudBridge 没做的一步）。 */
    fun deactivate(): HudCallResult = request(HUD_ACTIVATION_OFF)

    private fun request(value: Int): HudCallResult = synchronized(lock) {
        ensureManagerLocked()
        val mgr = manager
            ?: return HudCallResult.Failed(
                value,
                (state as? HudChannelAvailability.Unavailable)?.reason
                    ?: "HUD 通道未建立（未知原因）",
            )

        val raw = try {
            access.request(mgr, value)
        } catch (throwable: Throwable) {
            val reason = "CB_VF_HUD_ActvReq($value) 调用失败: ${describeThrowable(throwable)}"
            manager = null
            state = HudChannelAvailability.Unavailable(reason)
            logger(reason)
            return HudCallResult.Failed(value, reason)
        }

        // returnText 保持厂商枚举原样（SUCCEED / FAILED / …），类型只进日志，
        // 避免 `apiSucceeded` 这类判断被展示格式污染。
        val text = raw?.toString() ?: "null"
        val type = raw?.javaClass?.name ?: "null"
        logger("CB_VF_HUD_ActvReq($value) 返回 $text (returnType=$type)")
        HudCallResult.Returned(value, raw, text)
    }

    private fun ensureManagerLocked(): Any? {
        manager?.let {
            state = HudChannelAvailability.Available
            return it
        }
        return try {
            val opened = access.open()
            manager = opened
            state = HudChannelAvailability.Available
            logger("HUD 通道可用：反射链已打通（VfhudManager=${opened.javaClass.name}）")
            opened
        } catch (throwable: Throwable) {
            val reason = when (throwable) {
                is HudChainException -> throwable.message ?: throwable.step.label
                else -> describeThrowable(throwable)
            }
            manager = null
            state = HudChannelAvailability.Unavailable(reason)
            logger("HUD 通道不可用：$reason")
            null
        }
    }

    companion object {
        /** 统一 logcat TAG（与 HudChannelProbe 共用）。 */
        const val TAG = "xcertplay-hud"

        /** 车控 Binder 服务名（dex 核实：`ECarXCar.SERVICE_NAME`）。 */
        const val CAR_SERVICE = "ecarxcar_service"

        /** 公共属性管理器名（dex 核实：`ECarXCar.PA_SERVICE`）。 */
        const val PA_SERVICE = "car_publicattribute"

        /** HUD 使能属性 ID（dex 核实：`ManagerId_cbvfhudactvreq = 32928`）。 */
        const val HUD_ACTV_PROPERTY = 32928

        const val HUD_ACTIVATION_ON = 1
        const val HUD_ACTIVATION_OFF = 0
    }
}

/**
 * 真实反射实现的低层步骤。
 *
 * 每一步单独成函数，[HudChannelProbe] 才能逐步记录「服务 / SetManager /
 * VfhudManager 到底哪一步没拿到」，而不是只看到一个大而化之的失败。
 * 所有方法在失败时抛异常，由上层统一兜。
 */
internal object EcarxVfhudReflection {

    fun serviceBinder(): IBinder {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        return getService.invoke(null, EcarxVfhudChannel.CAR_SERVICE) as? IBinder
            ?: throw IllegalStateException(
                "ServiceManager.getService(\"${EcarxVfhudChannel.CAR_SERVICE}\") 返回 null",
            )
    }

    fun asInterface(binder: IBinder): Any {
        val stub = Class.forName("ecarx.car.IECarXCar\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("IECarXCar\$Stub.asInterface 返回 null")
    }

    fun createCar(context: Context, service: Any): Any {
        val carClass = Class.forName("ecarx.car.ECarXCar")
        val serviceInterface = Class.forName("ecarx.car.IECarXCar")
        return carClass
            .getMethod("createCar", Context::class.java, serviceInterface)
            .invoke(null, context, service)
            ?: throw IllegalStateException("ECarXCar.createCar 返回 null")
    }

    fun getSetManager(car: Any): Any {
        // PA_SERVICE 以 ROM 里的字段为准；取不到才回落到本工程常量。
        val paService = runCatching {
            car.javaClass.getField("PA_SERVICE").get(null) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: EcarxVfhudChannel.PA_SERVICE

        return car.javaClass
            .getMethod("getCarManager", String::class.java)
            .invoke(car, paService)
            ?: throw IllegalStateException("ECarXCar.getCarManager(\"$paService\") 返回 null")
    }

    fun getVfhudManager(setManager: Any): Any =
        setManager.javaClass
            .getMethod("getECarXCarVfhudManager")
            .invoke(setManager)
            ?: throw IllegalStateException("getECarXCarVfhudManager 返回 null")

    fun callActvReq(manager: Any, value: Int): Any? =
        manager.javaClass
            .getMethod("CB_VF_HUD_ActvReq", Int::class.javaPrimitiveType)
            .invoke(manager, value)
}

/**
 * [VfhudAccess] 的反射实现：逐步骤记录成功/失败。
 *
 * 日志一律进 `xcertplay-hud`（调用方注入的 logger），不吞步骤信息。
 */
internal class ReflectiveVfhudAccess(
    private val context: Context,
    private val logger: (String) -> Unit,
) : VfhudAccess {

    override fun open(): Any {
        val binder = step(HudChainStep.SERVICE_MANAGER) {
            EcarxVfhudReflection.serviceBinder()
        }
        val carInterface = step(HudChainStep.CAR_INTERFACE) {
            EcarxVfhudReflection.asInterface(binder)
        }
        val car = step(HudChainStep.CREATE_CAR) {
            EcarxVfhudReflection.createCar(context, carInterface)
        }
        val setManager = step(HudChainStep.SET_MANAGER) {
            EcarxVfhudReflection.getSetManager(car)
        }
        return step(HudChainStep.VFHUD_MANAGER) {
            EcarxVfhudReflection.getVfhudManager(setManager)
        }
    }

    override fun request(manager: Any, value: Int): Any? =
        EcarxVfhudReflection.callActvReq(manager, value)

    private inline fun <T> step(step: HudChainStep, block: () -> T): T = try {
        block().also { value ->
            // 带上实际类名：getCarManager 这一步骤要能看出拿到的确实是
            // ECarXCarSetManager，VfhudManager 那一步同理。
            val type = value?.let { "（${it.javaClass.name}）" }.orEmpty()
            logger("${step.label} → OK$type")
        }
    } catch (throwable: Throwable) {
        val cause = unwrapInvocation(throwable)
        logger("${step.label} → 失败：${describeThrowable(cause)}")
        throw HudChainException(step, cause)
    }
}

/** 反射 `InvocationTargetException` 拆包，日志里显示厂商真实异常。 */
internal fun unwrapInvocation(throwable: Throwable): Throwable =
    (throwable as? InvocationTargetException)?.targetException ?: throwable

internal fun describeThrowable(throwable: Throwable): String {
    val cause = unwrapInvocation(throwable)
    val message = cause.message?.takeIf { it.isNotBlank() }
    return if (message == null) {
        cause.javaClass.name
    } else {
        "${cause.javaClass.name}: $message"
    }
}
