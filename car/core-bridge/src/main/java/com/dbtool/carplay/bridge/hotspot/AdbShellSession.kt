package com.dbtool.carplay.bridge.hotspot

import android.content.Context

/** Fail-closed compatibility boundary: this product contains no ADB session backend.
 * Old diagnostic types remain so non-ADB application logging can degrade safely. */
internal class AdbShellSession(appContext: Context?) {
    data class Step(val title: String, val ok: Boolean, val detail: String, val command: String = "")
    data class Access(val shellOk: Boolean, val prefix: String?, val steps: List<Step>, val failure: String?) {
        val rooted: Boolean get() = prefix != null
        val prefixOrEmpty: String get() = prefix.orEmpty()
    }
    data class InitializationResult(val access: Access, val currentPortConfigured: Boolean,
        val persistentPortVerified: Boolean, val detail: String, val reusedExisting: Boolean = false)
    data class RepairOutcome(val attempted: Boolean, val reason: String, val init: InitializationResult? = null)
    private fun unavailable() = Access(false, null, emptyList(), "此产品已移除ADB功能")
    val runner: AdbShellRunner get() = throw UnsupportedOperationException("ADB backend removed")
    fun initializeDedicatedPort() = InitializationResult(unavailable(), false, false, "ADB backend removed")
    fun repairDedicatedPort() = RepairOutcome(false, "ADB backend removed")
    fun ensure(force: Boolean = false): Access = unavailable()
    fun checkDedicatedPort(): Access = unavailable()
    fun ensureRootForHotspot(): Access = unavailable()
    fun invalidate() = Unit
    fun open(owner: String) = Unit
    fun close(owner: String) = Unit
    fun releaseAll() = Unit
    val ownerCount: Int get() = 0
    val hasTransientOwner: Boolean get() = false
    fun peek(): Access? = null
    fun release() = Unit
}
