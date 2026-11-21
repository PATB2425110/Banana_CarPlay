package com.dbtool.carplay.android

import android.util.Log
import com.dbtool.carplay.api.diagnostics.CarDiagnosticsAdapter

/**
 * 默认诊断适配：落到 `logcat`，标签统一加 `XCExt.` 前缀便于现场过滤
 * （`adb logcat -s XCExt.*`）。
 *
 * 车机适配点：需要写车机日志服务、串口或文件导出时继承覆写。
 * 实现必须非阻塞——`logcat` 写入本身足够快，换成 IPC 实现时要注意。
 *
 * ⚠️ **这里刻意没有"开关"。** 曾经有一个 `enabled` 字段 + `setEnabled()`，
 * 注释写着"由设置页的 Debug logs 开关驱动"，但实际全仓没有任何地方调用它，
 * `isEnabled()` 也没有任何读者——而 `log()` 本来就无条件写 logcat。
 * 也就是说那个开关从来没生效过，只是让代码看起来"有开关"。
 * 设置页那个开关的真实语义是**画面上的日志浮层**（见 `CarPlayScreen` 的
 * `showDebugOverlay`），与本适配器无关，故把这段误导性的死代码删掉。
 * 将来真接了可关闭的外部通道（车机日志服务等）时，再在**那个实现**里
 * 加 `setEnabled` 并覆写 [isEnabled]。
 */
open class AndroidCarDiagnosticsAdapter : CarDiagnosticsAdapter {

    override fun log(tag: String, message: String) {
        Log.i(prefix(tag), message)
    }

    override fun warn(tag: String, message: String) {
        Log.w(prefix(tag), message)
    }

    override fun error(tag: String, message: String, cause: Throwable?) {
        Log.e(prefix(tag), message, cause)
    }

    /** `Log` 的 tag 上限是 23 字符，超长会被系统截断，这里主动收敛。 */
    private fun prefix(tag: String): String {
        val trimmed = TAG_PREFIX + tag
        return if (trimmed.length <= LOG_TAG_LIMIT) trimmed else trimmed.substring(0, LOG_TAG_LIMIT)
    }

    companion object {
        const val TAG_PREFIX = "XCExt."
        private const val LOG_TAG_LIMIT = 23
    }
}
