package com.dbtool.carplay.bridge.bluetooth

import android.content.Context

/**
 * 「蓝牙适配器是我们关掉的、还没恢复」这块标记的落盘（PATCH-0095，2026-10-11）。
 *
 * ## 为什么必须落盘
 *
 * [CarBluetoothHandoffPolicy.DISABLE_ADAPTER] 档会关掉**整个**蓝牙适配器，
 * 会话结束时再恢复。但恢复动作挂在**会话事件**上（`onSessionEnded`），
 * 而有一条路径根本不经过会话事件：**进程被杀**。
 *
 * 本工程里这不是罕见情况：
 * - 后台静默更新就是"装自己"，安装会杀掉进程重建（见 `CarUpdateManager`）；
 * - 还有 OOM、用户强停、车机重启。
 *
 * 一旦在那时留下"蓝牙关着"，后果不只是 CarPlay 连不上 ——
 * **车上的车钥匙、胎压、耳机全都断着**，而用户不知道该去哪儿开回来。
 * 所以把"我们关过它"写进磁盘，下次进程启动时补一次恢复
 * （[CarBluetoothHandoffReleaser.restoreIfLeftDisabled]）。
 *
 * ## 为什么不塞进 `CarSessionSettings` 的 store
 *
 * 那个 store 参与"恢复出厂设置"，而这是**运行时状态**、不是用户设置 ——
 * 与 `CarUpdatePrefs.silentUpdateEnabled` 同一个理由。恢复出厂把
 * "蓝牙可能还关着"这条记录清掉，只会留下一个没人认领的关闭状态。
 */
internal class CarBluetoothHandoffPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 适配器是否**由我们**关掉且尚未恢复。
     *
     * ⚠️ 只记录"我们关的"：用户自己在系统设置里关掉蓝牙**不会**写这个标记，
     * 因此启动时也不会被我们擅自打开（那会变成"App 偷偷改了用户的设置"）。
     */
    var adapterDisabledByUs: Boolean
        get() = prefs.getBoolean(KEY_ADAPTER_DISABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ADAPTER_DISABLED, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "car_bt_handoff"

        const val KEY_ADAPTER_DISABLED = "adapter_disabled_by_us"
    }
}
