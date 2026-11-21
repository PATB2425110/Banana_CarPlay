package com.dbtool.carplay.api.settings

/**
 * 「这次改动要不要重连才生效」的判据（PATCH-0103，2026-10-12 车主口径）。
 *
 * ## 为什么需要它
 *
 * 设置页从「草稿 + 保存并重连」改成**自动保存**之后，「保存」与「生效」
 * 被拆成了两件不同的事：
 *
 * - **保存**：改动即落盘，不需要点任何按钮；
     * - **生效**：一部分设置改了当场就变（画面翻转、Dock 排序、导航焦点…），
 *   另一部分**必须重连**才生效 —— 画布尺寸与 `/info` 都在**握手那一刻**定，
 *   音频通道号只在**建轨**时用得上，热点身份只在**建会话**时读
 *   （见 `CarSessionSettings` 里各字段的「必须保存并重连」注释）。
 *
 * 自动保存之后如果什么都不说，用户会以为「改了就是生效了」，而实际要等到下次
 * 重连。所以底部状态行必须能回答一句：**现在有没有改动在等重连**。
 *
 * ## 判据方向：保守，默认算「需要重连」
 *
 * 这里维护的是一份**就地生效白名单**（[LIVE_FIELDS]），**不在名单里的一律按
 * "需要重连"处理**。方向是刻意选的：
 *
 * - 把"就地生效"误判成"需要重连" ⇒ 状态行多提一句，用户多点一次重连，**无害**；
 * - 反过来（把"需要重连"误判成"就地生效"）⇒ 用户以为已经生效，实际没有，
 *   表现为"设置改了没反应" —— 现场最难查的一类问题。
 *
 * ## 实现手法：抹平再比较，而不是逐个字段比
 *
 * 不去写 `if (a.x != b.x || a.y != b.y || …)`（`CarSessionSettings` 有 70 多个字段，
 * 漏一个就错），而是**把白名单字段的值用旧值覆盖**，再看整体还有没有差异。
 * 这样新增字段**默认落在"需要重连"一侧**，不需要同步维护第二份清单 ——
 * 漏掉的风险被推到了安全的那一边。
 *
 * ## 为什么是纯函数
 *
 * 它要在设置页**每次改动**时被求值（渲染路径上），所以必须便宜、无副作用；
 * 而判错的代价是"用户以为生效了其实没有"。因此放在 `:car:api` 里脱机单测
 * （测试落在 `:car:core-bridge`，见 `REF-pitfalls.md` §127）。
 */
object CarSettingsReconnectPolicy {

