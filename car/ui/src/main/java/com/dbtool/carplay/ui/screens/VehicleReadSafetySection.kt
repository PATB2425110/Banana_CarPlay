package com.dbtool.carplay.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.dbtool.carplay.api.CarPlatformRegistry
import com.dbtool.carplay.api.vehicle.CarVehicleReadSafety
import com.dbtool.carplay.ui.components.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate

/** Independent safety preference: normal settings autosave/import cannot undo an automatic trip. */
@Composable
internal fun VehicleReadSafetySection(onReconnect: () -> Unit) {
    val context = LocalContext.current
    val safety = remember(context) { CarVehicleReadSafety.forProcess(context) }
    var state by remember(safety) { mutableStateOf(safety.state()) }
    var confirmEnable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val supported = remember { CarPlatformRegistry.currentOrNull()?.vehicleStatus != null }
    LaunchedEffect(safety) {
        // Worker callbacks only signal invalidation; reread on the UI coroutine so a delayed old
        // notification cannot restore an enabled switch or erase the latest protection reason.
        callbackFlow<Unit> {
            val observer = safety.observe { trySend(Unit) }
            awaitClose { observer.close() }
        }.conflate().collect { state = safety.state() }
    }
    SectionHeader(
        title = "车辆状态读取",
        subtitle = "默认关闭。开启后重连CarPlay生效；关闭立即停止读取，不断开导航或音乐。",
    )
    CarCard {
        CarSettingSwitch(
            title = "读取车辆状态",
            checked = state.enabled,
            enabled = supported,
            onCheckedChange = { enabled ->
                if (enabled) confirmEnable = true
                else if (!safety.setEnabled(false)) message = "关闭设置未能落盘；本次读取已保护性停止"
            },
            description = if (supported) {
                "只读燃油续航、车外温度、低油警告和车身昼夜；仍受原车权限限制。"
            } else "当前平台未提供车辆读取源；平板不会读取或伪造车况。",
        )
        state.disabledReason?.let {
            CarRowDivider()
            CarSettingInfo(title = "保护状态", value = "已自动关闭", description = it)
        }
        message?.let {
            CarRowDivider()
            CarSettingInfo(title = "提示", value = it)
        }
        if (state.enabled) {
            CarRowDivider()
            CarSettingAction(
                title = "重新连接以启用读取",
                value = null,
                onClick = onReconnect,
                description = "由你手动重连；未收到iPhone车辆状态订阅前不访问厂商读取接口。",
            )
        }
    }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("开启车辆状态读取？") },
            text = {
                Text("厂商SDK尚未实车验收，不能保证绝不闪退。读取期间异常退出后，下次启动会先关闭读取；" +
                    "系统杀进程或断电也可能触发保护。关闭后不会自动重新开启。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnable = false
                    message = if (safety.setEnabled(true)) null
                    else "未能开启；请等待旧读取退出，并确认保护设置可以保存"
                }) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text("保持关闭") }
            },
        )
    }
}
