package com.dbtool.carplay.api.display

/**
 * 仪表「原厂全屏地图模式」的可选控制契约。
 *
 * 与 [CarClusterDisplaySelection] / [CarClusterFlipControl] 同一模式：
 * 只有具备厂商模式接口的平台实现它，上层用 `as?` 探测。
 *
 * ## 它守护的是什么
 *
 * DBPlay 当前的「抢屏」只判断 `switchNaviMode(3)` 有没有抛异常，无法回答：
 * 模式真的切过去了吗？退出时真的还回去了吗？进程被杀后谁来补还原？
 * 本契约把这几件事收敛成可回读、可还原、可超时回滚、可跨进程补还原的状态机。
 *
 * ⚠️ 它**不是**产品开关：真正的用户开关是「投屏 / 不投屏」。本契约背后的
 * 灰度开关只决定「是否启用守护链路」，不改变「建窗即抢屏」的既有语义。
 */
interface CarClusterFullMapControl {
    /** 本机是否能调用厂商模式接口（类 / 方法探测结果）。 */
    fun isFullMapAvailable(): Boolean

    /**
     * 请求进入 / 退出原厂全屏地图模式。
     *
     * @return 是否已受理（不代表模式已切换；以 [fullMapState] 为准）。
     */
    fun requestFullMap(enabled: Boolean, reason: String): Boolean

    /** 当前状态快照（只读、便宜、不抛）。 */
    fun fullMapState(): CarClusterFullMapState

    /** 进程启动时补还原（账本有未完成记录才动作）。 */
    fun restoreIfLeftOn(): Boolean

    /** 首帧半程催一次 I 帧；null = 不催。 */
    fun setKeyFrameRequester(requester: (() -> Boolean)?)

    /** 灰度开关；false = 保持现有直接调用路径。 */
    fun setModeGuardEnabled(enabled: Boolean)
}

/**
 * 原厂全屏地图模式状态机的阶段。
 *
 * 与 `fulldeskmap_评估.md` §C.1 / §C.5 一一对应。
 */
enum class CarClusterFullMapPhase {
    /** 无窗口 / 已停止。 */
    IDLE,

    /** 已发 `switchNaviMode(3)`，等回读。 */
    ACQUIRING,

    /** 窗口已挂，等首帧。 */
    WAITING_FRAME,

    /** 模式 = 3 且首帧已到。 */
    ACTIVE,

    /** 被原厂收回，重开中。 */
    REOPENING,

    /** 已发 `switchNaviMode(1)`，等回读。 */
    RELEASING,

    /** 还原失败，账本保留，后台重试。 */
    RETRY_PENDING,

    /** 已确认 = 1，账本清除。 */
    RESTORED,

    /** 厂商接口不可用。 */
    UNSUPPORTED,

    /** 重开 / 还原达到上限。 */
    FAILED,
}

/** 原厂全屏地图模式的只读状态快照。 */
data class CarClusterFullMapState(
    val phase: CarClusterFullMapPhase = CarClusterFullMapPhase.IDLE,
    val supported: Boolean = false,
    val modeVerified: Boolean = false,
    val lastError: String? = null,
    val sinceElapsedMs: Long = 0L,
    /** 最近的状态变化记录，按旧→新排列（最多 30 条）。 */
    val events: List<CarClusterFullMapEvent> = emptyList(),
)

/** 一次全屏模式的状态变化记录（只用于展示，不参与判定）。 */
data class CarClusterFullMapEvent(
    val kind: String,          // 见 2.2 的 kind 表
    val atElapsedMs: Long,     // 记录时刻（SystemClock.elapsedRealtime）
    val detail: String? = null,// 可读补充（例如重开次数、错误信息）
)
