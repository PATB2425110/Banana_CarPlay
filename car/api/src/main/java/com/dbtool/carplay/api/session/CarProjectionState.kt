package com.dbtool.carplay.api.session

/**
 * 仪表副屏投屏（CarPlay cluster stream 111）的对外状态快照。
 *
 * ## 为什么需要单独一个模型
 *
 * 投屏是**异步且可能失败**的：命令发出去不等于 iPhone 画上去了。UI 必须能区分
 * 下面这几种「看起来一样、原因完全不同」的情况，否则用户只会看到「按了没反应」：
 *
 * | 字段 | false 的含义 | 用户该做什么 |
 * | --- | --- | --- |
 * | [streamReady] | iPhone 还没为本机建第二路屏流 | 确认手机已连、系统支持第二屏 |
 * | [attached] | 车机侧窗口没建起来 | 查悬浮窗权限 / 固件是否放行副屏 |
 * | [firstFrame] | 窗建好了、iPhone 还没推画面 | 等；超时则是手机不认这个 URL |
 * | [shown] | 画面就绪但我们没要求显示 | 在设置页手动投屏 |
 *
 * 四者全 true 时，地图才真正在仪表上（见 [visible]）。
 *
 * @param streamReady `AirPlaySession.clusterStream > 0`
 * @param attached    车机侧悬浮窗已挂上仪表屏
 * @param firstFrame  iPhone 首帧已到达（此时画面才真正可见）
 * @param displayId   接管的屏 id；未接管为 -1
 * @param shown       我们是否要求 iPhone 显示画面
 */
data class CarProjectionState(
    val streamReady: Boolean = false,
    val attached: Boolean = false,
    val firstFrame: Boolean = false,
    val displayId: Int = -1,
    val shown: Boolean = false,
) {
    /** 画面此刻是否真的在仪表上。 */
    val visible: Boolean get() = streamReady && attached && firstFrame && shown

    /**
     * 一句话说清卡在哪一步。
     *
     * 判断顺序与 [visible] 的判据一致 —— 第一个不满足的即是原因。
     */
    val summary: String
        get() = when {
            !streamReady -> "等待 iPhone 建立仪表屏流"
            !attached -> "车机未能在仪表屏上创建投屏窗口"
            !firstFrame -> "已请求投屏，等待 iPhone 首帧"
            !shown -> "已就绪，当前未显示（可在设置页手动投屏）"
            else -> "仪表导航投屏中"
        }
}
