package com.dbtool.carplay.geely.lynk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Lynk 车机**开机自启**：借「已配对手机开机后回连蓝牙」这个事件拉起 CarPlay。
 *
 * ## 机制（从 DiPlay(Lynk) 反编译提取）
 *
 * - 读 `Settings.Global.getInt(cr, "boot_count")`（系统每次开机 +1）。
 * - 与 SharedPreferences 里上次记录的 `last_boot_count` 比对。
 * - 触发条件（**全部满足**）：
 *   1. 开机自启开关开（`AirPlayPersistence.loadAutoStartOnBoot`）
 *   2. 连上来的 `BluetoothDevice.address` 与已存 `phoneAddress` 相等
 *   3. `boot_count` 与上次不同
 *   → `startActivity(CarPlayHostActivity)` 拉起 CarPlay。
 *
 * ## 含义
 *
 * **开机自启不是靠开机广播自己拉**，而是**借"手机开机后重新连上蓝牙"这个事件**
 * 去拉起。所以车机不开机自启热点 / 手机不自动回连，这条链就不触发。
 *
 * ## 与 DBPlay 热点的关系
 *
 * 这与「DBPlay 热点三模式」是正交的：DBPlay 车机借力车机自带热点做自启，
 * 这里给出的是**另一条可用的自启触发源**（蓝牙回连）。两者可以并存。
 *
 * ⚠️ 未实机验证。
 */
class LynkBluetoothStartup(
    private val context: Context,
    private val prefsName: String = "dbplay_lynk_startup",
) {

    private companion object {
        const val TAG = "DBPlay-LynkStartup"
        const val KEY_LAST_BOOT_COUNT = "last_boot_count"
    }

    /** 系统开机计数。取不到返回 -1。 */
    fun bootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, "boot_count")
    }.getOrDefault(-1)

    /**
     * 判定「是否该由本次蓝牙回连拉起 CarPlay」。
     *
     * @param device 刚连上的蓝牙设备
     * @param savedPhoneAddress 已记住的手机蓝牙地址
     * @param autoStartEnabled 开机自启开关
     * @return true = 应拉起；调用方拉起后须调 [markBootHandled]
     */
    fun shouldLaunch(
        device: BluetoothDevice?,
        savedPhoneAddress: String?,
        autoStartEnabled: Boolean,
    ): Boolean {
        if (!autoStartEnabled) return false
        val address = device?.address ?: return false
        if (savedPhoneAddress.isNullOrBlank() || !address.equals(savedPhoneAddress, true)) {
            return false
        }
        val current = bootCount()
        if (current < 0) {
            Log.w(TAG, "读不到 boot_count，不触发")
            return false
        }
        val last = prefs().getInt(KEY_LAST_BOOT_COUNT, -1)
        val ok = current != last
        Log.i(TAG, "蓝牙回连判定 ok=$ok bootCount=$current last=$last addr=$address")
        return ok
    }

    /** 记录本次已处理的开机计数，避免重复拉起。 */
    fun markBootHandled() {
        prefs().edit().putInt(KEY_LAST_BOOT_COUNT, bootCount()).apply()
    }

    private fun prefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
}
