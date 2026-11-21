package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * DBPlay **CH341 USB→I2C 桥**（驱动苹果 MFI 认证芯片）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 的 `transport/Ch341I2cTransport.java`(18KB)、
 * `transport/Ch341UsbHost.java`(39KB)、`transport/LinuxI2cTransport.java` 反编译提取。
 *
 * ## 为什么要 CH341
 *
 * 苹果 MFI 认证芯片（如 CP2.0C / 2.0C）挂在 **I2C 总线**上，
 * 车机通常不给应用层开 I2C。**CH341 是廉价的 USB→I2C 桥**，
 * 插上就能从应用层直接读写 MFI 芯片（DiPlay 原包里有 `libxcertplay_i2c.so`）。
 *
 * ## CH341 流模式指令表（DiPlay 原文，可直接用）
 *
 * | 常量 | 值 | 含义 |
 * |---|---|---|
 * | `UIO_STREAM` | 171 (0xAB) | 进入流模式 |
 * | `UIO_DIR` | 64 (0x40) | 设方向 |
 * | `UIO_OUT` | 128 (0x80) | 输出 |
 * | `UIO_END` | 32 (0x20) | 流结束 |
 * | `START` | 116 (0x74) | I2C START |
 * | `STOP` | 117 (0x75) | I2C STOP |
 * | 写 | 128..191 | 写 N 字节（`WRITE_MIN`..`WRITE_MAX`）|
 * | 读 | 192..255 | 读 N 字节（`READ_MIN`..`READ_MAX`）|
 * | 设 | 96..99 | 参数设置（`SET_MIN`..`SET_MAX`）|
 *
 * `LENGTH_MASK = 63`（低 6 位是长度）、`ACK_BIT = 128`、`STREAM_END = 0`。
 *
 * ## MFI 复位时序（关键）
 *
 * ```
 * 拉低复位 (MFI_RESET_LOW_MILLIS)
 *   → 释放
 *   → 等启动 (MFI_RESET_STARTUP_MILLIS)
 *   → 静默等待 (RESPONSE_QUIET_MILLIS = 25ms)
 *   → 选片到读的间隔 (SELECT_TO_READ_GAP_MILLIS)
 * ```
 *
 * ⚠️ GPIO 只能用 **D0~D5**（DiPlay: `CH341 output GPIO must be D0..D5`）。
 *
 * ⚠️ 未实机验证。CH341 的 VID/PID 与车机 USB 权限须真机确认。
 */
class LynkCh341I2c(private val context: Context) {

    companion object {
        private const val TAG = "DBPlay-CH341"

        // ---- CH341 流模式指令 ----
        const val UIO_STREAM = 171
        const val UIO_DIR = 64
        const val UIO_OUT = 128
        const val UIO_END = 32

        const val I2C_START = 116
        const val I2C_STOP = 117

        const val WRITE_MIN = 128
        const val WRITE_MAX = 191
        const val READ_MIN = 192
        const val READ_MAX = 255
        const val SET_MIN = 96
        const val SET_MAX = 99

        const val LENGTH_MASK = 63
        const val ACK_BIT = 128
        const val STREAM_END = 0
        const val BITS_PER_I2C_BYTE = 8

        // ---- MFI 复位时序 ----
        const val MFI_RESET_LOW_MILLIS = 20L
        const val MFI_RESET_STARTUP_MILLIS = 100L
        const val RESPONSE_QUIET_MILLIS = 25L
        const val SELECT_TO_READ_GAP_MILLIS = 2L
        const val DEFAULT_TIMEOUT_MILLIS = 1000L

        /** USB 权限动作（DiPlay 原文）。 */
        const val CH341_USB_PERMISSION = "com.dbtool.carplay.CH341_USB_PERMISSION"
    }

    /** USB 权限状态。 */
    sealed class PermissionResult {
        data class Requested(val device: UsbDevice) : PermissionResult()
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class AlreadyGranted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    /** 打开结果。 */
    sealed class OpenResult {
        data class Connected(val session: Session) : OpenResult()
        data class Failed(val error: String) : OpenResult()
    }

