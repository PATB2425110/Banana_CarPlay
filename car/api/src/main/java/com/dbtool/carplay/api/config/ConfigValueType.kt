package com.dbtool.carplay.api.config

/**
 * 配置键的**值域契约**（2026-10-24 收尾轮）。
 *
 * ## 为什么需要这一层
 *
 * [ConfigWhitelist] 只管**键**在白名单里，不管**值**。
 * 结果是 `draw_x=NaN`、`left_on=yes`、`mode=NOT_A_MODE` 都能进快照，
 * 等到恢复执行器把它喂给渲染层时，`Float.coerceIn` 对 `NaN` 直接返回 `NaN`
 * （stdlib 行为），一路走到 GPU 矩阵。
 *
 * 这一层按 key 定义**类型 + 范围**，是 `of()` / `deserialize()` /
 * `ConfigChangePlan.build()` 三条路径的**唯一**值校验入口。
 *
 * ## 范围从哪来
 *
 * 每个数值范围都抄自生产代码里的真实常量或调用点，注释里写明出处。
 * **不按名字猜类型**；对契约不明的键一律不放进本表 → 视为「未知 → 拒绝」。
 *
 * ## VIN 防护的诚实边界
 *
 * 强类型是本层的主要防护：`draw_x` 只收有限数值，VIN 这类 17 位串塞不进来。
 * 但**自由字符串字段**（`clusterThemeId`、`dockAppOrder`）在当前调用方
 * 没有传入已知 VIN 的前提下，**无法保证**识别所有形态的 VIN。
 * 见 [ConfigSnapshot.of] 的 `knownVins` 参数。
 */
internal object ConfigValueType {

    /** 值的类型类别。 */
    enum class Kind { BOOLEAN, FLOAT, INT, ENUM, STRING, CUSTOM }

    /**
     * 单个键的值契约。
     *
     * @param kind 类型
     * @param min 数值下界（含）；`null` = 不限
     * @param max 数值上界（含）；`null` = 不限
     * @param values 枚举允许值（`kind == ENUM` 时必须非空）
     * @param maxLength 字符串最大长度（`kind == STRING`）
     * @param allowEmpty 字符串是否允许空
     */
    data class Spec(
        val kind: Kind,
        val min: Float? = null,
        val max: Float? = null,
        val values: Set<String>? = null,
        val maxLength: Int = 4096,
        val allowEmpty: Boolean = false,
        /** 是否允许值内出现换行（`dockAppOrder` 用 `\n` 作分隔符，必须允许）。 */
        val allowNewline: Boolean = false,
        /** 自定义校验：返回 null = 通过。用于无法用上面几类表达的复合值。 */
        val custom: ((String) -> String?)? = null,
    )

    /** 布尔字段只接受这两个字面量 —— 与 `SharedPreferences.getBoolean` 的写入侧一致。 */
    private const val TRUE = "true"
    private const val FALSE = "false"

    // ---------------------------------------------------------------- 数值范围来源

    /** 设计画布宽（`ClusterDrawRegion.CANVAS_WIDTH` / 1920×720 口径）。 */
    private const val CANVAS_W = 1920f

    /** 设计画布高。 */
    private const val CANVAS_H = 720f

    /** 双盘缩放下限（`ClusterDialLayout.sanitize` 用 `coerceIn(0.2f, 3f)`，UI 滑块 0.4..1.6）。 */
    private const val DIAL_MIN_SCALE = 0.2f

    /** 双盘最小宽（`ClusterDrawRegion.MIN_WIDTH` = 480，等价缩放 0.25）。 */
    private const val DIAL_MIN_W = 480f

    /** 双盘最小高（`ClusterDrawRegion.MIN_HEIGHT` = 180）。 */
    private const val DIAL_MIN_H = 180f

    /** 视频缩放下限（`ClusterVideoTransform.MIN_SCALE`）。 */
    private const val MIN_SCALE = 0.5f

    /** 视频缩放上限（`ClusterVideoTransform.MAX_SCALE`）。 */
    private const val MAX_SCALE = 3f

    /** 平移上限 X（`ClusterVideoTransform.MAX_OFFSET_X`）。 */
    private const val MAX_OFFSET_X = 1920f

    /** 平移上限 Y（`ClusterVideoTransform.MAX_OFFSET_Y`）。 */
    private const val MAX_OFFSET_Y = 720f

    // ---------------------------------------------------------------- 契约表

    private val bool = Spec(Kind.BOOLEAN)

    /** 画布 X 坐标：0..1920。**不允许负值**。 */
    private val canvasX = Spec(Kind.FLOAT, 0f, CANVAS_W)

