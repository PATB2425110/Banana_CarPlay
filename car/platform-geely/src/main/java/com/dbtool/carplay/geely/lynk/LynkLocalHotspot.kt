package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Lynk 车机**本地热点**支持判定。
 *
 * ## 关键：编译期按系统版本开关
 *
 * 上游 DiPlay(Lynk) 的实现：
 *
 * ```kotlin
 * fun supported(context) = Build.VERSION.SDK_INT == 28 &&
 *     context.resources.getBoolean(R.bool.config_lynk_local_hotspot)
 * ```
 *
 * - **只在 Android 9（sdk28）为真**，其他系统版本一律 false —— 吉利 CS11 车机正是 sdk28。
 * - 判断依据是**编译期布尔资源** `config_lynk_local_hotspot`，**不是运行时探测**。
 *   排查「为什么这个方案不启用」先看这条布尔 + 系统版本，而不是去翻热点代码。
 *
 * ## 对 DBPlay 的意义
 *
 * DBPlay 的「自建热点」方案在**非 sdk28 设备上根本不该尝试**（手机/新系统上都走复用/局域网）。
 * 这条判定可直接用来做**降级闸门**：不支持就别进自建分支，直接走复用/局域网。
 *
 * ⚠️ 本应用如果不带 `config_lynk_local_hotspot` 资源，[supported] 恒为 false（安全默认）。
 */
object LynkLocalHotspot {

    private const val TAG = "DBPlay-LynkHotspot"

    /** 吉利资源里那条编译期布尔的资源名。 */
    private const val BOOL_RES_NAME = "config_lynk_local_hotspot"

    /** 只有这个 SDK 版本上 Lynk 本地热点才有效。 */
    private const val LYNk_SDK = 28

    /**
     * 本机是否支持 Lynk 本地热点方案。
     */
    fun supported(context: Context): Boolean {
        val sdkOk = Build.VERSION.SDK_INT == LYNk_SDK
        val resOk = runCatching {
            val id = context.resources.getIdentifier(
                BOOL_RES_NAME, "bool", context.packageName
            )
            id != 0 && context.resources.getBoolean(id)
        }.getOrDefault(false)
        val ok = sdkOk && resOk
        Log.i(TAG, "Lynk localHotspot supported=$ok (sdk=${Build.VERSION.SDK_INT}, sdkOk=$sdkOk, resOk=$resOk)")
        return ok
    }
}
