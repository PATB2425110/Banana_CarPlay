package com.dbtool.carplay.api.system

/**
 * 平台对 **USB 主机（Host）模式** 的支持情况。
 *
 * 有线 CarPlay 的硬前提：车机 USB 口要能作为 Host 去枚举 iPhone。
 * 这一步失败时应用侧完全看不出原因——`UsbManager.deviceList` 恒为空，
 * 界面一直停在「等待 iPhone 接入」，看起来像"插了没反应"。
 */
enum class CarUsbHostSupport {
    /** 已确认支持（能读到内核的 USB 总线目录）。 */
    SUPPORTED,

    /** 已确认不支持：内核根本没有 USB 主机子系统。 */
    UNSUPPORTED,

    /**
     * 无法判定。
     *
     * ⚠️ 这个取值不是凑数的，**绝大多数正常手机/车机都会落在这里**：
     * 探测依赖读 `/sys/bus`、`/dev/bus/usb`，而 SELinux 通常不向普通应用开放它们。
     * 关键点是 `File.list()` 对"权限被拒"和"路径不存在"**都返回 null**
     * （不抛 `SecurityException`），两者无法区分，所以读不到时**必须**停在
     * "无法判定"——把它当成"不支持"会在正常的机器上误报（已在真机上验证过这一点）。
     */
    UNKNOWN,
}

/** 平台身份信息。用于诊断回显与配置选择，不参与协议协商。 */
data class CarPlatformInfo(
    val platformId: String,
    val platformName: String,
    /** 车机 OS 版本，例如 `Flyme Auto 1.2.3` / `Android 12`。 */
    val osVersion: String,
    val deviceModel: String,
    /** 是否为车机（false 表示手机/平板/模拟器）。 */
    val isHeadUnit: Boolean,
    /** 车机固件版本（若可获取）。 */
    val firmwareVersion: String? = null,
    /**
     * USB 主机能力（有线 CarPlay 的前提），见 [CarUsbHostSupport]。
     *
     * ⚠️ **只用于诊断回显，不要用它拦断连接流程。** 探测是文件系统启发式，
     * 误判为 [CarUsbHostSupport.UNSUPPORTED] 时若直接拒绝启动有线会话，
     * 会把本来可用的车机一起挡掉。
     */
    val usbHostSupport: CarUsbHostSupport = CarUsbHostSupport.UNKNOWN,
)

/**
 * 车机系统能力适配：有没有 root、能不能开机自启、能不能常驻前台。
 *
 * 这是"车机系统适配"最容易长出差异化逻辑的地方，因此单独成接口，
 * 避免后续把 `SystemProperties`、`su`、车机专有服务调用写进 UI 或 Core。
 */
interface CarSystemAdapter {
    val info: CarPlatformInfo

    /** 当前硬件/系统是否被本 Adapter 认为支持。false 时产品应给出明确提示而不是黑屏。 */
    fun isSupported(): Boolean = true

    /** 读取系统属性；无法读取时返回 null（不要抛异常）。 */
    fun systemProperty(key: String): String? = null

    /** 车机是否支持开机自启。 */
    fun supportsAutoStart(): Boolean = true

    /** 车机是否支持 root 提权（部分车机的 MFi / 数据显示需要）。 */
    fun supportsRoot(): Boolean = false

    /**
     * 请求把本应用置于前台并保持（车机常驻前台场景）。
     * 返回 false 表示车机不允许，调用方不应重试死循环。
     */
    fun requestForegroundKeepAlive(reason: String): Boolean = true

    fun releaseForegroundKeepAlive() {}

    /**
     * 是否需要在启动时申请 VPN 权限（有线 CarPlay 需要 VpnService）。
     * 部分车机已把 VPN 能力预授权，无需弹窗。
     */
    fun requiresVpnConsent(): Boolean = true

    /**
     * 本机 USB 主机能力，见 [CarUsbHostSupport]。
     *
     * 默认 [CarUsbHostSupport.UNKNOWN]，理由是**宁可漏报不可误报**：
     * 把它判成"不支持"会让人放弃本来可用的有线链路，而判成"无法判定"
     * 最坏只是诊断页少一条解释。具体平台按自己的内核/硬件事实覆写。
     */
    fun usbHostSupport(): CarUsbHostSupport = CarUsbHostSupport.UNKNOWN
}
