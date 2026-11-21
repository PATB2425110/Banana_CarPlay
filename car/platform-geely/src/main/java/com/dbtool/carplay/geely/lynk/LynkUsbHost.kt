package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **iPhone USB 主机层**（有线 CarPlay 建链 + 驱动抢占恢复）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `transport/IphoneUsbHost.java`(59KB)、
 * `transport/NcmUsbBridge.java`(41KB)、`transport/TlsDuplexChannel.java`(42KB)、
 * `transport/UsbDriverRecovery.java`(23KB) 提取。
 *
 * ## 🔥 最有价值的发现：USB 驱动抢占
 *
 * Android/Linux 内核会**先用 `snd-usb-audio` 驱动抓走 iPhone 的 USB 音频接口**，
 * 导致应用层 `claimInterface` 失败 → 有线 CarPlay 连不上。
 * DiPlay 有专门的恢复流程：
 *
 * ```
 * USB handoff begin config=… interfaces=…
 * USB handoff iface=… driver=… errno=…     ← errno:61（驱动占用）
 * snd-usb-audio                            ← 罪魁祸首驱动名
 * unsupported-owner / no-known-driver / detach-failed / owner-changed
 * ```
 *
 * **DBPlay 若在有线连接失败时只报「打不开设备」，多半就是被 `snd-usb-audio` 占了接口。**
 *
 * ## iPhone USB 权限四态
 *
 * `Requested` / `Granted` / `AlreadyGranted` / `Denied`
 *
 * ## USB 侧常见失败（DiPlay 原文）
 *
 * - `Re-enumerated iPhone exposes no USBMUX CarPlay configuration`
 * - `CarPlay configuration exposes no USBMUX interface`
 * - `USBMUX interface exposes no bulk endpoint pair`
 * - `setConfiguration … reported failure; claiming anyway`（失败也硬认领）
 *
 * ## NCM 桥（有线走 IPv6 over USB）
 *
 * - 接口名 `ncm-status-in`
 * - `ncm bulk-out not ready; retaining bridge for retry`（不放弃，保留重试）
 * - NTB16 头校验：`NCM read buffer does not begin with an NTB16 header` /
 *   `Invalid NTB16 block length` / `Invalid NTB16 short-packet pad`
 *
 * ⚠️ 未实机验证。`UsbDriverRecovery` 涉及系统驱动 detach，需要 root 或系统签名权限。
 */
class LynkUsbHost {

    companion object {
        private const val TAG = "DBPlay-UsbHost"

        /** 抢占 iPhone USB 音频接口的系统驱动（DiPlay 原文）。 */
        const val KERNEL_AUDIO_DRIVER = "snd-usb-audio"

        /** 驱动占用错误码（DiPlay 原文出现的 errno）。 */
        const val ERRNO_DRIVER_BUSY = 61

        const val NCM_STATUS_IN = "ncm-status-in"

        // ---- USB 侧失败原因（原文）----
        const val ERR_NO_USBMUX_CARPLAY_CONFIG =
            "Re-enumerated iPhone exposes no USBMUX CarPlay configuration"
        const val ERR_NO_USBMUX_INTERFACE =
            "CarPlay configuration exposes no USBMUX interface"
        const val ERR_NO_BULK_PAIR = "USBMUX interface exposes no bulk endpoint pair"
        const val ERR_NO_USB_PERMISSION = "USB permission has not been granted"
        const val ERR_CANNOT_OPEN = "UsbManager could not open the iPhone"
        const val ERR_OPEN_RECOVERY = "USB driver handoff could not open recovery connection"
    }

    /** USB 权限状态（DiPlay 四态）。 */
    sealed class Permission {
        data class Requested(val deviceId: Int) : Permission()
        data class Granted(val deviceId: Int) : Permission()
        data class AlreadyGranted(val deviceId: Int) : Permission()
        data class Denied(val deviceId: Int) : Permission()
    }

    /** USB 驱动交接结果。 */
    sealed class Handoff {
        data class Begin(val config: Int, val interfaces: List<Int>) : Handoff()
        data class Iface(val id: Int, val driver: String, val errno: Int) : Handoff()
        data class DetachFailed(val id: Int, val errno: Int) : Handoff()
        data class OwnerChanged(val id: Int) : Handoff()
        data class NoKnownDriver(val id: Int) : Handoff()
    }

    /**
     * 判定接口是否被系统驱动占用（需要驱逐）。
     *
     * @param driverName 当前持有接口的内核驱动名
     */
    fun isDriverStolen(driverName: String?): Boolean {
        if (driverName == KERNEL_AUDIO_DRIVER) {
            Log.w(TAG, "USB handoff iface driver=$KERNEL_AUDIO_DRIVER errno=$ERRNO_DRIVER_BUSY (must reclaim)")
            return true
        }
        return false
    }

    /**
     * 驱动交接建议。
     *
     * DiPlay 的四种处理：
     * - `unsupported-owner` → 无法驱逐，放弃该接口
     * - `no-known-driver`   → 本来就没驱动占用，可直接 claim
     * - `detach-failed`     → 驱逐失败（可能无权限）
     * - `owner-changed`     → 驱动自己释放了，重试 claim
     */
    fun handoffAdvice(state: String): String = when (state) {
        "unsupported-owner" -> "该接口被未知驱动占用，无法驱逐"
        "no-known-driver" -> "无驱动占用，直接 claim"
        "detach-failed" -> "驱逐失败（可能缺权限），需 root/系统签名"
        "owner-changed" -> "驱动已自行释放，重试 claim"
        else -> "未知状态：$state"
    }

    /** USBMUX 接口校验（必须有 bulk 端点对）。 */
    fun validateUsbmuxInterface(hasIn: Boolean, hasOut: Boolean): String? = when {
        !hasIn || !hasOut -> ERR_NO_BULK_PAIR
        else -> null
    }

    /** NCM NTB16 头校验（DiPlay 原文三种错误）。 */
    fun validateNtb16(headerOk: Boolean, blockLengthOk: Boolean, padOk: Boolean): String? = when {
        !headerOk -> "NCM read buffer does not begin with an NTB16 header"
        !blockLengthOk -> "Invalid NTB16 block length"
        !padOk -> "Invalid NTB16 short-packet pad"
        else -> null
    }

    /** NCM 写未就绪时的处理（DiPlay: 保留桥重试）。 */
    fun onBulkOutNotReady(): String =
        "ncm bulk-out not ready; retaining bridge for retry"

    /** setConfiguration 失败但继续认领（DiPlay 容错策略）。 */
    fun onSetConfigurationFailed(config: Int): String =
        "setConfiguration $config reported failure; claiming anyway"
}
