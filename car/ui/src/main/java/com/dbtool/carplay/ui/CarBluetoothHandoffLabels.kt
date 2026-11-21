package com.dbtool.carplay.ui

import com.dbtool.carplay.api.settings.CarBluetoothHandoffPolicy

/**
 * 无线握手后蓝牙处理力度的中文短标签。
 *
 * 与 [CarMfiTarget.displayLabel] 同一理由集中在一处：设置页的选项与摘要行都要用，
 * 散着写必然漂移。标签**刻意做短**——分段控件的每个选项等宽均分整行，
 * 长标签在车机上会被挤成两行或截断。
 *
 * 穷尽式 `when`（不写 `else`）：契约层加档位时这里会编译失败，强制显式决策。
 */
internal fun CarBluetoothHandoffPolicy.displayLabel(): String = when (this) {
    CarBluetoothHandoffPolicy.OFF -> "不处理"
    CarBluetoothHandoffPolicy.DISCONNECT_LINK -> "断开手机"
    CarBluetoothHandoffPolicy.DISABLE_ADAPTER -> "关闭蓝牙"
}
