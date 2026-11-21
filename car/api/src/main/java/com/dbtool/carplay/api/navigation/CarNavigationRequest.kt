package com.dbtool.carplay.api.navigation

/**
 * 「请界面跳到某个页面」的一次性请求（`:car:app` → `:car:ui`）。
 *
 * ## 为什么需要这个东西
 *
 * `:car:ui` 不能依赖 `:car:app`（依赖方向是 `:car:app` → `:car:ui`），
 * 所以控制器**没法直接调** `CarPlayApp` 里的 `push(...)`。
 * 而 `:car:ui` 的 `CarUiActions.openDeveloperSettings()` 又必须能
 * "把用户送到设置页的某个具体分区"（店主 2026-10-06 口径）。
 *
 * 两者之间需要一个**只装数据、不装行为**的载体：UI 把它
 * 渲染进 `CarUiModel.navigationRequest`，`CarPlayApp` 用 `LaunchedEffect`
 * 消费一次，然后回调 [CarUiActions.consumeNavigationRequest] 清空。
 *
 * ## 为什么是"一次性请求"而不是"当前目标页"
 *
 * 因为**导航是事件不是状态**：
 * - 做成状态的话，"用户在设置页手动按返回"会立刻被这条仍然存在的状态弹回去
 *   （返回 → 收到通知 → 又跳回来 → 死循环，用户被锁在那个页面里）；
 * - 一次性语义天然对齐 `LaunchedEffect(key)` 的 `key`：同一个 key 只发一次，
 *   用户随后的手动导航不会被覆盖。
 *
 * `key` 用 [Long] 自增而不是 `UUID`：可读、可在日志里对上，也不需要额外依赖。
 */
data class CarNavigationRequest(
    /** 单调自增，作为 `LaunchedEffect` 的 key。同一个 key 只会被消费一次。 */
    val key: Long,
    val target: CarNavigationTarget,
) {
    companion object {
        /**
         * 造一个指向 [target] 的请求。
         *
         * [key] 由**调用方（控制器）**持有并传入，不放在本对象里：
         * 请求是进程级一次性的，序号也必须是进程级的——若在这里自增，
         * 每个请求都会拿到同一个初值 ⇒ 连跳两次时 `key` 不变 ⇒ UI 的
         * `LaunchedEffect` 不重跑 ⇒ 第二次点击没反应。
         */
        fun fromTarget(target: CarNavigationTarget, key: Long): CarNavigationRequest =
            CarNavigationRequest(key = key, target = target)
    }
}

/**
 * 导航目标。
 *
 * ⚠️ 刻意**不是** `CarDestination` 的复制：`CarDestination` 在 `:car:ui`
 * 的 `navigation` 包里，是**界面自己的**路由表。让 `:car:app` 直接构造
 * `CarDestination` 会把"应用有哪些页面"泄漏到控制器层，以后调整界面结构
 * 都要动 `:car:app`。这里只描述**意图**（"去热点+ADB 那件事"），
 * 由 `:car:ui` 决定它落在哪个页面、哪个分区。
 */
enum class CarNavigationTarget {
    /**
     * 设置 → 连接 → 无线「立即建立热点 + ADB」。
     *
     * 失败页「去开 ADB 调试」的落点：直接打开**连接**分区（那个按钮在
     * 「热点服务」分组里），而不是把用户丢在设置索引页自己找。
     */
    SETTINGS_HOTSPOT,
}
