package com.dbtool.carplay.api.config

/**
 * 配置字段的**变更生效方式**（2026-10-24）。
 *
 * 这是「安全配置变更」的类型级闸门：任何不在 [ConfigWhitelist] 里的键，
 * 一律按 [FORBIDDEN] 处理并拒绝修改。
 */
enum class ConfigFieldKind {
    /** 纯本地渲染参数（主题/圆盘/遮罩/视频变换/透视）。改完立即重绘，不重连。 */
    LOCAL_RENDER,

    /** `LIVE_FIELDS` 里允许即时生效的会话设置。 */
    LIVE,

    /** 必须重连才生效。允许改，但必须提示用户，不得伪装成即时生效。 */
    NEEDS_RECONNECT,

    /** 禁止通过本通道修改：投屏底层、协议、认证、解码器、Surface。 */
    FORBIDDEN,
}

/**
 * 配置白名单（2026-10-24）。
 *
 * ## 三份名单的来源
 *
 * - [LIVE]：**逐字复制** `CarSettingsReconnectPolicy.LIVE_FIELDS`，不另造一份真相；
 *   由 `ConfigWhitelistTest` 断言两者一致，防止漂移。
 * - [LOCAL_RENDER]：DBPlay 自己维护的渲染参数键（不属于 `CarSessionSettings`）。
 * - [FORBIDDEN]：硬编码拒绝清单。即使调用方硬塞也不放行。
 *
 * ## 为什么要有 FORBIDDEN
 *
 * 快照/回滚/规则引擎最终都要落到「改某个键」上。如果只靠调用方自觉，
 * 迟早有人顺手把 `displayId` 写进来。这里从类型层断掉：
 * [kindOf] 对未知键返回 `null`（= 不在白名单），[ConfigChangePlan] 直接拒绝。
 */
object ConfigWhitelist {
    val NEEDS_RECONNECT: Set<String> = setOf(
        "safeArea",
        "immersiveNavigationEnabled", "immersiveNavigationCardEnabled", "immersiveNavigationAlignment",
        "immersiveNavigationMapLayout", "immersiveNavigationSafeArea", "immersiveNavigationDrawOutside",
    )

    /** 与 `CarSettingsReconnectPolicy.LIVE_FIELDS` 逐字一致。 */
    val LIVE: Set<String> = setOf(
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
     * 本地渲染参数：改完只需 invalidate / 重新下发参数，不重连。
     *
     * 前缀形式（`dial_*` 等）在 [kindOf] 里按前缀匹配，因为圆盘布局与透视
     * 是逐角/逐轴展开成独立键存的。
     */
    /**
     * 本地渲染参数的**精确键集合**（2026-10-24 P1-1 修正）。
     *
     * ## 为什么不用前缀
     *
     * 初版用 `left_` / `draw_` / `mask_` 这类宽前缀，实测可被
     * `left_displayId` / `draw_initialUrl` / `right_auth_token` / `left_vin`
     * / `mask_token` 全部绕过（前缀命中即放行）—— 闸门 fail-open。
     *
     * 现在改成**逐键枚举**，键名全部抄自 `CarAppController` 的真实存储：
     * - `dbplay_dial_layout`: `draw_x/y/w/h`、`left_*`/`right_*`、`video_*`
     * - `dbplay_cluster_editor`: `video_persp_*`
     * - `dbplay_cluster_mask`（独立 prefs 组）: `enabled`、`tl_x`..`br_y`、
     *   `left_w/left_s/left_on` 等四边三件套、`mode`
     *
     * 新增渲染参数时必须同步加进这里，否则会被当"未知 → 拒绝"。
     */
    val LOCAL_RENDER: Set<String> = setOf(
        // 主题（clusterAppearanceMode 已在 LIVE 里，不放这里，避免分类歧义）
        "clusterThemeId",
        // 自绘区域（dbplay_dial_layout: draw_*）
        "draw_x", "draw_y", "draw_w", "draw_h",
        // 左右圆盘（dbplay_dial_layout: left_* / right_*）
        "left_dx", "left_dy", "left_scale", "left_visible",
        "right_dx", "right_dy", "right_scale", "right_visible",
        // 视频变换（dbplay_dial_layout: video_*）
        "video_scale", "video_dx", "video_dy",
        // 四角透视（dbplay_cluster_editor: video_persp_*）
        "video_persp_enabled",
        "video_persp_tlX", "video_persp_tlY",
        "video_persp_trX", "video_persp_trY",
        "video_persp_brX", "video_persp_brY",
        "video_persp_blX", "video_persp_blY",
        // 遮罩几何（dbplay_cluster_mask）
        "enabled",
        "tl_x", "tl_y", "tr_x", "tr_y", "bl_x", "bl_y", "br_x", "br_y",
        // 遮罩四边渐变
        "left_w", "left_s", "left_on",
        "top_w", "top_s", "top_on",
        "right_w", "right_s", "right_on",
        "bottom_w", "bottom_s", "bottom_on",
        "mode",
    )

    /**
     * 绝对禁止通过配置通道修改的键（或前缀）。
     *
     * 即使调用方塞进 `Map<String,String>` 也会被拒。
     */
    val FORBIDDEN: Set<String> = setOf(
        "displayId",
        "clusterDisplayId",
        "fixedClusterDisplayId",
        "initialURL",
        "initialUrl",
        "setup",
        "SETUP",
        "streamType",
        "screenTypeAlt",
        "decoderType",
        "surfaceLifecycle",
        "carPlayProtocol",
        "mfiCertificate",
        "mfiSignature",
        "authToken",
        "pairingKey",
        "vin",
    )

    /** 禁止的键前缀。 */
    val FORBIDDEN_PREFIXES: List<String> = listOf(
        "mfi_",
        "auth_",
        "token_",
        "pairing_",
        "surface_",
    )

    /**
     * 判定一个键的变更分类。
     *
     * @return `null` = **不在任何白名单内**，调用方必须拒绝（不是 FORBIDDEN 而是未知，
     *         两者都拒绝，但原因不同：未知=没授权，FORBIDDEN=明确禁止）。
     */
    fun kindOf(key: String): ConfigFieldKind? {
        if (FORBIDDEN.contains(key) || FORBIDDEN_PREFIXES.any { key.startsWith(it) }) {
            return ConfigFieldKind.FORBIDDEN
        }
        if (LIVE.contains(key)) return ConfigFieldKind.LIVE
        if (LOCAL_RENDER.contains(key)) return ConfigFieldKind.LOCAL_RENDER
        if (NEEDS_RECONNECT.contains(key)) return ConfigFieldKind.NEEDS_RECONNECT
        return null
    }

    /** 是否允许本通道修改（FORBIDDEN 与未知都返回 false）。 */
    fun isAllowed(key: String): Boolean {
        val kind = kindOf(key) ?: return false
        return kind != ConfigFieldKind.FORBIDDEN
    }

    /** 是否需要重连才生效。 */
    fun needsReconnect(key: String): Boolean = kindOf(key) == ConfigFieldKind.NEEDS_RECONNECT
}
