package com.dbtool.carplay.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dbtool.carplay.api.system.CarPlatformInfo
import com.dbtool.carplay.api.system.CarSystemAdapter
import com.dbtool.carplay.api.system.CarUsbHostSupport
import java.io.File

/**
 * 默认系统适配：读 `Build` / `PackageManager` 判定是否车机，并提供系统属性读取。
 *
 * `isHeadUnit` 的判定与 upstream 的思路一致：优先看是否声明了
 * `android.hardware.type.automotive`，其次看 UI 模式（`UI_MODE_TYPE_CAR`）。
 * 两者都不是的手机/模拟器归为非车机。
 */
open class AndroidCarSystemAdapter(
    protected val context: Context,
) : CarSystemAdapter {

    override val info: CarPlatformInfo by lazy { buildInfo() }

    override fun isSupported(): Boolean = true

    /**
     * 读取 Android 系统属性。
     *
     * 走 `android.os.SystemProperties` 反射，这是车机上常见的做法但属于隐藏 API，
     * 因此全程 best-effort：失败返回 null，绝不抛出。
     * 需要 root/专有服务的属性读取请在车机 Adapter 里覆写。
     */
    override fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    override fun supportsAutoStart(): Boolean = true

    /**
     * 默认 false。车机是否需要 root 由具体 Adapter 覆写——
     * 默认不假设车机有 root，避免误导后续功能。
     */
    override fun supportsRoot(): Boolean = false

    override fun requestForegroundKeepAlive(reason: String): Boolean = true

    override fun releaseForegroundKeepAlive() {}

    /** 默认需要 VPN 授权：有线 CarPlay 依赖 `VpnService`，且 upstream 会申请该权限。 */
    override fun requiresVpnConsent(): Boolean = true

    override fun usbHostSupport(): CarUsbHostSupport = probeUsbHost()

    /**
     * USB 主机能力探测：**只在能证明时下结论**。
     *
     * 判据（按可靠性排序）：
     * 1. `/sys/bus` 可读 —— 内核总线目录里有没有 `usb` 这一项，直接对应"内核编没编 USB 支持"。
     *    这是唯一能给出**否定**结论的判据（实测无 USB 子系统的车机上，该目录读得到但没有 `usb`）。
     * 2. `/dev/bus/usb` 可读 —— usbfs 挂载点，能列出来就说明主机侧确实存在（哪怕当前没插设备）。
     * 3. 两条都读不到 —— 返回 [CarUsbHostSupport.UNKNOWN]。
     *
     * ⚠️⚠️ **第 3 条不能合并进第 1 条**。`File.list()` 对"权限被拒"（SELinux 的 EACCES）
     * 和"路径不存在"（ENOENT）**都返回 null，不抛异常**——所以"读不到"既可能是没有，
     * 也可能是没权限，二者无法区分。早期版本把它当成"没有 USB 主机"，结果在一台
     * 正常的 vivo 手机上（`/sys/bus/usb/devices` 与 `/dev/bus/usb` 都存在、但应用无权读）
     * 诊断页报出了"不可用"。**误报"不支持"比不报严重得多**，因此读不到一律归入
     * [CarUsbHostSupport.UNKNOWN]，正常机器上这一行会显示"无法判定"而不是"不可用"。
     *
     * ⚠️ 不要用 `PackageManager.FEATURE_USB_HOST` 代替：那是 ROM 里的声明，与内核实际
     * 能力可以不一致。实测有一台车机声明了该 feature，但 `/sys/bus` 下没有 `usb`、
     * `/dev/bus` 不存在、`/proc/devices` 里也没有 usbfs 的 189 号设备。
     */
    private fun probeUsbHost(): CarUsbHostSupport {
        File(SYS_BUS_DIR).list()?.let { entries ->
            return if (USB_BUS_ENTRY in entries) {
                CarUsbHostSupport.SUPPORTED
            } else {
                CarUsbHostSupport.UNSUPPORTED
            }
        }
        // 总线目录读不到（多数正常机器都如此）：退一步看 usbfs。
        // 能列出内容即证明主机侧存在；列不出就是"仍然无法判定"。
        File(USBFS_DIR).list()?.let { return CarUsbHostSupport.SUPPORTED }
        return CarUsbHostSupport.UNKNOWN
    }

    private fun buildInfo(): CarPlatformInfo {
        val isAutomotive = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val uiModeCar = runCatching {
            val uiModeManager =
                context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR
        }.getOrDefault(false)
        return CarPlatformInfo(
            platformId = id,
            platformName = platformName,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            isHeadUnit = isAutomotive || uiModeCar,
            firmwareVersion = Build.DISPLAY,
            usbHostSupport = usbHostSupport(),
        )
    }

    protected open val id: String = "android"

    /**
     * 平台显示名。
     *
     * 提成可覆写字段而不是在 [buildInfo] 里写死 `"Android"`：
     * 否则厂商适配会出现 `platformId=ecarx` 配 `platformName=Android` 这种
     * 自相矛盾的诊断信息——排查线上问题时这种不一致最费时间。
     */
    protected open val platformName: String = "Android"

    private companion object {
        /** 内核总线目录。其中有没有 [USB_BUS_ENTRY] 就是"内核编没编 USB 支持"。 */
        const val SYS_BUS_DIR = "/sys/bus"

        /** USB 总线在 [SYS_BUS_DIR] 下的目录名。 */
        const val USB_BUS_ENTRY = "usb"

        /** usbfs 挂载点，主机侧设备节点（`001/002/...`）都在这里。 */
        const val USBFS_DIR = "/dev/bus/usb"
    }
}
