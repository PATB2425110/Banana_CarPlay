package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarHudStatus
import java.util.Locale

/**
 * HUD 设置版块的**全部用户可见文案**（2026-10-18）。
 *
 * ## 为什么抽成纯函数
 *
 * 这一版块的状态来自三层（桥现场探测 → 上层去重计数 → 车机回执），字段多且
 * 组合多（未支持 / 未解析 / 绑定中 / 已连接 / 有失败 / 没收到帧）。把"哪个字段
 * 组合显示哪句话"写在 Composable 里，JVM 单测跑不到，只能靠上车看；写在这里
 * 就能用 `HudStatusTextTest` 一条条钉死。
 *
 * ## 文案纪律
 *
 * - **全中文**，不出现 `code 10` / `Parcel` / `bindService` 这类实现细节，
 *   也不出现英文阶段名 —— 设置页面向车主，不面向开发者；
 * - 未解析到服务时**不拿默认包名冒充**：那会让"这台车没有 HUD"看起来像
 *   "连的是百度服务但没连上"，排障会被带偏。
 */

/**
 * 目标服务那一行。
 *
 * 只显示宿主包名（`com.baidu.naviauto` / `com.autonavi.amapauto`），
 * 因为两个候选的服务类名相同，把类名一起显示只会把一行文字撑成三行。
 */
fun hudTargetText(status: CarHudStatus): String = when {
    !status.supported -> "本机不支持"
    status.targetPackage.isNotBlank() -> status.targetPackage
    else -> "未找到车机 HUD 服务"
}

/** 连接状态：已连接 / 绑定中… / 未连接。 */
fun hudConnectionText(status: CarHudStatus): String = when {
    !status.supported -> "不支持"
    status.available -> "已连接"
    status.connecting -> "绑定中…"
    else -> "未连接"
}

/** 导航数据去重不等于视频丢帧，也不代表原厂屏幕渲染成功。 */
fun hudCountersText(status: CarHudStatus): String = if (!status.supported) {
    "—"
} else {
    "收到 ${status.receivedFrames} 次 · 桥接受理 ${status.pushedFrames} 次 · " +
        "重复更新已过滤 ${status.droppedFrames} 次"
}

/**
 * 最近一条导航提示：`<转向文案> · <距离>`；没有则 `暂无导航提示`。
 *
 * 距离为 null 时只显示转向文案（不编一个"0 米"）；转向文案为空但距离有值时
 * 只显示距离 —— 两者都没有才算"暂无"。
 */
fun hudLatestText(status: CarHudStatus): String {
    if (!status.supported) return "暂无导航提示"
    val maneuver = status.lastManeuverText.trim()
    val meters = status.lastSegmentMeters
    return when {
        maneuver.isEmpty() && meters == null -> "暂无导航提示"
        maneuver.isEmpty() -> hudDistanceText(meters!!)
        meters == null -> maneuver
        else -> "$maneuver · ${hudDistanceText(meters)}"
    }
}

/**
 * 失败原因那一行；没有失败时返回 `null`（调用方据此不渲染那一行）。
 *
 * 常态下多挂一行"没有失败"是纯噪声，而这一行存在的意义恰恰是"出问题时它必须在"。
 *
 * ## 为什么这里要做一次"去技术词"
 *
 * 桥侧的 [CarHudStatus.lastFailure] 是**给诊断包看**的，里面会带
 * `bindService 返回 false`、`服务 onBind 返回 null` 这类实现细节。
 * 设置页面向车主，任务书要求不出现这些词，所以这里把已知的技术表述换成
 * 一句人话；**宿主包名保留** —— 那是现场判断"两台车里哪台能连"的关键信息。
 */
fun hudFailureText(status: CarHudStatus): String? =
    status.lastFailure
        .takeIf { status.supported && it.isNotBlank() }
        ?.let(::friendlyFailure)

private fun friendlyFailure(raw: String): String = raw
    .replace("车机没有该服务（bindService 返回 false）", "没有可用的地图服务")
    .replace("服务 onBind 返回 null", "地图服务未响应")
    .replace("静默绑定失败（服务从未回调 onServiceConnected）", "地图服务未响应")
    .replace(Regex("""HUD 事务 \d+ 异常：[A-Za-z]+"""), "推送失败，服务未响应")
    .replace(Regex("""绑定异常：[A-Za-z.$]+(（[^）]*）)?"""), "连接失败")

/** 公里/米切换：1000 米以上用公里，避免"12345 米"这种难读的数。 */
private fun hudDistanceText(meters: Int): String = if (meters >= 1000) {
    String.format(Locale.CHINA, "%.1f 公里", meters / 1000.0)
} else {
    "$meters 米"
}