    /** 一个已打开会话。 */
    class Session(
        val usbInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    /** 找 bulk IN + OUT 端点。 */
    fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            when (ep.direction) {
                UsbConstants.IN -> inEp = ep
                UsbConstants.OUT -> outEp = ep
            }
        }
        if (inEp == null || outEp == null) {
            Log.w(TAG, "No USB interface has both bulk IN and OUT endpoints")
            return null
        }
        return inEp to outEp
    }

    /** 打开 CH341。 */
    fun open(manager: UsbManager, device: UsbDevice): OpenResult {
        if (!manager.hasPermission(device)) {
            return OpenResult.Failed("USB permission has not been granted")
        }
        val iface = device.getInterface(0)
        if (!manager.openDevice(device).claimInterface(iface, true)) {
            return OpenResult.Failed("Could not claim CH341 USB interface")
        }
        val eps = findBulkEndpoints(iface) ?: return OpenResult.Failed("无 bulk 端点")
        return OpenResult.Connected(Session(iface, eps.first, eps.second))
    }

    // ---- 指令编码 ----

    /** 写 N 字节（N ≤ 63）。 */
    fun encodeWrite(data: ByteArray): ByteArray {
        require(data.size <= LENGTH_MASK) { "write length must be <= 63" }
        return byteArrayOf((WRITE_MIN or data.size).toByte(), *data)
    }

    /** 读 N 字节（N ≤ 63）。 */
    fun encodeRead(count: Int): ByteArray {
        require(count in 0..LENGTH_MASK) { "read length must be <= 63" }
        return byteArrayOf((READ_MIN or count).toByte())
    }

    /** 组装一次 I2C 事务：[START][写地址+数据][STOP]。 */
    fun encodeTransaction(addr: Int, write: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // ⚠️ 别用 `writeBytes(ByteArray)` —— 那是 API 33 才有的重载，
        // `write(ByteArray)` 才是 API 1 起就有、且语义相同（都写全部字节）。
        // 车机是 Android 9 / SDK 28，用 writeBytes 会在车机上 NoSuchMethodError 崩。
        // lint 的 NewApi 已在 1.0.33 出包时拦下这三行（PATCH-0070）。
        out.write(byteArrayOf(I2C_START.toByte()))
        out.write(encodeWrite(byteArrayOf((addr shl 1).toByte(), *write)))
        out.write(byteArrayOf(I2C_STOP.toByte()))
        return out.toByteArray()
    }

    /**
     * MFI 复位脉冲（低电平有效）。
     *
     * @param gpioPin 必须 D0~D5
     * @return 生成的低电平序列
     */
    fun pulseActiveLowReset(gpioPin: Int): ByteArray {
        require(gpioPin in 0..5) { "CH341 output GPIO must be D0..D5" }
        val bit = (1 shl gpioPin).toByte()
        return byteArrayOf(
            UIO_STREAM.toByte(), UIO_DIR.toByte(), 0, bit,       // 设为输出
            UIO_OUT.toByte(), 0, bit, 0,                          // 拉低
            UIO_END.toByte(),
        )
    }

    /** 复位后等待时序。 */
    fun resetTiming(): List<Pair<String, Long>> = listOf(
        "复位低电平" to MFI_RESET_LOW_MILLIS,
        "复位释放→启动" to MFI_RESET_STARTUP_MILLIS,
        "响应静默" to RESPONSE_QUIET_MILLIS,
        "选片→读间隔" to SELECT_TO_READ_GAP_MILLIS,
    )

    /** 校验响应字节数是否与预期一致。 */
    fun validateResponse(actual: Int, expected: Int): Boolean {
        if (actual != expected) {
            Log.w(TAG, "transaction returned $actual bytes; expected $expected")
            return false
        }
        return true
    }

    /** 检查某字节是否 NACK（ACK_BIT 为 0 表示 NACK）。 */
    fun isNack(ackByte: Int, index: Int): Boolean {
        val nack = (ackByte and ACK_BIT) == 0
        if (nack) Log.w(TAG, "CH341 reported an I2C NACK on byte $index")
        return nack
    }

    private object UsbConstants {
        const val IN = 128
        const val OUT = 0
    }
}