    /** 画布 Y 坐标：同上，0..720。 */
    private val canvasY = Spec(Kind.FLOAT, 0f, CANVAS_H)

    private val scale = Spec(Kind.FLOAT, MIN_SCALE, MAX_SCALE)

    /** 圆表缩放：下限是 0.2（`ClusterDialLayout` 的 sanitize），**不是**视频的 0.5。 */
    private val dialScale = Spec(Kind.FLOAT, DIAL_MIN_SCALE, MAX_SCALE)

    /** 主题 id / 包名列表 / HUD 裁剪描述，属于自由字符串。 */
    private val freeString = Spec(Kind.STRING, allowEmpty = false, maxLength = 512)

    /**
     * 键 → 契约。
     *
     * 未列出的键 = 契约未知 → **拒绝**（fail-closed）。新增白名单键必须同步加这里，
     * 否则该键的值会被判「无值域定义」而整份拒绝。
     */
    val SPECS: Map<String, Spec> = buildMap {
        // ---- 主题（dbplay_dial_layout / LIVE）
        put("clusterThemeId", freeString)

        // ---- 自绘区域（ClusterDrawRegion：1920×720，最小 480×180）
        put("draw_x", canvasX)
        put("draw_y", canvasY)
        put("draw_w", Spec(Kind.FLOAT, DIAL_MIN_W, CANVAS_W))
        put("draw_h", Spec(Kind.FLOAT, DIAL_MIN_H, CANVAS_H))

        // ---- 左右圆盘（ClusterDialLayout：dx/dy 允许负偏移，scale 0.5..3）
        listOf("left", "right").forEach { side ->
            put("${side}_dx", Spec(Kind.FLOAT, -MAX_OFFSET_X, MAX_OFFSET_X))
            put("${side}_dy", Spec(Kind.FLOAT, -MAX_OFFSET_Y, MAX_OFFSET_Y))
            put("${side}_scale", dialScale)
            put("${side}_visible", bool)
        }

        // ---- 视频变换（ClusterVideoTransform）
        put("video_scale", scale)
        put("video_dx", Spec(Kind.FLOAT, -MAX_OFFSET_X, MAX_OFFSET_X))
        put("video_dy", Spec(Kind.FLOAT, -MAX_OFFSET_Y, MAX_OFFSET_Y))

        // ---- 会话设置（LIVE_FIELDS 的 20 项）
        put("clusterFlip180", bool)
        put("keepHotspotAlive", bool)
        put("debugLogs", bool)
        put("hideTopBar", bool)
        put("hideBottomBar", bool)
        put("dockBar", bool)
        put("fullscreen", bool)
        put("hudGuidanceEnabled", bool)
        put("hudPushEnabled", bool)
        put("clusterAutoShowOnRoute", bool)
        put("localNavigationCardEnabled", bool)
        put("projectionEnabled", bool)
        put("hudProjectionEnabled", bool)
        put("safeAreaDrawOutside", bool)
        put("navigationAudioFocusEnabled", bool)
        put("immersiveGlassCardsEnabled", bool)
        put("immersiveGlassColor", Spec(Kind.INT, min = -1f, max = 16777215f))
        put("immersiveGlassTransparency", Spec(Kind.INT, min = 0f, max = 100f))
        put("immersiveNavigationEnabled", bool)
        put("immersiveNavigationCardEnabled", bool)
        put("immersiveNavigationMapLayout", bool)
        put("immersiveNavigationDrawOutside", bool)
        put("immersiveNavigationAlignment", Spec(Kind.INT, min = 0f, max = 2f))

        // ---- safeArea：`CarSessionSettings.kt:386 CarSettingsSafeArea(x/y/width/height: Int)`
        //   ⚠️ 曾经当成 0..200 的 Float —— 没有任何字符串编码能既表达矩形又过那个校验。
        //   生产存储是 4 个独立 int（SharedPreferencesCarSettingsStore），这里接受
        //   `x,y,width,height` 四元组；空串表示"未设置"（isEmpty 语义）。
        put(
            "safeArea",
            Spec(
                Kind.CUSTOM,
                allowEmpty = true,
                custom = { v ->
                    if (v.isEmpty()) {
                        null
                    } else {
                        val parts = v.split(",")
                        if (parts.size != 4) {
                            "safeArea 必须是 x,y,width,height 四元组"
                        } else {
                            val nums = parts.map { it.trim().toIntOrNull() }
                            when {
                                nums.any { it == null } -> "safeArea 四元组必须是整数"
                                nums[0]!! < 0 || nums[1]!! < 0 -> "safeArea 原点不得为负"
                                nums[2]!! < 0 || nums[3]!! < 0 -> "safeArea 宽高不得为负"
                                else -> null
                            }
                        }
                    }
                },
            ),
        )

        put("immersiveNavigationSafeArea", getValue("safeArea"))
        // ---- dockAppOrder：磁盘格式是 `\n` 分隔（SharedPreferencesCarSettingsStore:148/312）
        //   ⚠️ 曾经禁 `\n` 且禁空串 —— 用户有 0 个或 ≥2 个 dock 应用时必失败。
        put("dockAppOrder", Spec(Kind.STRING, allowEmpty = true, allowNewline = true, maxLength = 8192))

        // ---- hudCrop：HudCropSpec 四边分数，形如 `l,t,r,b` 或空
        put(
            "hudCrop",
            Spec(
                Kind.CUSTOM,
                allowEmpty = true,
                custom = { v ->
                    if (v.isEmpty()) {
                        null
                    } else {
                        val parts = v.split(",").map { it.trim() }
                        when {
                            parts.size != 4 -> "hudCrop 必须是 l,t,r,b 四元组"
                            parts.any { it.toFloatOrNull()?.isFinite() != true } -> "hudCrop 必须是有限数值"
                            else -> {
                                val f = parts.map { it.toFloat() }
                                when {
                                    f.any { it < 0f || it > 1f } -> "hudCrop 必须在 0..1"
                                    // ★ P1-6：生产 hudCrop 要求 right > left 且 bottom > top
                                    //   （`CarHudDisplay.kt:46-47` 抛 IAE；`HudCropGeometry.sanitizeHudCrop`
                                    //   在 r<=l||b<=t 时**静默回落**全画面 0,0,1,1）。
                                    //   配置层若放行，用户会看到 HUD 裁剪被悄悄重置且无报错。
                                    f[2] <= f[0] || f[3] <= f[1] ->
                                        "hudCrop 要求 right>left 且 bottom>top"
                                    else -> null
                                }
                            }
                        }
                    }
                },
            ),
        )

        listOf("clusterAppearanceMode", "uiAppearanceMode").forEach {
            put(it, Spec(Kind.ENUM, values = setOf("DARK", "LIGHT", "SYSTEM")))
        }
        put(
            "hudScaleMode",
            Spec(Kind.ENUM, values = setOf("FIT", "FILL", "STRETCH")),
        )
    }

