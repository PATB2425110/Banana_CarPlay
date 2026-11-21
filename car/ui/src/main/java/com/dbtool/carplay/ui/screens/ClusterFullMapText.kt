package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.CarClusterFullMapEventUi
import com.dbtool.carplay.ui.CarClusterFullMapUi

/**
 * 仪表「原厂全屏地图模式」的文案映射。
 *
 * ## 为什么是纯函数
 *
 * 状态机的阶段是**技术标识**，用户看不懂；而这里要回答的只是
 * "仪表现在归谁、卡在哪一步"。把映射抽成纯函数有两个直接好处：
 *
 * - 设置页的索引行与详情页共用同一套判断，不会两处说法不一致；
 * - 每条规则都能用单测钉住，文案改动不必开模拟器逐条看。
 *
 * ## 判断顺序
 *
 * 先看灰度和本机能力，再看阶段：开关没开时阶段可能是任意值（甚至残留旧值），
 * 先说"守护未启用"才符合用户实际会看到的行为；本机没有厂商接口时同理，
 * 阶段只会是"不支持"，也只能说这一句。
 */

/** 一行摘要，用于「仪表导航」索引行。 */
fun clusterFullMapSummary(ui: CarClusterFullMapUi): String =
    "全屏模式 · " + clusterFullMapDetail(ui).first

/** 详情页一行状态 + 可选补充（失败原因）。 */
fun clusterFullMapDetail(ui: CarClusterFullMapUi): Pair<String, String?> {
    if (!ui.guardEnabled) {
        return "守护未启用" to "灰度开关关闭时按旧路径投屏"
    }
    if (!ui.supported) {
        return "本机不支持" to "未找到车机的全屏地图接口，按原厂界面投屏"
    }
    return when (ui.phase) {
        "ACQUIRING" -> "正在接管仪表…" to null
        "WAITING_FRAME" -> "已接管，等待画面…" to null
        "ACTIVE" -> "已接管仪表" to elapsedDetail(ui.sinceElapsedMs)
        "REOPENING" -> "画面被车机收回，正在重开…" to null
        "RELEASING" -> "正在还原原厂仪表…" to null
        "RETRY_PENDING" -> "还原未成功，稍后重试" to ui.lastError
        "RESTORED" -> "已还原原厂仪表" to null
        "FAILED" -> "接管失败" to ui.lastError
        else -> "空闲" to null
    }
}

/**
 * 时长文案：按秒向下取整，不足一秒单独说。
 *
 * 「不到 1 秒」而不是「0 秒」：刚点完按钮时写 0 秒会像没开始；
 * 而先写「正在接管」、一秒后再写「已持续 1 秒」也更贴近用户看到的节奏。
 */
private fun elapsedDetail(elapsedMs: Long): String {
    val seconds = elapsedMs / 1000
    return if (seconds < 1) "已持续不到 1 秒" else "已持续 $seconds 秒"
}

/**
 * 事件 → 中文一句话。未知 kind 返回 null（调用方自行跳过）。
 *
 * kind 只是代码里的键；这里返回的每一句都是给车主看的话，
 * 不允许出现 `ACQUIRE` / `VERIFIED` / `TIMEOUT` 这类英文标识。
 */
fun clusterFullMapEventText(event: CarClusterFullMapEventUi): String? =
    when (event.kind) {
        "ACQUIRE_SENT" -> "请求接管仪表"
        "ENTER_VERIFIED" -> "车机已确认进入全屏地图"
        "ENTER_UNVERIFIED" -> "进入未确认，正在重试"
        "FIRST_FRAME" -> "收到第一帧画面"
        "FIRST_FRAME_TIMEOUT" -> "等首帧超时，已回滚"
        "TAKEN_BACK" -> "车机收回了仪表"
        "REOPEN_OK" -> "重新接管成功"
        "REOPEN_FAILED" -> "重新接管失败"
        "RELEASE_SENT" -> "请求还原原厂仪表"
        "RELEASE_VERIFIED" -> "已确认还原"
        "RELEASE_UNVERIFIED" -> "还原未确认，稍后重试"
        "RESTORE_ON_BOOT" -> "启动时补还原"
        "GUARD_DISABLED" -> "守护关闭，主动还原"
        else -> null
    }

/**
 * 历史列表右侧的时间：相对「现在」的秒数。
 *
 * - 不超过 60 秒：「N 秒前」（0 秒也照写，说明记录就是刚刚）；
 * - 超过 60 秒：「N 分钟前」，分钟向下取整（125 秒 → 2 分钟前）。
 * - 时钟回拨 / 未来时间戳：钳到 0，避免出现「-3 秒前」。
 */
fun clusterFullMapEventAgo(event: CarClusterFullMapEventUi, nowElapsedMs: Long): String {
    val elapsedMs = (nowElapsedMs - event.atElapsedMs).coerceAtLeast(0L)
    val seconds = elapsedMs / 1000
    return if (seconds <= 60) "$seconds 秒前" else "${seconds / 60} 分钟前"
}
