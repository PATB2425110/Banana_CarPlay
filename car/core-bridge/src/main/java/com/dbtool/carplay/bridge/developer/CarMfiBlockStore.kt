package com.dbtool.carplay.bridge.developer

import android.content.Context

/**
 * 「本机是否被管理员拉黑」的本地落盘。
 *
 * ## 为什么必须落盘，而不是只放在内存
 *
 * 用户选定的语义是 **sticky（断网也拦）**：
 *
 * - 只要收到过一次「已拉黑」，就写进磁盘，**之后断网也照样拦**；
 * - 只有服务端**明确回 `blocked=false`** 才清除。
 *
 * 只放内存的话，"拉黑 → 车机重启 → 断网 → 又能连上"——拦截形同虚设。
 * 反过来，"连不上服务端就当没拉黑"也不行：车机常年没网，那等于永不生效。
 *
 * ## 为什么抽成接口
 *
 * 判定策略（[resolveBlockStatus]）是本功能的**全部风险所在**，必须能进单测。
 * 而本仓的单测是**纯 JVM**（`:car:core-bridge` 只有 `testImplementation(libs.junit)`，
 * 没有 Robolectric），`Context` / `SharedPreferences` 在那里跑不起来。
 * 因此把"存储"收成一个三方法接口，单测给一个内存实现即可。
 *
 * ## 线程
 *
 * 实现一律**直读直写 prefs**、不做内存缓存：本类在
 * `CarVehicleProfileReporter` 与 `CarPlaySessionBridge` 里**各持一份**，
 * 而它们跑在不同线程上（上报在 `dbplay-profile`、建栈在 `xc-ext-session`）。
 * 带内存缓存的两份实例会各自记一份旧值，表现为"上报说没拉黑、连接说拉黑了"。
 * `SharedPreferences` 自身线程安全，且 `apply()` 是异步落盘、不阻塞调用线程。
 *
 * ⚠️ 接口本身是 `public`（不是 `internal`）：它经由 [CarDeviceStatusSync] 的构造函数
 * 出现在 `CarPlaySessionBridge` 的**公开**签名上，而 `internal` 类型不能那样暴露。
 * 与 `CarVehicleProfileReporter` 是同一个理由。
 */
interface CarMfiBlockStore {

    /** 本机当前是否处于「已拉黑」状态。 */
    val isBlocked: Boolean

    /**
     * 拉黑原因（面向用户的中文）。
     *
     * 未拉黑时为空串。**已拉黑但没记到原因时也可能为空串**（老数据），
     * 因此取用方必须自己兜底文案，见 `CarDeveloperEndpoints.BLOCKED_REASON_FALLBACK`。
     */
    val reason: String

    /** 记下「已拉黑」。同一次拉黑重复调用是幂等的。 */
    fun cache(reason: String)

    /** 清除标记（只在服务端**明确**回 `blocked=false` 时调用）。 */
    fun clear()
}

/**
 * [CarMfiBlockStore] 的落盘实现。
 *
 * 与 `CarProfilePrefs` / `CarDeveloperPrefs` 同款：单开一个 prefs 文件，
 * 语义独立（"这台车被停用了"与"上次上报时间"是两件事，混在一起迟早互相踩）。
 *
 * ⚠️ **不参与"恢复出厂设置"之外的任何清理**：清应用数据会连它一起清掉，
 * 但那正是我们想要的降级——deviceId 也会换新，服务端按 VIN 照样认得出来
 * （VIN 不会变，见 `CarVinReader`），下次联网一次即恢复拦截。
 */
class CarMfiBlockPrefs(context: Context) : CarMfiBlockStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val isBlocked: Boolean
        get() = prefs.getBoolean(KEY_BLOCKED, false)

    override val reason: String
        get() = prefs.getString(KEY_REASON, "").orEmpty()

    override fun cache(reason: String) {
        prefs.edit()
            .putBoolean(KEY_BLOCKED, true)
            .putString(KEY_REASON, reason)
            .apply()
    }

    override fun clear() {
        // 连原因一起清：留着会让下一次拉黑（若服务端没给 reason）显示出上一次的旧文案。
        prefs.edit()
            .remove(KEY_BLOCKED)
            .remove(KEY_REASON)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "car_mfi_block"
        const val KEY_BLOCKED = "blocked"
        const val KEY_REASON = "blocked_reason"
    }
}
