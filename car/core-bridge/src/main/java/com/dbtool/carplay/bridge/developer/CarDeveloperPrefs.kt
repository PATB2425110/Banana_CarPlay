package com.dbtool.carplay.bridge.developer

import android.content.Context

/**
 * 开发者模式的本地持久化。
 *
 * 刻意**不复用** [com.dbtool.carplay.bridge.SharedPreferencesCarSettingsStore]：
 * - 那个 store 管的是"会话配置"，参与"恢复出厂设置"；开发者模式是**本机调试开关**，
 *   不该被恢复出厂清掉——现场排障时清一次设置就要重新连点 7 次版本号，很烦。
 * - 设备号是后台认车的主键，必须比"设置"活得更久：后台一旦按它下发过诊断任务，
 *   换了号就等于换了一台车。
 */
internal class CarDeveloperPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 解锁

    /** 是否已解锁开发者模式。 */
    var unlocked: Boolean
        get() = prefs.getBoolean(KEY_UNLOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_UNLOCKED, value).apply()
        }

    // -------------------------------------------------------------- 设备短码

    /**
     * 设备短码：**一次生成、终身不变**。
     *
     * 不做"锚点变了就重算"。参考实现（DBTool）踩过这个坑：早期按"锚点优先级"重算，
     * 等 VIN 后来能读到（root 打通后）就把号从 androidId 版本换成 vin 版本，
     * 后台/授权侧立刻对不上，现场表现是"重启后设备号又变了"。
     * 这里只认缓存：形态合法就永远沿用，只有缺失或明显异常才重新生成。
     */
    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)?.takeIf(::isPlausibleDeviceId)
        set(value) {
            prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        }

    // ---------------------------------------------------------- 最近上传结果

    var lastLogId: String
        get() = prefs.getString(KEY_LAST_LOG_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_LOG_ID, value).apply()
        }

    var lastFileName: String
        get() = prefs.getString(KEY_LAST_FILE_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_FILE_NAME, value).apply()
        }

    /** 最近一次成功上传的时间戳（毫秒），0 = 从未成功过。 */
    var lastUploadAtMillis: Long
        get() = prefs.getLong(KEY_LAST_UPLOAD_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_UPLOAD_AT, value).apply()
        }

    // ---------------------------------------------------------- 系统信息缓存

    /** 系统信息缓存值。 */
    data class SystemInfo(
        val androidVersion: String,
        val systemVersion: String,
        val deviceModel: String,
    )

    /**
     * 读系统信息缓存。命中条件：**逻辑标记一致 + 未超期**。
     *
     * ⚠️ 为什么一定要带标记：参考实现里客户端把 `ro.product.flymeVersion`（真机为 1.9）
     * 当成系统版本上报，后来改成读车机系统包版本名（真机为 2.5.x），
     * 但 SharedPreferences 里**残留着旧逻辑写的值**，于是诊断包长期显示错误的 1.9。
     * 结论：读取逻辑一改就必须换标记，旧缓存自动失效——而不是指望"下次会重算"。
     */
    fun readSystemInfo(nowMillis: Long): SystemInfo? {
        if (prefs.getString(KEY_SYSINFO_MARKER, null) != SYSTEM_INFO_MARKER) return null
        val cachedAt = prefs.getLong(KEY_SYSINFO_AT, 0L)
        if (cachedAt <= 0L) return null
        if (nowMillis - cachedAt > SYSTEM_INFO_TTL_MILLIS) return null
        return SystemInfo(
            androidVersion = prefs.getString(KEY_SYSINFO_ANDROID, "").orEmpty(),
            systemVersion = prefs.getString(KEY_SYSINFO_SYSTEM, "").orEmpty(),
            deviceModel = prefs.getString(KEY_SYSINFO_MODEL, "").orEmpty(),
        )
    }

    fun writeSystemInfo(info: SystemInfo, nowMillis: Long) {
        prefs.edit()
            .putString(KEY_SYSINFO_MARKER, SYSTEM_INFO_MARKER)
            .putLong(KEY_SYSINFO_AT, nowMillis)
            .putString(KEY_SYSINFO_ANDROID, info.androidVersion)
            .putString(KEY_SYSINFO_SYSTEM, info.systemVersion)
            .putString(KEY_SYSINFO_MODEL, info.deviceModel)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "car_developer"

        const val KEY_UNLOCKED = "developer_unlocked"
        const val KEY_DEVICE_ID = "device_id"

        const val KEY_LAST_LOG_ID = "last_log_id"
        const val KEY_LAST_FILE_NAME = "last_file_name"
        const val KEY_LAST_UPLOAD_AT = "last_upload_at"

        const val KEY_SYSINFO_MARKER = "system_info_marker"
        const val KEY_SYSINFO_AT = "system_info_at"
        const val KEY_SYSINFO_ANDROID = "system_info_android"
        const val KEY_SYSINFO_SYSTEM = "system_info_system"
        const val KEY_SYSINFO_MODEL = "system_info_model"

        /**
         * 系统信息读取逻辑的版本标记。**改动读取逻辑时必须 +1**，
         * 否则老设备会一直用旧逻辑写下的缓存。
         */
        const val SYSTEM_INFO_MARKER = "getprop+flymepkg-v1"

        /** 12 小时。系统版本不会一天变好几次，但 OTA 之后不该一直报旧值。 */
        const val SYSTEM_INFO_TTL_MILLIS = 12L * 60 * 60 * 1000

        /** 形态校验：只允许 `[0-9a-zA-Z_-]`，长度 8..128。 */
        fun isPlausibleDeviceId(value: String): Boolean {
            if (value.length !in 8..128) return false
            if (value.equals("unknown", ignoreCase = true) || value.equals("null", ignoreCase = true)) {
                return false
            }
            return value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}
