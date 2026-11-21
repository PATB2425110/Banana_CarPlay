package com.dbtool.carplay.app

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.dbtool.carplay.bridge.developer.CarMfiBlockPrefs
import com.dbtool.carplay.bridge.developer.CarMfiBlockStore
import com.dbtool.carplay.ui.screens.maskVinForDisplay

/**
 * 封禁设备的总闸门：判定与「拉起封禁页」的动作放在一起，
 * 但判定部分不碰 Activity，因此可以直接用纯 JVM 单测钉住。
 *
 * 失败方向刻意是 **fail-closed**：读 SharedPreferences 都可能因为 ROM 的
 * 存储异常抛错，这时宁可显示封禁页，也不能让一台已知被封禁的车漏进主界面。
 * 真正需要放行时，服务端明确下发 `blocked=false` 会调用 `clear()`，下一次
 * 启动判定自然恢复。
 */
object BlockGate {
    private const val TAG = "DBPlay.BlockGate"

    /** 读标记失败时记录一行日志；单测可注入自己的收集器。 */
    internal fun shouldIntercept(
        store: CarMfiBlockStore,
        onReadError: (String, Throwable) -> Unit,
    ): Boolean = try {
        store.isBlocked
    } catch (error: Throwable) {
        onReadError("读取封禁标记失败，按已封禁处理", error)
        true
    }

    /**
     * 本机是否应当被拦在 UI 之外。
     *
     * 注意：这里只读落盘标记，不做网络请求。断网时沿用 sticky 缓存，
     * 因此车机重启、没有网络也照样会进入封禁页。
     */
    fun shouldIntercept(store: CarMfiBlockStore): Boolean =
        shouldIntercept(store) { message, error ->
            Log.e(TAG, message, error)
        }

    /**
     * 若已封禁，启动封禁页并结束当前 Activity，返回 true。
     *
     * 调用方必须在任何 `setContent` / 主题初始化 / 服务绑定之前调用，
     * 否则主界面会先渲染一帧，封禁页再盖上来。
     */
    fun interceptIfBlocked(
        activity: Activity,
        store: CarMfiBlockStore = CarMfiBlockPrefs(activity),
    ): Boolean {
        if (!shouldIntercept(store)) return false
        activity.startActivity(blockedIntent(activity))
        activity.finish()
        return true
    }

    /** 清掉主界面任务，只留下唯一一个封禁页。 */
    internal fun blockedIntent(activity: Activity): Intent =
        Intent(activity, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
}

/** 封禁页文案的纯函数部分，单测不需要 Android 运行时。 */
object BlockedPageCopy {
    const val TITLE = "设备已停用"
    const val BODY = "本车设备已被管理员停用，无法继续使用。"
    const val FOOTER = "如需恢复，请联系管理员并提供上面的设备编号。"

    /** 无原因时返回 null，页面就不渲染这一行。 */
    fun reasonLine(reason: String): String? =
        reason.trim().takeIf { it.isNotEmpty() }?.let { "原因：$it" }

    /** `ABCDEF123456` → `ABCD****3456`。 */
    fun maskDeviceId(deviceId: String): String {
        val text = deviceId.trim()
        if (text.isEmpty()) return ""
        if (text.length <= 8) return text.take(4) + "****"
        return text.take(4) + "****" + text.takeLast(4)
    }

    /**
     * 设备标识行：优先显示后台认车用的 deviceId；没有时退回已缓存的 VIN 掩码。
     */
    fun deviceLine(deviceId: String, vin: String?): String {
        val maskedId = maskDeviceId(deviceId)
        if (maskedId.isNotEmpty()) return "设备编号：$maskedId"
        val maskedVin = maskVinForDisplay(vin)
        return "设备编号：VIN $maskedVin"
    }
}
