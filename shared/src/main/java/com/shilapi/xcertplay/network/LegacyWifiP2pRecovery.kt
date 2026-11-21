package com.shilapi.xcertplay.network

/**
 * Android 9 legacy Wi-Fi Direct 的三条**恢复判据**（纯函数，可脱机单测）。
 *
 * ## 为什么会有这个文件（2026-10-10，真车日志）
 *
 * 诊断包 `1791546694008_e2f499c9_67b3c24af6b5`（VIN `L6TTEST1111111111`，
 * 同一台车在 ADB 模式下是 `d5ddb4312da1`）里，`P2P_DIRECT` 模式 **7 次会话尝试全部**
 * 停在同一条栈上：
 *
 * ```
 * java.io.IOException: Could not establish WIFI_P2P_LEGACY hotspot:
 *     Turn on Wi-Fi in the head unit's settings, then reconnect
 *   at LegacyWifiP2pGroupManager.checkPrerequisites(LegacyWifiP2pGroupManager.kt:146)
 * ```
 *
 * 同一份包的 `system/logcat_system.txt` / `system/environment.txt` 把原因写得很直白：
 *
 * ```
 * WifiControllerIPCP: current state: WIFI_STATE_DISABLED
 * WiFi：启用=false 状态=1 IP=0.0.0.0
 * 当前网络：传输=以太网
 * ```
 *
 * 即 **这台车机的框架 Wi-Fi（STA）平时就是关的**（上行是以太网）。而 Wi-Fi Direct 走的是
 * Android 框架，框架要求 Wi-Fi 已开启（`WifiController` 只在 `WIFI_STATE_ENABLED` 之后
 * 才把 P2P 状态机拉起来）。旧实现只读**一次** `isWifiEnabled`，读到 `false` 就当成
 * "用户该自己去系统设置里开"，于是这个模式在这台车上**永远走不到组网阶段**——
 * 也就谈不上"发现 / 识别"任何热点。
 *
 * 修复里三处需要判断的分支抽成纯函数放在这里，好在纯 JVM 上钉死：
 *
 * 1. [legacyWifiStaIsPoweredOn] —— 状态同步：`getWifiState()` 到哪一步才算"真的开了"。
 * 2. [legacyWifiStaShouldBeRestored] —— 归属纪律：什么时候才该把 Wi-Fi 关回去
 *    （与 [legacyP2pGroupCanBeRemoved] 同源：**只还自己借的东西**）。
 * 3. [legacyP2pCreateGroupRetryable] —— 状态同步：`createGroup` 失败后该不该重试
 *    （Wi-Fi 刚上电时 P2P 状态机可能还没就绪，这是典型的竞态）。
 *
 * ## ⚠️ 数值字面量是刻意的
 *
 * 下面几个码用字面量，而不是引用 `WifiManager.WIFI_STATE_*` / `WifiP2pManager.*`：
 * 本文件要能在**纯 JVM 单测**里跑，而单测的 `android.jar` 是 stub，静态字段的取值
 * 不保证可读（同族坑见 `REF-pitfalls` §57）。这些数值本身是 Android **公开 API 的常量**，
 * 从 API 1 起没有变过，已用 `javap -constants` 对着 `android-34/android.jar` 核对：
 * `WIFI_STATE_DISABLED=1`、`WIFI_STATE_ENABLED=3`、`WifiP2pManager.ERROR=0`、`BUSY=2`。
 *
 * ⚠️ **2026-10-13（PATCH-0113）**：上面引用的 `P2P_DIRECT` 那一档热点模式已整体移除，
 * 本文现场属**历史证据**。本文件的判据按车主边界**原样保留**（它们服务的是上游 `:shared`
 * 的无线链路，与撤掉的那一档模式无关）。
 */

/** `WifiManager.getWifiState()`：`1` = 已关闭（Android 公开 API 常量 `WIFI_STATE_DISABLED`）。 */
internal const val LEGACY_WIFI_STATE_DISABLED = 1

/** `WifiManager.getWifiState()`：`0` = 正在关闭（`WIFI_STATE_DISABLING`）。 */
internal const val LEGACY_WIFI_STATE_DISABLING = 0