    /**
     * 改了**当场生效**、不需要重连的字段名。
     *
     * 每一个都有代码依据（写在这里是为了让"为什么它算就地生效"可被复查）：
     *
     * | 字段 | 依据 |
     * | --- | --- |
     * | `clusterFlip180` | `CarAppController.applySettings` 里明确注释"画面翻转**就地生效**（不重连）" |
     * | `dockAppOrder` | `CarAppController` 里走 `applySettings(..., reconnect = false)` |
     * | `keepHotspotAlive` | 同上（`reconnect = false`） |
     * | `safeAreaDrawOutside` | 副屏区外绘制开关由 `resolveClusterSpec()` 现读 |
     * | `debugLogs` | 日志开关在写日志时现取 |
     * | `hideTopBar` / `hideBottomBar` / `dockBar` / `fullscreen` | 纯 Compose 层布局，不参与握手声明 |
     * | `hudGuidanceEnabled` | `CarPlaySessionBridge.updateSettings` 里直接改 HUD 闸门（PATCH-0114）；HUD 是握手**之后**的单向上报，不需要 iPhone 同意任何东西 |
     * | `hudPushEnabled` | 会话建立/结束时由 `CarAppController` 调 `EcarxVfhudChannel.activate()/deactivate()`；通道只是车控信号，不参与握手声明。开关本身必须在会话中途也能立即生效 |
     * | `clusterAutoShowOnRoute` | `CarPlaySessionBridge.updateSettings` 里直接改「导航自动投屏」闸门（PATCH-0119）；它只是"路线状态要不要自动投"的本地判据，不参与握手声明。**而且必须立即生效**：导航中关掉它若要重连才生效，等于为了关一个开关掐断一次 CarPlay |
     * | `localNavigationCardEnabled` | 主页 Compose 入口 + 仪表中央自绘卡片显隐，只读取 UI 设置，不参与与 iPhone 的握手 |
     * | `projectionEnabled` | 设置页「投屏到仪表」总开关（2026-10-20 合并入口）：开关本身同步提交并立即建窗/拆窗；能力门由 `CarPlaySessionBridge.isProjectionEnabled` **现读**，`resolveClusterSpec()` 不读它，因此不参与握手声明，不需要重连 |
     * | `clusterAppearanceMode` | 仪表主题明暗只替换已存在皮肤 View 的 palette，不改变 CarPlay 声明或 Surface；必须立即生效 |
     * | `hudProjectionEnabled` | HUD 地图投屏窗口由 `CarAppController.applyHudProjection()` 当场建/拆；它不参与握手声明（111 的声明来自仪表副屏 spec） |
     * | `hudCrop` / `hudScaleMode` | 裁剪只改 `TextureView.setTransform`，GPU 纹理矩阵下一帧即生效，不需要重建窗口或重连 |
     * | `navigationAudioFocusEnabled` | `CarPlaySessionBridge.updateSettings` 就地更新导航焦点闸门，不改变音轨或握手 |
     *
     * ⚠️ 这份名单**只用于文档与回归测试**；真正的比较在 [requiresReconnect] 里
     * 用 `copy(...)` 抹平完成。两者必须一致，由
     * `CarSettingsReconnectPolicyTest` 里的源码断言钉住。
     */
    val LIVE_FIELDS: List<String> = listOf(
        "clusterFlip180",
        "dockAppOrder",
        "keepHotspotAlive",
        "safeAreaDrawOutside",
        "debugLogs",
        "hideTopBar",
        "hideBottomBar",
        "dockBar",
        "fullscreen",
        "hudGuidanceEnabled",
        "hudPushEnabled",
        "clusterAutoShowOnRoute",
        "localNavigationCardEnabled",
        "projectionEnabled",
        "clusterAppearanceMode",
        "uiAppearanceMode",
        "hudProjectionEnabled",
        "hudCrop",
        "hudScaleMode",
        "navigationAudioFocusEnabled",
        "immersiveGlassCardsEnabled",
        "immersiveGlassColor",
        "immersiveGlassTransparency",
    )

    /**
     * [next] 相对 [previous] 有没有**必须重连才能生效**的改动。
     *
     * @return true = 有改动在等重连（状态行该提示，并给出「重连生效」入口）。
     *         两次完全相同、或只动了 [LIVE_FIELDS] 里的字段时为 false。
     */
    fun requiresReconnect(previous: CarSessionSettings, next: CarSessionSettings): Boolean {
        if (previous == next) return false
        // 把"就地生效"的字段抹成旧值：抹完还有差异 ⇒ 差异一定落在需要重连的字段上。
        val flattened = next.copy(
            clusterFlip180 = previous.clusterFlip180,
            dockAppOrder = previous.dockAppOrder,
            keepHotspotAlive = previous.keepHotspotAlive,
            safeAreaDrawOutside = previous.safeAreaDrawOutside,
            debugLogs = previous.debugLogs,
            hideTopBar = previous.hideTopBar,
            hideBottomBar = previous.hideBottomBar,
            dockBar = previous.dockBar,
            fullscreen = previous.fullscreen,
            hudGuidanceEnabled = previous.hudGuidanceEnabled,
            hudPushEnabled = previous.hudPushEnabled,
            clusterAutoShowOnRoute = previous.clusterAutoShowOnRoute,
            localNavigationCardEnabled = previous.localNavigationCardEnabled,
            projectionEnabled = previous.projectionEnabled,
            clusterAppearanceMode = previous.clusterAppearanceMode,
            uiAppearanceMode = previous.uiAppearanceMode,
            hudProjectionEnabled = previous.hudProjectionEnabled,
            hudCrop = previous.hudCrop,
            hudScaleMode = previous.hudScaleMode,
            navigationAudioFocusEnabled = previous.navigationAudioFocusEnabled,
            immersiveGlassCardsEnabled = previous.immersiveGlassCardsEnabled,
            immersiveGlassColor = previous.immersiveGlassColor,
            immersiveGlassTransparency = previous.immersiveGlassTransparency,
        )
        return flattened != previous
    }
}