    /**
     * 校验单个键值。
     *
     * @return `null` = 通过；否则为**不含敏感值本身**的拒绝原因
     */
    fun validate(key: String, value: String): String? {
        val spec = SPECS[key] ?: return "键无值域定义（fail-closed）: $key"
        return when (spec.kind) {
            Kind.BOOLEAN -> when (value) {
                TRUE, FALSE -> null
                else -> "布尔字段只接受 true/false: $key"
            }

            Kind.FLOAT -> {
                val f = value.toFloatOrNull()
                when {
                    f == null -> "数值字段解析失败: $key"
                    // NaN 走过所有比较都是 false，必须显式挡 —— stdlib 的
                    // Float.coerceIn 对 NaN 直接返回 NaN，下游 sanitize 也不挡。
                    f.isNaN() -> "数值不得为 NaN: $key"
                    f.isInfinite() -> "数值不得为无穷: $key"
                    spec.min != null && f < spec.min -> "$key 低于下限 ${spec.min}"
                    spec.max != null && f > spec.max -> "$key 超过上限 ${spec.max}"
                    else -> null
                }
            }

            Kind.INT -> {
                val i = value.toIntOrNull() ?: return "整数字段解析失败: $key"
                when {
                    spec.min != null && i < spec.min -> "$key 低于下限 ${spec.min.toInt()}"
                    spec.max != null && i > spec.max -> "$key 超过上限 ${spec.max.toInt()}"
                    else -> null
                }
            }

            Kind.ENUM -> {
                val allowed = spec.values ?: return "枚举字段缺少允许值: $key"
                if (value in allowed) null else "枚举值非法: $key"
            }

            Kind.STRING -> when {
                !spec.allowEmpty && value.isEmpty() -> "字符串字段不得为空: $key"
                value.length > spec.maxLength -> "字符串过长: $key"
                !spec.allowNewline && value.any { it == '\n' || it == '\r' } ->
                    "字符串不得含换行: $key"
                else -> null
            }

            // ⚠️ 不能写 `custom?.invoke(v) ?: "缺失"` —— custom 返回 null 表示**通过**，
            //   用 ?: 会把"通过"误判成"校验器缺失"。
            Kind.CUSTOM -> {
                val fn = spec.custom ?: return "自定义校验器缺失: $key"
                fn(value)
            }
        }
    }
}