/** `WifiManager.getWifiState()`：`2` = 正在开启（`WIFI_STATE_ENABLING`）。 */
internal const val LEGACY_WIFI_STATE_ENABLING = 2

/** `WifiManager.getWifiState()`：`3` = 已开启（Android 公开 API 常量 `WIFI_STATE_ENABLED`）。 */
internal const val LEGACY_WIFI_STATE_ENABLED = 3

/** `WifiP2pManager.ERROR`（Android 公开 API 常量）。 */
internal const val LEGACY_P2P_ERROR = 0

/** `WifiP2pManager.BUSY`（Android 公开 API 常量）。 */
internal const val LEGACY_P2P_BUSY = 2

/**
 * `getWifiState()` 是不是"正在迁移"（`DISABLING` / `ENABLING`）。
 *
 * ⚠️ 迁移中**不许**发新的开关请求：厂商 ROM 对"关闭还没落地就要求开启"的处置
 * 各不相同——有的直接丢掉这次请求，有的长时间停在 `ENABLING`。现场表现就是
 * **连续重试时第二、三次反而开不起来**（第一次的收尾关闭还在途中）。
 * 正确做法是先等它落定，见 `LegacyWifiP2pGroupManager.awaitWifiStationSettled`。
 *
 * `null`（读不到）不算迁移：读不到时再等下去只是白等，交给后面的判定处理。
 */
internal fun legacyWifiStaIsTransitioning(state: Int?): Boolean =
    state == LEGACY_WIFI_STATE_DISABLING || state == LEGACY_WIFI_STATE_ENABLING

/**
 * `getWifiState()` 是不是"已经上电完成"。
 *
 * ⚠️ 只认 [LEGACY_WIFI_STATE_ENABLED]（`3`）。**不能**把 `ENABLING`（`2`）也当成功：
 * `WifiP2pManager.createGroup` 在 Wi-Fi 还处于 `ENABLING` 时调用，框架侧 P2P 状态机
 * 尚未就绪，返回的是 `BUSY` / `ERROR`——那正是"Wi-Fi 开了但 P2P 起不来"这类
 * "看着像好了其实没好"的现场来源。
 *
 * `null`（读不到）一律返回 false：调用方据此**继续等待**而不是直接放行，
 * 与 `LegacyWifiP2pGroupOwnership.legacyP2pGroupCanBeRemoved` 里
 * "读不到就不动作"的处置方向一致。
 */
internal fun legacyWifiStaIsPoweredOn(state: Int?): Boolean = state == LEGACY_WIFI_STATE_ENABLED

/**
 * `close()` 时该不该把车机 Wi-Fi 关回去。
 *
 * 只有**两个条件同时成立**才关：
 * - [ownedByUs]：这次确实是本 manager 打开的（不是用户 / 其他应用本来就开着的）；
 * - [currentlyEnabled]：它**现在**确实还开着。
 *
 * 与 [legacyP2pGroupCanBeRemoved] 是同一条纪律——**只还自己借的东西**：
 * - 少判 [ownedByUs] ⇒ 会把用户自己打开的 Wi-Fi 关掉（车机连手机热点时尤其致命）；
 * - 少判 [currentlyEnabled] ⇒ 会对一个"读不到状态"（`null`）的车机发关闭命令，
 *   等于闭着眼睛改别人的状态。
 */
internal fun legacyWifiStaShouldBeRestored(ownedByUs: Boolean, currentlyEnabled: Boolean?): Boolean =
    ownedByUs && currentlyEnabled == true

/**
 * `createGroup` 返回这个原因码时，值不值得再试一次。
 *
 * - [LEGACY_P2P_BUSY] / [LEGACY_P2P_ERROR]：Wi-Fi 刚上电、P2P 状态机还没就绪时
 *   最常见的结果，隔一会儿再来一次通常就成了 ⇒ **可重试**。
 * - `P2P_UNSUPPORTED`(`1`) / `NO_PERMISSION`：硬件不支持或权限被拒，
 *   重试一万次也一样，还会白白吃掉用户的等待时间 ⇒ **立刻失败**，
 *   让上层把真正的原因报出来。
 */
internal fun legacyP2pCreateGroupRetryable(reason: Int): Boolean =
    reason == LEGACY_P2P_BUSY || reason == LEGACY_P2P_ERROR
