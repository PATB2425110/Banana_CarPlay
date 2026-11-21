package com.dbtool.carplay.bridge.bluetooth

/**
 * 解析 `BtLinkReleaseTool` 的 `DBPLAY-BT result=...` 标记行（纯函数）。
 *
 * ## 为什么必须"找不到 = 未知"，而不是成功
 *
 * `app_process` 的 stdout 里混着 JVM 噪声（`WARNING: linker...`、
 * `ClassNotFoundException`、`Aborted` 等）。工具若因加固包/类加载失败根本没跑起来，
 * 输出里**不会**有标记行。此时把"没解析到"当成成功，就会向用户谎报
 * 「蓝牙已断开」。所以 [parse] 返回 null 时，调用方必须按未知处理。
 *
 * 字段值里的空白由工具侧替换成 `_`（见 `BtLinkReleaseTool.sanitize`），
 * 这里可以安全地按空白分词。
 */
internal data class BtLinkReleaseResult(
    /** `probe` / `disconnected` / `failed` / `refused` / `forbidden` / `allowed` / ... */
    val result: String?,
    val connectedBefore: Boolean?,
    val connectedAfter: Boolean?,
    /** 适配器是否 `STATE_ON`（`adapter=ON/OFF`）。 */
    val adapterOn: Boolean?,
    /** `BluetoothAdapter.getState()` 的原始值（12 = STATE_ON）。 */
    val adapterState: Int?,
    val policyForbidden: Boolean?,
    val uid: Int?,
    /** `ok` / `hidden-api-blocked` / `security` / `invoke-error`。 */
    val disconnectMethod: String?,
    /** 失败分类（`NoSuchMethodException` / `SecurityException` / ...），无异常时为 null。 */
    val exception: String?,
) {
    /**
     * 只有「明确结果是 disconnected **且** 复查未连接 **且** 适配器仍 ON」才算成功。
     * 缺任何一项都返回 false（未知 ≠ 成功）。
     */
    val released: Boolean
        get() = result == "disconnected" && connectedAfter == false && adapterOn == true

    companion object {

        /**
         * 从混杂输出里取**最后一条**带 `result=` 的 `DBPLAY-BT` 标记行。
         * 没有标记行 / 没有 result 字段时返回 null（调用方按未知处理）。
         */
        fun parse(output: String): BtLinkReleaseResult? {
            val line = output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains("DBPLAY-BT") && it.contains("result=") }
                ?: return null
            val fields = line.split(Regex("\\s+"))
                .mapNotNull { token ->
                    val index = token.indexOf('=')
                    if (index <= 0) null else token.substring(0, index) to token.substring(index + 1)
                }
                .toMap()
            if (fields["result"].isNullOrEmpty()) return null
            fun flag(key: String): Boolean? = when (fields[key]) {
                "true" -> true
                "false" -> false
                else -> null
            }
            val adapterOn = when (fields["adapter"]) {
                "ON" -> true
                "OFF" -> false
                else -> flag("adapterOn")
            }
            return BtLinkReleaseResult(
                result = fields["result"],
                connectedBefore = flag("connectedBefore"),
                connectedAfter = flag("connectedAfter"),
                adapterOn = adapterOn,
                adapterState = fields["adapterState"]?.toIntOrNull(),
                policyForbidden = flag("policyForbidden"),
                uid = fields["uid"]?.toIntOrNull(),
                disconnectMethod = fields["disconnectMethod"],
                exception = fields["exception"]?.takeIf { it != "none" },
            )
        }
    }
}
