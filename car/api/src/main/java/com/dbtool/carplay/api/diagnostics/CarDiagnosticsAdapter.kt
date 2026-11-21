package com.dbtool.carplay.api.diagnostics

/**
 * 诊断适配。
 *
 * 统一出口，避免各模块各自决定"日志写哪里"：车机现场排查时往往需要通过
 * 车机专有通道（ADB、串口、车机日志服务、U 盘导出）取日志。
 *
 * 实现必须保证非阻塞、不抛异常——诊断能力坏掉不允许影响 CarPlay 主流程。
 */
interface CarDiagnosticsAdapter {
    /**
     * 写一条日志。
     *
     * ⚠️ **没有开关**，调用即落盘（Android 上就是 `logcat`）。
     * 这里刻意不接设置页的「调试日志」开关：那个开关的真实语义是
     * **在 CarPlay 画面上叠加日志浮层**（性能敏感，驾驶中默认关），
     * 而 logcat 是现场排查的基础设施，不该因为界面上一个开关没打开就断掉
     * ——否则最需要日志的"连不上"现场恰好什么都抓不到。
     */
    fun log(tag: String, message: String)

    fun warn(tag: String, message: String) = log(tag, "WARN $message")

    fun error(tag: String, message: String, cause: Throwable? = null) = log(tag, "ERROR $message")

    /**
     * 上报一份状态快照（键值对），供诊断页/远程排查展示。
     * 覆盖式：同一 section 的旧值会被替换。
     *
     * **可选的扩展点**：默认空实现。只有真的接了"车机日志服务 / 远端上报"
     * 这类外部通道的适配器才需要覆写——写本地 logcat 的适配器覆写它没有意义。
     */
    fun reportSnapshot(section: String, values: Map<String, String>) {}

    /**
     * 该适配器是否有**可开关的**上报通道。
     *
     * **可选的扩展点**：默认 false（= 没有独立开关，[log] 始终生效）。
     * 覆写它只在实现方确实存在"关掉就不再记录"的外部通道时才有意义，
     * 此时应由实现方自己提供一个 `setEnabled` 并在设置变更时同步。
     */
    fun isEnabled(): Boolean = false
}
