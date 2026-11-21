package com.dbtool.carplay.api.display

/** 产品模式在构造期固定，不随每次 `/info` 解析变化。 */
enum class AltScreenProductMode {
    /** 本立项唯一开放模式。 */
    SHARED_111_ONLY,

    /** 未来产品模式；本立项不接线、不开放 UI。 */
    HUD_ONLY_ALLOWED,
}

sealed interface AltScreenSlotDecision {
    data object None : AltScreenSlotDecision

    data class ClusterOnly(val spec: CarClusterSpec) : AltScreenSlotDecision

    data class Shared111(val spec: CarClusterSpec) : AltScreenSlotDecision

    data class HudOnly(val spec: CarClusterSpec) : AltScreenSlotDecision

    data class Conflict(val reason: String) : AltScreenSlotDecision
}

/**
 * ALT(111) 槽位纯策略。
 *
 * [decide] 的 `clusterProjectionRequested` 只记录产品是否请求仪表投屏，
 * 不用于推断 ALT 槽是否为空。当前协议只有一个 111 内容来源，因此 HUD 请求
 * 独立 111 时默认复用 `clusterSpec`（Shared111）。
 */
class AltScreenSlotPolicy(
    private val productMode: AltScreenProductMode = AltScreenProductMode.SHARED_111_ONLY,
) {
    @Suppress("UNUSED_PARAMETER")
    fun decide(
        clusterProjectionRequested: Boolean,
        hudIndependentRequested: Boolean,
        hudSpecKnown: Boolean,
        clusterSpec: CarClusterSpec?,
    ): AltScreenSlotDecision {
        if (!hudIndependentRequested) {
            return clusterSpec?.let(AltScreenSlotDecision::ClusterOnly)
                ?: AltScreenSlotDecision.None
        }

        return when (productMode) {
            AltScreenProductMode.SHARED_111_ONLY ->
                clusterSpec?.let(AltScreenSlotDecision::Shared111)
                    ?: AltScreenSlotDecision.Conflict(REASON_CLUSTER_SPEC_UNAVAILABLE)

            AltScreenProductMode.HUD_ONLY_ALLOWED -> when {
                !hudSpecKnown -> AltScreenSlotDecision.Conflict(REASON_HUD_SPEC_NOT_READY)
                clusterSpec != null -> AltScreenSlotDecision.HudOnly(clusterSpec)
                else -> AltScreenSlotDecision.Conflict(REASON_CLUSTER_SPEC_UNAVAILABLE)
            }
        }
    }

    companion object {
        const val REASON_CLUSTER_SPEC_UNAVAILABLE = "CLUSTER_SPEC_UNAVAILABLE"
        const val REASON_HUD_SPEC_NOT_READY = "HUD_SPEC_NOT_READY"
    }
}
