package com.dbtool.carplay.geely.lynk

/**
 * DBPlay **桌面小部件数据模型**（CarPlayGlance）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `glance/CarPlayGlance.java`(19KB) 提取。
 *
 * ## 用途
 *
 * 车机桌面 / 仪表上有一块「CarPlay 小卡片」，显示导航 + 媒体摘要。
 * DiPlay 用一个统一的 `Snapshot` 数据模型承载，由 `BydAppleManeuver`（比亚迪导航对象）
 * 填充导航字段、由媒体回调填充歌曲字段。
 *
 * ## Snapshot 字段（DiPlay 原文 10 项）
 *
 * | 字段 | 类型 | 含义 |
 * |---|---|---|
 * | `connected` | Boolean | CarPlay 是否已连接 |
 * | `maneuverType` | Integer? | 导航动作类型（左转/右转/直行…）|
 * | `drivingSide` | Int | 靠左/靠右行驶（0/1）|
 * | `distanceMeters` | Int | 距下一动作距离（米）|
 * | `road` | String? | 道路名 |
 * | `arrivalEpochSeconds` | Long? | 预计到达时间（epoch 秒）|
 * | `remainingSeconds` | Long? | 剩余时间（秒）|
 * | `remainingMeters` | Long? | 剩余距离（米）|
 * | `song` | String? | 当前歌曲名 |
 * | `playing` | Boolean | 是否正在播放 |
 *
 * ⚠️ 未实机验证。小部件宿主（桌面/仪表）需车机侧支持。
 */
class LynkGlanceSnapshot {

    /** 桌面卡片快照（对应 DiPlay `CarPlayGlance.Snapshot`）。 */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String? = null,
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        val song: String? = null,
        val playing: Boolean = false,
    ) {
        /** 是否应在卡片上显示导航区（有动作类型且有距离）。 */
        fun hasNavigation(): Boolean = maneuverType != null

        /** 是否应在卡片上显示媒体区（有歌名）。 */
        fun hasMedia(): Boolean = !song.isNullOrBlank()

        /** 导航剩余时间的人类可读文本。 */
        fun remainingText(): String? {
            val sec = remainingSeconds ?: return null
            val min = sec / 60
            return if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分"
        }

        /** 距离的人类可读文本。 */
        fun distanceText(): String {
            val m = if (remainingMeters != null) remainingMeters!!.toInt() else distanceMeters
            return if (m >= 1000) String.format("%.1f 公里", m / 1000.0) else "$m 米"
        }
    }

    /** 空快照（未连接时）。 */
    fun empty(): Snapshot = Snapshot(connected = false)

    /** 已连接但无导航/媒体（仅显示连接状态）。 */
    fun connectedOnly(): Snapshot = Snapshot(connected = true)
}
