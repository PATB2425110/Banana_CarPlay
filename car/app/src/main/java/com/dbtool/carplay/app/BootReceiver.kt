package com.dbtool.carplay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dbtool.carplay.bridge.SharedPreferencesCarSettingsStore

/**
 * 开机自启 + 装完预热。
 *
 * 与上游 `BootReceiver` 行为一致（同样是读设置开关、同样是 startActivity），
 * 但**不复用上游那个类**：它硬编码启动上游的 `CarPlayHostActivity`，
 * 而本产品只有 `MainActivity` 一个宿主。复用会导致开机后弹出旧的 UI。
 *
 * 启动失败时只记日志不崩溃——车机上 BOOT_COMPLETED 的接收方很多，
 * 因为自启失败把进程搞崩没有意义。
 *
 * `ACTION_MY_PACKAGE_REPLACED` 分支只做一件事：调度一次性 dex 预热任务，
 * 不启动界面、不碰自启开关（见 [DexWarmup]）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> handleBootCompleted(context)

            // 【新增 2026-10-18】覆盖安装/升级后的一次性预热：只是调度一个
            // JobService 把进程撑住，让加固壳的 dex2oat 跑完（见 DexWarmup）。
            // 与下面的开机自启分支完全独立，不改 BOOT_COMPLETED 的任何行为。
            Intent.ACTION_MY_PACKAGE_REPLACED -> DexWarmup.scheduleAfterUpdate(context)
        }
    }

    /** `BOOT_COMPLETED` 的现有逻辑，原样搬迁，未改一行。 */
    private fun handleBootCompleted(context: Context) {
        val settings = runCatching {
            SharedPreferencesCarSettingsStore(context).load()
        }.getOrNull()

        // 【新增 2026-10-15】无条件留一行证据 + 现场端口快照。
        //
        // 原来本类**只在失败时**打日志（见下面两处 onFailure），于是"这台车到底有没有
        // 把 BOOT_COMPLETED 派给我们"在日志里**零命中**。而"不依赖 Init 也能跑通"
        // 这条路线成立与否，**全看这一行有没有出现**。
        //
        // 端口用**只读快照**而不是任何建立动作：BOOT_COMPLETED 时刻我们只想**观测**，
        // 不想顺手改 adbd（那是 setupHotspotAndAdbNow() 的事）。
        val services = CarPlayApplication.servicesOrNull()
        Log.i(
            TAG,
            "收到 BOOT_COMPLETED：autoStartOnBoot=${settings?.autoStartOnBoot} " +
                "keepHotspotAlive=${settings?.keepHotspotAlive} " +
                "enableBuiltInAdb=${settings?.enableBuiltInAdb} " +
                "adbPort=${runCatching { services?.adbPortSnapshot() }.getOrNull() ?: "（服务未就绪）"}",
        )

        // 1) Activity 自启：独立的开关，不影响热点恢复。
        val launchActivity = settings?.autoStartOnBoot ?: false
        if (launchActivity) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            runCatching { context.startActivity(launch) }
                .onFailure { Log.w(TAG, "开机自启失败", it) }
        }

        // 按需方案不在开机阶段建立 ADB 通道或自建热点。
        // ADB 初始化只响应用户的显式操作；自建热点仅随 ADB_HOSTAPD 会话启动。
    }

    private companion object {
        const val TAG = "carplay-boot"
    }
}
