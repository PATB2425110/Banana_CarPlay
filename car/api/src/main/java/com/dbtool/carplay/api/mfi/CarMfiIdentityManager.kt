package com.dbtool.carplay.api.mfi

import android.net.Uri

/**
 * 本地 MFi 配件身份的供给与自定义。
 *
 * ## 为什么需要这个契约
 *
 * `CarMfiTarget.LOCAL_OFFLINE` 要能工作，必须有一份 Apple 配件身份
 * （P-256 私钥 + 配件证书）。身份有两条来源：
 *
 * 1. **随 APK 内置**（`assets/offline-mfi/`）——开箱即用；
 * 2. **用户导入**——把 `identity.pk8` + `certificate.p7b` 放到应用私有目录，
 *    优先级高于内置，方便用户换成自己的配件身份。
 *
 * 第 2 条要出现在设置页，而 UI 只允许依赖 `:car:api`，
 * 具体实现（含文件校验、staging 安装、权限收紧）在 `:car:core-bridge`。
 * 所以这里只放契约。
 *
 * ## 为什么导入是"两个文件一起"
 *
 * 私钥与证书必须配对（同一个公钥）。分两次导入会存在"换了私钥还没换证书"
 * 的中间态，此时签名校验必然失败，而失败点在 iPhone 侧，
 * 本机看不到任何线索。所以 [importIdentity] 一次收两个 Uri，一起校验、一起就位。
 */
interface CarMfiIdentityManager {

    /** 当前身份状态，供设置页展示。 */
    fun status(): CarMfiIdentityStatus

    /**
     * 导入用户自定义身份。
     *
     * **校验失败时不会破坏现有身份**：用户拿错文件不应该把能用的身份弄坏。
     *
     * @return 导入后的状态。失败时 [CarMfiIdentityStatus.available] 为 false，
     *         [CarMfiIdentityStatus.detail] 是可直接显示的中文原因。
     */
    fun importIdentity(identityUri: Uri, certificateUri: Uri): CarMfiIdentityStatus

    /**
     * 删除用户导入的身份，退回随包内置版本。
     *
     * @return true 表示确实删掉了导入的身份；false 表示本来就没有导入过。
     */
    fun clearImported(): Boolean
}

/**
 * 身份状态快照。
 *
 * 刻意做成扁平的可显示模型，而不是把 `Availability` 直接抛给 UI：
 * UI 不需要知道"载入失败"和"文件缺失"的区别，只需要知道
 * **能不能用、用的是哪一份、指纹是什么**。
 */
data class CarMfiIdentityStatus(
    /** 身份是否可用。false 时选中 `LOCAL_OFFLINE` 会以一条可读错误告终。 */
    val available: Boolean = false,

    /**
     * 来源标签，直接显示。
     * 可用时是"随包内置"/"用户导入"，不可用时为空串。
     */
    val sourceLabel: String = "",

    /** 一句话说明。可用时是配件证书的指纹摘要，不可用时是失败原因。 */
    val detail: String = "",

    /** 当前是否用的是用户导入的身份（决定设置页显示"清除导入"按钮）。 */
    val imported: Boolean = false,

    /** 身份是否来自随包内置的兜底。 */
    val bundled: Boolean = false,

    /**
     * 公钥指纹（SHA-256 hex，前 16 位）。
     *
     * 用于让用户确认"现在生效的是哪一份身份"。不是机密——
     * 指纹是公钥的摘要，本身就印在证书里。
     */
    val fingerprint: String? = null,
)
