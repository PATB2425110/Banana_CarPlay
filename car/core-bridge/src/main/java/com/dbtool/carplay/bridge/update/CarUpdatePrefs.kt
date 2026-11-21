package com.dbtool.carplay.bridge.update

import android.content.Context

/**
 * 在线更新的本地持久化。
 *
 * 刻意**不复用** [com.dbtool.carplay.bridge.SharedPreferencesCarSettingsStore]：
 * 那个 store 参与"恢复出厂设置"，而"上次检查时间""已下载的包"这些是
 * **升级链路自己的状态**——恢复出厂把"已下载待安装的 APK"的记录清掉，
 * 只会留下一份没人认领的安装包，没有任何好处。
 */
internal class CarUpdatePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 自动检查节流

    /**
     * 上次**发起**检查的时间戳（毫秒），0 = 从未查过。
     *
     * ⚠️ 记的是"发起"而不是"成功"：安装自我更新会把进程杀掉重建，
     * 一次"发出去还没回来就被杀"的检查如果不记账，每次重建都会再发一遍。
     * 实测踩过：装完 0.0.12 后短时间内连发了 4 次 `check_update`。
     * 代价是检查失败后 6 小时内不再自动重试——这可以接受（用户手动点
     * 「检查更新」不受节流限制）。
     */
    var lastCheckAtMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()
        }

    // -------------------------------------------------------------- 已忽略版本

    /**
     * 用户点了「稍后再说」的版本号。
     *
     * 只抑制**自动弹窗**：同一个版本不再每次启动都糊到脸上，
     * 但用户在「关于」页手动点「检查更新」时照常弹——那时他是主动要看的。
     */
    var dismissedVersionCode: Long
        get() = prefs.getLong(KEY_DISMISSED_VERSION_CODE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_DISMISSED_VERSION_CODE, value).apply()
        }

    // ------------------------------------------------------------ 待安装的包

    /**
     * 已下载完成的 APK 路径。空串 = 没有。
     *
     * 存路径而不是只存"下载过"这个事实：应用重启后用户还能直接点安装，
     * 不必重新下几十 MB。
     */
    var downloadedApkPath: String
        get() = prefs.getString(KEY_DOWNLOADED_PATH, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_PATH, value).apply()
        }

    /** 已下载 APK 对应的版本号。 */
    var downloadedVersionCode: Long
        get() = prefs.getLong(KEY_DOWNLOADED_VERSION_CODE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_DOWNLOADED_VERSION_CODE, value).apply()
        }

    /**
     * 已经交给系统安装器、但还没等到结果的版本号。
     *
     * ⚠️ 这是**上报"安装成功"的唯一手段**：无论走静默安装还是系统安装器，
     * 应用都会被系统杀掉重建，拿不到安装回调。所以把"我交出去了"记下来，
     * 下次启动时比对 `PackageManager` 读回的版本号——已经 ≥ 它，就说明装成功了。
     * 服务端的 `/api/app/update_status` 要的正是这个结论。
     */
    var pendingInstallVersionCode: Long
        get() = prefs.getLong(KEY_PENDING_INSTALL_CODE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_PENDING_INSTALL_CODE, value).apply()
        }

    // ------------------------------------------------------------ 后台静默更新

    /**
     * 用户是否开启了「后台自动更新」（「关于」页那颗开关）。
     *
     * ## 默认值：**`true`**（2026-10-13 车主口径改；PATCH-0115）
     *
     * 车主原话：「设置-关于-后台-自动更新默认开启」。改之前是 `false`，当时的理由是
     * 「这是唯一一个能让 App 把自己装死"的功能」——那个顾虑**没有消失**，只是被
     * 下面这条**三重保险**接住了：
     *
     * 1. **服务端还有一把锁**：`/api/app/check_update` 的 `auto_update` 必须也为真
     *    （后台「应用更新」页那颗开关）。发布方要能踩刹车，这是**真正**的那道闸；
     * 2. **熔断**：同一版本静默尝试失败 [SILENT_MAX_ATTEMPTS] 次 ⇒ 该版本**永久**不再
     *    自动装，只在「关于」页显示「自动更新失败，请手动安装」；
     * 3. **用户随时能关**：「关于」页那颗开关，关掉即写盘。
     *
     * ⚠️ 仍然要记住风险在哪：**推一个坏包**（装完起不来、用户连设置页都进不去）
     * 时，保险 2 需要"下一次启动能跑 `settlePendingInstall`"才推进计数；若坏包
     * 在启动早期就崩，计数可能推不动 —— 那时**只剩保险 1**。所以"服务端默认别开
     * `auto_update`、要灰度"这条纪律仍然成立（见 `REF-dbapi.md` 的 auto_update 一节）。
     *
     * ## 生效范围（**注意：对"从没动过这颗开关"的老安装也生效**）
     *
     * 读的是 `getBoolean(KEY, true)`：prefs 里**没有这个键**时返回默认值。因此
     * - 新装 ⇒ 开；
     * - 老安装、从没碰过这颗开关 ⇒ 也变成开（**这是"改默认值"的应有语义**）；
     * - 老安装、曾经**显式关掉**过 ⇒ 保持关（显式写入的 `false` 不会被默认值覆盖）。
     *
     * 不需要一次性迁移：本项**不在** `CarSessionSettings` 里，没有"出厂默认值"要回写，
     * 改 `getBoolean` 的默认参数就是全部（对照 `REF-rules.md` 里"改已有字段默认值必须
     * 配迁移"那条 —— 那条针对的是 `CarSessionSettings` 的字段）。
     *
     * ⚠️ 刻意不放进 [com.dbtool.carplay.bridge.SharedPreferencesCarSettingsStore]：
     * 那个 store 参与"恢复出厂设置"，而升级链路的状态不该被它清掉
     * （与本类类注释同一个理由）。
     */
    var silentUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_SILENT_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SILENT_ENABLED, value).apply()
        }

    /** [silentAttemptCount] 计的是哪个版本。0 = 还没记过。 */
    var silentAttemptVersionCode: Long
        get() = prefs.getLong(KEY_SILENT_ATTEMPT_CODE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SILENT_ATTEMPT_CODE, value).apply()
        }

    /** 同一版本**连续**静默尝试了几次（见 [nextSilentAttempt]）。 */
    var silentAttemptCount: Int
        get() = prefs.getInt(KEY_SILENT_ATTEMPT_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_SILENT_ATTEMPT_COUNT, value).apply()
        }

    /**
     * 已被熔断（永久关闭静默）的版本号。0 = 没有熔断。
     *
     * 命中后该版本**不再自动安装**，只在「关于」页显示"自动更新失败，请手动安装"。
     * 解除方式见 [silentBlocked] 的注释。
     */
    var silentBlockedVersionCode: Long
        get() = prefs.getLong(KEY_SILENT_BLOCKED_CODE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SILENT_BLOCKED_CODE, value).apply()
        }

    /**
     * 记一次"即将发起静默安装"，返回推进后的计数。
     *
     * ⚠️ **必须在真正发起安装之前调用**，与 [lastCheckAtMillis] 同一个理由：
     * 装自己会把进程杀掉，成功路径上的写盘代码**可能永远执行不到**——
     * 那次尝试就白记了，下次启动会以为是第一次，于是永远凑不满熔断阈值。
     *
     * @return 推进后的计数。⚠️ "还能不能再试"应该在调用本方法**之前**用
     *         [silentAttemptLimitReached] 判定 —— 那时读到的才是"已经试过几次"。
     */
    fun recordSilentAttempt(versionCode: Long): SilentAttempt {
        val attempt = nextSilentAttempt(
            previousVersionCode = silentAttemptVersionCode,
            previousCount = silentAttemptCount,
            versionCode = versionCode,
        )
        prefs.edit()
            .putLong(KEY_SILENT_ATTEMPT_CODE, attempt.versionCode)
            .putInt(KEY_SILENT_ATTEMPT_COUNT, attempt.count)
            .apply()
        return attempt
    }

    /**
     * 解除熔断与计数（用户手动安装、或用户重新打开静默开关时调用）。
     *
     * 手动路径不受熔断限制 —— 用户明确点了「立即安装」，那是在**他的**判断下
     * 装这个包，熔断拦的是"无人值守的自动重试"，不是他本人。
     */
    fun clearSilentBreaker() {
        prefs.edit()
            .putLong(KEY_SILENT_ATTEMPT_CODE, 0L)
            .putInt(KEY_SILENT_ATTEMPT_COUNT, 0)
            .putLong(KEY_SILENT_BLOCKED_CODE, 0L)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "car_update"

        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"
        const val KEY_DOWNLOADED_PATH = "downloaded_apk_path"
        const val KEY_DOWNLOADED_VERSION_CODE = "downloaded_version_code"
        const val KEY_PENDING_INSTALL_CODE = "pending_install_version_code"

        const val KEY_SILENT_ENABLED = "silent_update_enabled"
        const val KEY_SILENT_ATTEMPT_CODE = "silent_attempt_version_code"
        const val KEY_SILENT_ATTEMPT_COUNT = "silent_attempt_count"
        const val KEY_SILENT_BLOCKED_CODE = "silent_blocked_version_code"
    }
}
