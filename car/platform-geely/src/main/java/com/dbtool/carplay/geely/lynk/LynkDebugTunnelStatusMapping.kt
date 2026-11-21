package com.dbtool.carplay.geely.lynk

import com.dbtool.carplay.api.developer.CarDebugTunnelState
import com.dbtool.carplay.api.developer.CarDebugTunnelStatus

/** 把隧道自身状态映射成界面契约（`car:ui` 不许依赖 platform 层，所以在这一层翻译）。 */
fun LynkDebugTunnel.TunnelStatus.toUiStatus(): CarDebugTunnelStatus = CarDebugTunnelStatus(
    state = when (state) {
        LynkDebugTunnel.State.IDLE -> CarDebugTunnelState.IDLE
        LynkDebugTunnel.State.CONNECTING -> CarDebugTunnelState.CONNECTING
        LynkDebugTunnel.State.CONNECTED -> CarDebugTunnelState.CONNECTED
        LynkDebugTunnel.State.RETRYING -> CarDebugTunnelState.RETRYING
    },
    lastError = lastError,
    lastConnectedAtMs = lastConnectedAtMs,
    reconnectCount = reconnectCount,
)
