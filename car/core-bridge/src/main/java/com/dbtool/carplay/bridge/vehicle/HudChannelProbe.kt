package com.dbtool.carplay.bridge.vehicle

import android.content.Context
import android.util.Log

/**
 * HUD 通道探针结果。
 *
 * [available] 只代表「反射链打通」，不代表 HUD 真的亮了；
 * 后者请结合 `CB_VF_HUD_ActvReq(1)` 的返回值与实车肉眼确认。
 */
data class HudProbeResult(
    val available: Boolean,
    val failedStep: String? = null,
    val failure: String? = null,
    val activationReturn: String? = null,
    val deactivationReturn: String? = null,
) {
    fun summary(): String = buildString {
        append("HUD 通道探针：")
        if (available) {
            append("链路可用")
            append("，ActvReq(1)=").append(activationReturn ?: "未调用")
            append("，ActvReq(0)=").append(deactivationReturn ?: "未调用")
        } else {
            append("不可用")
            failedStep?.let { append("，卡在 ").append(it) }
            failure?.let { append("，原因 ").append(it) }
        }
    }
}

/**
 * 一条命令看清 HUD 反射链卡在哪一步。
 *
 * 依次尝试并打印：
 * 1. `ServiceManager.getService("ecarxcar_service")` 能否拿到 Binder；
 * 2. `IECarXCar$Stub.asInterface` 能否拿到车控接口；
 * 3. `ECarXCar.createCar` 能否成功；
 * 4. `ECarXCar.getCarManager("car_publicattribute")` 能否拿到 `ECarXCarSetManager`；
 * 5. `getECarXCarVfhudManager()` 能否拿到 `VfhudManager`；
 * 6. `CB_VF_HUD_ActvReq(1)` 返回什么。
 *
 * 第 6 步之后会**再发一次 0** 恢复关闭状态（诊断不应把 HUD 留在常亮）。
 *
 * 结果同时写 logcat（TAG 固定 [EcarxVfhudChannel.TAG] = `xcertplay-hud`）。
 *
 * ⚠️ 必须在后台线程调用（Binder + 反射）。
 */
class HudChannelProbe(
    private val logger: (String) -> Unit = { line -> Log.i(EcarxVfhudChannel.TAG, line) },
) {

    fun run(context: Context): HudProbeResult {
        logger("===== HUD 通道探针开始 =====")

        val access = ReflectiveVfhudAccess(context.applicationContext, logger)
        val manager = try {
            access.open()
        } catch (throwable: Throwable) {
            val cause = unwrapInvocation(throwable)
            val failedStep = (throwable as? HudChainException)?.step?.label
            val failure = describeThrowable(cause)
            logger("HUD 通道探针结论：不可用，卡在 ${failedStep ?: "未知步骤"}（$failure）")
            return HudProbeResult(
                available = false,
                failedStep = failedStep,
                failure = failure,
            )
        }

        logger("VfhudManager 已取得：${manager.javaClass.name}")

        val activation = call(access, manager, EcarxVfhudChannel.HUD_ACTIVATION_ON)
        logger("CB_VF_HUD_ActvReq(1) → $activation")

        // 诊断结束后恢复关闭：探针只回答“能不能调通”，不应把 HUD 留在常亮。
        val deactivation = call(access, manager, EcarxVfhudChannel.HUD_ACTIVATION_OFF)
        logger("CB_VF_HUD_ActvReq(0) → $deactivation（探针结束，恢复关闭）")

        return HudProbeResult(
            available = true,
            activationReturn = activation,
            deactivationReturn = deactivation,
        )
    }

    private fun call(access: VfhudAccess, manager: Any, value: Int): String =
        try {
            val raw = access.request(manager, value)
            raw?.let { "${it.toString()} (${it.javaClass.name})" } ?: "null"
        } catch (throwable: Throwable) {
            "异常：${describeThrowable(throwable)}"
        }
}
