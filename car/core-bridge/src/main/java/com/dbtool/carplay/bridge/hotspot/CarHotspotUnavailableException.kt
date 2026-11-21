package com.dbtool.carplay.bridge.hotspot

/**
 * **热点建不起来，会话没法开。**
 *
 * ⚠️ 2026-10-03 起，只在一种情况抛它：方案是「自建热点」（`ADB_HOSTAPD`）而
 * **拉起失败**。以前还会"回落到系统 LocalOnlyHotspot"，但该形态已整体移除
 * （见 `CarHotspotMode`）——实测它在车机上从没成功过，回落只是再白等 60 秒
 * 并拿到一句用户看不懂的报错。
 *
 * ## 为什么抛，而不是照旧回落
 *
 * 照旧回落等于：再白等 60 秒，拿到一句"系统热点启动超时"，而**用户完全不知道
 * 下一步该做什么**——真正的出路（打开车机自带热点再复用）没有出现在任何文案里。
 *
 * 抛出去则由 `CarPlaySessionBridge.startInternal` 直接落到首页「连接失败」，
 * 文案就是 [message]——**已经是写好的中文**，刻意**不再过**
 * [com.dbtool.carplay.bridge.CarErrorText]：那张表是"上游英文 → 中文"，
 * 中文进去只会撞到兜底，把具体原因吃掉。
 */
internal class CarHotspotUnavailableException(message: String) : IllegalStateException(message)
