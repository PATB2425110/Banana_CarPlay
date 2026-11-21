package com.dbtool.carplay.api.developer

/**
 * 「调试反连隧道」的界面可见状态（2026-10-16）。
 *
 * ## 为什么需要它
 *
 * 隧道此前在开发者页只有一个开关：用户拨上去之后，**连没连上、卡在哪一步、
 * 上次为什么失败**在界面上全都看不到。现场因此出现两种近似无解的问法：
 * "开关是开着的，到底通没通？"、"上回改了地址，它有没有真的在重连？"
 * —— 只能靠猜，或者干脆采一份诊断包再看日志。
 *
 * ## 为什么单独一套枚举，而不是直接用 `LynkDebugTunnel.State`
 *
 * `car:api` 是**零依赖契约模块**，不许引用任何 platform 实现
 * （`LynkDebugTunnel` 在 `car:platform-geely`，且它是 Android 侧的类）。
 * 界面（`car:ui`）只认契约层的类型，翻译放在 platform 层一处完成
 * （见 `LynkDebugTunnelStatusMapping.toUiStatus`）—— 这样"界面显示什么"
 * 与"隧道内部怎么记账"可以各自演进，而不必让 `car:ui` 认识 platform 层。
 */
enum class CarDebugTunnelState(val title: String) {
    /** 未开启（开关关着，或地址为空没起）。 */
    IDLE("未开启"),

    /** 正在与反连服务器建链（含口令握手）。 */
    CONNECTING("连接中"),

    /** 已连上，正在双向转发。 */
    CONNECTED("已连接"),

    /** 断开但会自动重连（指数退避）。 */
    RETRYING("重连中"),
}

/**
 * 调试反连隧道的实时状态快照（只读回显，不带行为）。
 *
 * ⚠️ 这里**只有读数，没有开关** —— 开关属于用户设置
 * （`CarSessionSettings.debugTunnelEnabled`），两件事分开：设置要能拨、要落盘；
 * 本类型只是"此刻实际是什么情况"。
 */
data class CarDebugTunnelStatus(
    /** 当前所处阶段。 */
    val state: CarDebugTunnelState = CarDebugTunnelState.IDLE,

    /** 最近一次失败原因（已脱敏短句）。null / 空白串 = 没有失败记录。 */
    val lastError: String? = null,

    /** 最近一次成功连上的时刻（毫秒）；0 = 这一轮还没连上过。 */
    val lastConnectedAtMs: Long = 0L,

    /** 当前这一轮累计重连次数。 */
    val reconnectCount: Int = 0,
) {
    /**
     * 人类可读的一句话（界面状态行的副文案）。
     *
     * 四态语气**刻意各不相同**，因为用户在这四个阶段要做的下一件事不一样：
     * 未开启时要"去打开"，连接中要"等"，已连接可以"直接 adb 进来"，
     * 重连中要"看上次失败原因再决定改不改地址"。
     *
     * @param nowMs 当前时刻（毫秒）。由调用方传入而不是内部取系统时间，
     *        好让本函数保持**纯函数**、单测里可以钉死边界。
     */
    fun detailLine(nowMs: Long): String = when (state) {
        CarDebugTunnelState.IDLE ->
            if (lastError.isNullOrBlank()) {
                "未开启隧道"
            } else {
                "未开启；上次失败：$lastError"
            }

        CarDebugTunnelState.CONNECTING -> "正在反连服务器…"

        CarDebugTunnelState.CONNECTED ->
            if (lastConnectedAtMs > 0L) {
                "已连上，保持 ${humanElapsed(nowMs - lastConnectedAtMs)}"
            } else {
                // 理论上不该出现（连上必然写时刻），但状态与时刻是两个字段，
                // 真出现了宁可少说一句，也不要拿 0 去算出一个"1970 年"的荒谬时长。
                "已连上"
            }

        CarDebugTunnelState.RETRYING ->
            "连接断开，已重连 $reconnectCount 次" +
                if (lastError.isNullOrBlank()) "" else "；上次失败：$lastError"
    }

    companion object {
        /**
         * 毫秒 → 「刚刚 / N 秒 / N 分钟 / N 小时 / N 天」。
         *
         * 取**下界档**而不是四舍五入：说"1 分钟"时实际可能只过了 60 秒，
         * 宁少报不多报 —— 排障时"它已经保持了很久"比"它好像刚连上"更容易误导人。
         */
        fun humanElapsed(ms: Long): String = when {
            ms < 1_000L -> "刚刚"
            ms < 60_000L -> "${ms / 1_000L} 秒"
            ms < 3_600_000L -> "${ms / 60_000L} 分钟"
            ms < 86_400_000L -> "${ms / 3_600_000L} 小时"
            else -> "${ms / 86_400_000L} 天"
        }
    }
}
