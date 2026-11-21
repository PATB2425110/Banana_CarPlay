package com.dbtool.carplay.api.power

import android.content.Context
import android.content.SharedPreferences

/** 「熄火自动暂停」开关在设置文件里的键名。 */
const val CAR_POWER_PAUSE_KEY: String = "power_pause_enabled"

/** 开关默认值：开启。 */
const val CAR_POWER_PAUSE_DEFAULT_ENABLED: Boolean = true

/**
 * 设置文件共用的名字（与 `SharedPreferencesCarSettingsStore` 保持一致）。
 *
 * 开关不进 `CarSessionSettings`：它不参与与 iPhone 的握手，只是本地的一条
 * 运行时闸门，改完应当**立即生效**。做成独立的读写点，避免为它引入一次
 * "保存设置 → 重连"的往返。
 */
const val CAR_POWER_PAUSE_PREFS_NAME: String = "car_ui_settings"

/**
 * 「熄火自动暂停」开关的读写与变化订阅。
 *
 * 设置页与 `:car:core-bridge` 的接线共用同一个键，保证"界面上看到的值"
 * 与"实际生效的值"永远是同一份。
 */
class CarPowerPauseSetting(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(CAR_POWER_PAUSE_PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取开关；文件里没有该键时返回默认值（开）。 */
    fun isEnabled(): Boolean =
        CAR_POWER_PAUSE_DEFAULT_ENABLED // Product keeps this hidden feature enabled, including old false preferences.

    /** 写入开关。立即返回，落盘由 SharedPreferences 异步完成。 */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(CAR_POWER_PAUSE_KEY, enabled).apply()
    }

    /**
     * 订阅开关变化。
     *
     * @return 取消订阅句柄；重复调用 `close()` 无害。
     */
    fun observe(onChanged: (Boolean) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CAR_POWER_PAUSE_KEY) onChanged(isEnabled())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
