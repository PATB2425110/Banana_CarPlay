package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **仪表帧 + 箭头码表（第二套）+ 原厂地图借屏 + 歌曲状态**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydClusterFrame.java`(14KB)、
 * `hud/BydOemClusterNavi.java`(17KB)、`hud/BydAdbShell.java`(4KB)、
 * `hud/BydReadAddress.java`(5KB)、`hud/ClusterSongState.java`(11KB)、
 * `hud/BydClusterSong.java`(18KB)、`hud/BydAppleManeuver.java`(11KB)、
 * `hud/ClusterTurnGuidance.java`(10KB) 提取。
 *
 * ## ⚠️ 坑：箭头码表有两套，编码不同！
 *
 * | 场景 | 类 | LEFT | RIGHT | STRAIGHT | DESTINATION |
 * |---|---|---|---|---|---|
 * | 机动码（HUD/通用） | `BydManeuverCodes` | 1 | 2 | 11 | 48 |
 * | **仪表帧** | `BydClusterFrame` | **2** | **3** | **9** | **15** |
 *
 * **用错表 → 箭头全乱。** 两条通道各用各的表，别混。
 *
 * ## 🔥 「借屏」的具体实现：借原厂地图的仪表 Activity
 *
 * DiPlay 不是「新开窗口投仪表」，而是**借原厂地图的仪表页**：
 *
 * ```
 * STOCK_MAP                   = "com.byd.automap"
 * STOCK_MAP_CLUSTER_ACTIVITY  = "com.byd.automap.extra.MeterActivity"
 * JOURNAL                     = "restore_journal"
 * ```
 *
 * 流程：`pm … --user 0` 停掉原厂地图的 MeterActivity → 占它的位 → 退出时按
 * Journal 还原。失败态：`Stock-map hold refused` / `Invalid stock-map recovery journal`。
 *
 * ⚠️ **`--user 0`** 表明这是**多用户/多账户**场景下的操作，必须指定用户 0。
 *
 * ## ADB shell 封装（BydAdbShell）
 *
 * 失败重试间隔 `RETRY_MILLIS = 30000`（30 秒），**不放弃、慢速重试**。
 *
 * ## 歌曲状态（ClusterSongState / BydClusterSong）
 *
 * - `MAX_TEXT_BYTES = 255`
 * - `NOW_PLAYING_UPDATE = 20481`（与 iAP2 端点 `StartNowPlayingUpdates` 对上）
 * - 字段：`TITLE=1` / `ARTIST=12` / `ITEM=0`
 * - 状态：`STATUS_PLAYING=1` / `STATUS_SEEK_FORWARD=3` / `STATUS_SEEK_BACKWARD=4`
 * - 写节流：`NOTE_MILLIS=3000` / `ON_CHANGE_MILLIS=5000`
 * - 播放态：`PLAYING=1` / `PAUSED=2` / `STOPPED=3`
 *
 * ⚠️ 未实机验证。`com.byd.automap` 是比亚迪原厂地图，吉利 CS11 上是别的包名。
 */
object LynkClusterFrame {

    private const val TAG = "DBPlay-ClusterFrame"

    // ---- 🔥 仪表帧箭头码表（第二套，与 LynkVehicleFields.ManeuverCodes 不同！）----
    object FrameIcon {
        const val LEFT = 2
        const val RIGHT = 3
        const val SLIGHT_LEFT = 4
        const val SLIGHT_RIGHT = 5
        const val SHARP_LEFT = 6
        const val SHARP_RIGHT = 7
        const val U_TURN_LEFT = 8
        const val STRAIGHT = 9
        const val ROUNDABOUT_ENTER = 11
        const val ROUNDABOUT_EXIT = 12
        const val DESTINATION = 15
        const val ROUNDABOUT_ENTER_CLOCKWISE = 17
        const val ROUNDABOUT_EXIT_CLOCKWISE = 18
        const val U_TURN_RIGHT = 19

        fun name(code: Int): String = when (code) {
            LEFT -> "左转"
            RIGHT -> "右转"
            SLIGHT_LEFT -> "微左"
            SLIGHT_RIGHT -> "微右"
            SHARP_LEFT -> "急左"
            SHARP_RIGHT -> "急右"
            U_TURN_LEFT -> "左掉头"
            U_TURN_RIGHT -> "右掉头"
            STRAIGHT -> "直行"
            ROUNDABOUT_ENTER -> "进入环岛"
            ROUNDABOUT_EXIT -> "驶出环岛"
            ROUNDABOUT_ENTER_CLOCKWISE -> "进入环岛（顺时针）"
            ROUNDABOUT_EXIT_CLOCKWISE -> "驶出环岛（顺时针）"
            DESTINATION -> "到达终点"
            else -> "未知($code)"
        }
    }

    /** 仪表帧。 */
    data class Frame(
        val icon: Int,
        val roundaboutExit: Int,
        val distanceMeters: Int,
        val road: String?,
        val routeRemainingMeters: Int,
        val routeRemainingSeconds: Int,
    )

    // ---- 借原厂地图的仪表页 ----
    /** 原厂地图包名（比亚迪）。 */
    const val STOCK_MAP = "com.byd.automap"

    /** 原厂地图的仪表 Activity。 */
    const val STOCK_MAP_CLUSTER_ACTIVITY = "com.byd.automap.extra.MeterActivity"

    /** 借屏恢复账本键名（与 DiLink3 的 `restore_stock_mode` 是两套，别混）。 */
    const val JOURNAL = "restore_journal"

    /** 操作必须指定的用户。 */
    const val TARGET_USER = 0

    const val LOG_HOLD_REFUSED = "Stock-map hold refused"
    const val LOG_RESTORE_WILL_RETRY = "Stock-map restore will retry"
    const val LOG_INVALID_JOURNAL = "Invalid stock-map recovery journal"
    const val LOG_INVALID_OEM_STATE = "Invalid OEM component state"

    /** ADB shell 失败重试间隔（毫秒）。 */
    const val ADB_RETRY_MILLIS = 30000L

    // ---- 歌曲状态 ----
    /** 歌曲文本字节上限。 */
    const val SONG_MAX_TEXT_BYTES = 255

    /** 与 iAP2 端点对应的 NowPlaying 更新 ID。 */
    const val NOW_PLAYING_UPDATE = 20481

    object SongField {
        const val ITEM = 0
        const val TITLE = 1
        const val ARTIST = 12
    }

    object SongStatus {
        const val STATUS = 0
        const val PLAYBACK = 1
        const val STATUS_PLAYING = 1
        const val STATUS_SEEK_FORWARD = 3
        const val STATUS_SEEK_BACKWARD = 4
    }

    object SongState {
        const val PLAYING = 1
        const val PAUSED = 2
        const val STOPPED = 3
    }

    /** 写节流：普通刷新 3 秒。 */
    const val SONG_NOTE_MILLIS = 3000L

    /** 写节流：换歌刷新 5 秒。 */
    const val SONG_ON_CHANGE_MILLIS = 5000L

    /** 空歌占位（避免残留上一首）。 */
    val EMPTY_SONG = " "

    /**
     * 借原厂仪表页的命令。
     *
     * @param disable true=停掉原厂页（借屏）；false=恢复
     */
    fun buildStockMapCommand(disable: Boolean): String =
        "pm ${if (disable) "disable" else "enable"} --user $TARGET_USER $STOCK_MAP_CLUSTER_ACTIVITY"

    /** 提示两套码表不可混用。 */
    fun codeTableWarning(): String =
        "箭头码表有两套：HUD 用 ManeuverCodes(LEFT=1)，仪表帧用 FrameIcon(LEFT=2)，混用箭头全乱"

    /** 借屏恢复提示。 */
    fun recoveryNote(): String =
        "借原厂地图仪表页前写入 $JOURNAL，退出/Crash 后必须还原，否则车机仪表功能被占死"
}
