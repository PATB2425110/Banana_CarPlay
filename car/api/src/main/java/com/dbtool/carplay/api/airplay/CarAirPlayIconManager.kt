package com.dbtool.carplay.api.airplay

import android.net.Uri

/**
 * AirPlay（CarPlay 图标）自定义图标的供给与自定义。
 *
 * ## 这是什么
 *
 * AirPlay 的 `/info` 响应里可以带一组 `oemIcons`。iPhone 在 CarPlay 启动器
 * 与 AirPlay 菜单里就用它来显示车机的品牌图标。上游在设置页提供了
 * "AirPlay icon → 从文件选择一张图片"，二开版必须提供等价能力，
 * 否则用户只能看到占位图。
 *
 * ## 为什么契约放在 `:car:api`
 *
 * 设置页（`:car:ui`）要展示预览、要触发导入，而 UI 只允许依赖 `:car:api`；
 * 真正的实现（资源读取、正方形裁剪、落盘）在 `:car:core-bridge`。
 * 与 [com.dbtool.carplay.api.mfi.CarMfiIdentityManager] 同一套思路。
 *
 * ## 两条硬约束
 *
 * 1. **必须是正方形**。上游 `CarPlayHostActivity.decodeAirPlayIcon()` 在
 *    `outWidth != outHeight` 时直接丢弃图片，回落占位图——用户选了图却"没生效"，
 *    而且没有任何提示。所以这里由实现负责**居中裁剪成正方形**，不让用户踩。
 * 2. **导入失败不得破坏现有图标**。与 MFi 身份同理：用户拿错文件不该把
 *    本来能用的图标弄坏。
 */
interface CarAirPlayIconManager {

    /** 当前生效的图标状态，供设置页展示（含预览）。 */
    fun status(): CarAirPlayIconStatus

    /**
     * 导入用户自定义图标。
     *
     * @return 导入后的状态。失败时 [CarAirPlayIconStatus.available] 描述的是
     *         **当前仍在生效的那一份**，失败原因在 [CarAirPlayIconStatus.detail]。
     */
    fun importIcon(iconUri: Uri): CarAirPlayIconStatus

    /**
     * 删除用户导入的图标，退回随包内置版本。
     *
     * @return true 表示确实删掉了导入的图标；false 表示本来就没有导入过。
     */
    fun clearImported(): Boolean
}

/**
 * 图标状态快照。
 *
 * 刻意**不是 `data class`**：[previewPng] 是字节数组，`data class` 自动生成的
 * `equals` 会退化成引用比较，每次刷新都判"不相等"，反而给出一个误导性的语义。
 * 需要相等性判断的地方请比较 [widthPixels] / [imported] / [detail] 这些字段。
 */
class CarAirPlayIconStatus(
    /** 是否有一份可用图标。false 时 CarPlay 侧会退回占位图。 */
    val available: Boolean = false,

    /** 来源标签，直接显示："随包内置" / "用户导入"。 */
    val sourceLabel: String = "",

    /** 一句话说明：可用时是尺寸信息，不可用时是失败原因。 */
    val detail: String = "",

    /** 当前是否用的是用户导入的图标（决定设置页显示"恢复默认"）。 */
    val imported: Boolean = false,

    /** 当前是否用的是随包内置图标。 */
    val bundled: Boolean = false,

    val widthPixels: Int = 0,
    val heightPixels: Int = 0,

    /** 预览用的 PNG 字节，UI 侧自行解码。可能为 null（不可用或读取失败）。 */
    val previewPng: ByteArray? = null,
)
