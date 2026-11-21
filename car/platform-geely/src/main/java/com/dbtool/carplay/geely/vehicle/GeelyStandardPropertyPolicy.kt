package com.dbtool.carplay.geely.vehicle

/** Snapshot of the PUBLIC CarPropertyConfig, retained as provenance rather than guessed support. */
internal data class GeelyStandardPropertyConfig(
    val propertyId: Int,
    val access: Int,
    val propertyTypeName: String,
    val areaType: Int,
    val isGlobal: Boolean,
    val areaIds: List<Int>,
    val changeMode: Int,
)

internal data class GeelyStandardPropertySelection(
    val managerSignalId: Int,
    val halPropertyId: Int,
    val areaId: Int,
    val config: GeelyStandardPropertyConfig,
)

internal data class GeelyStandardRawProperty(
    val propertyId: Int,
    val areaId: Int,
    val status: Int,
    /** Actual CarPropertyValue.getTimestamp(), HAL elapsed-realtime nanoseconds. */
    val timestampNanos: Long,
    val value: Any?,
)

internal data class GeelyStandardPropertyOrigin(
    val selection: GeelyStandardPropertySelection,
    /** Original HAL time, never replaced with our successful read/receipt time. */
    val actualHalTimestampNanos: Long,
    val validatedAtNanos: Long,
    val previousTimestampNanos: Long?,
)

internal object GeelyStandardPropertyPolicy {
    const val MAX_CONTINUOUS_SAMPLE_AGE_NANOS = 3_000_000_000L
    const val STATIC = 0
    const val ON_CHANGE = 1
    const val CONTINUOUS = 2

    /**
     * Exact RECEIVED pairs from ECarXCarService PropertyIdTransformation.java:9,
     * mHaltoMgrPropIdMap (OSN2.0), independently extracted to android-vendor-six-hal-mappings.json.
     * These are NOT computed as a bitwise prefix. Unknown runtime versions have no mapping.
     */
    private val halIds = mapOf(
        31062 to 557873494, // 0x21407956, pair 1360: fuel range
        31063 to 557873495, // 0x21407957, pair 1361: DstUnit
        30634 to 557873066, // 0x214077AA, pair 1008: ambient temperature
        30635 to 557873067, // 0x214077AB, pair 1009: ambient quality
        31021 to 557873453, // 0x2140792D, pair 1319: low fuel
        30619 to 557873051, // 0x2140779B, pair 993: TwliBriSts
    )

    fun halId(signal: GeelyVehicleSignal, runtimeManagerId: Int?): Int? =
        runtimeManagerId?.takeIf { it == signal.expectedId }?.let(halIds::get)

    fun select(
        signal: GeelyVehicleSignal,
        runtimeManagerId: Int?,
        config: GeelyStandardPropertyConfig?,
    ): GeelyStandardPropertySelection? {
        val halId = halId(signal, runtimeManagerId) ?: return null
        if (config == null || config.propertyId != halId ||
            config.access !in listOf(1, 3) || // VehiclePropertyAccess READ / READ_WRITE
            config.propertyTypeName != "java.lang.Integer" || config.changeMode !in STATIC..CONTINUOUS
        ) return null
        val area = areaFor(config) ?: return null
        return GeelyStandardPropertySelection(runtimeManagerId!!, halId, area, config)
    }

    private fun areaFor(config: GeelyStandardPropertyConfig): Int? {
        if (config.isGlobal != (config.areaType == 0)) return null
        return if (config.isGlobal) {
            // Public global properties use area 0; reject contradictory explicit area configs.
            0.takeIf { config.areaIds.isEmpty() || config.areaIds == listOf(0) }
        } else {
            // These whole-vehicle inputs have no policy for choosing among multiple zones.
            config.areaIds.singleOrNull()?.takeIf { config.areaType in 2..6 && it > 0 }
        }
    }

    fun timestampValid(timestamp: Long, now: Long, previous: Long?, changeMode: Int): Boolean =
        // Zero is not documented as a valid static-unit timestamp in this captured contract.
        // Even STATIC therefore needs a positive non-future time; no zero exception is invented.
        timestamp > 0 && now >= timestamp && changeMode in STATIC..CONTINUOUS &&
            (changeMode != CONTINUOUS || now - timestamp <= MAX_CONTINUOUS_SAMPLE_AGE_NANOS) &&
            (previous == null || timestamp >= previous)

    fun reading(
        selection: GeelyStandardPropertySelection,
        raw: GeelyStandardRawProperty?,
        nowNanos: Long,
        previousTimestampNanos: Long?,
    ): GeelyVehicleReading? {
        if (raw == null || raw.propertyId != selection.halPropertyId ||
            raw.areaId != selection.areaId || raw.status != 0 || raw.value !is Int ||
            !timestampValid(raw.timestampNanos, nowNanos, previousTimestampNanos, selection.config.changeMode)
        ) return null
        val origin = GeelyStandardPropertyOrigin(
            selection, raw.timestampNanos, nowNanos, previousTimestampNanos,
        )
        if (!originMatches(origin, selection.managerSignalId, raw.propertyId, raw.areaId)) return null
        return GeelyVehicleReading(
            selection.managerSignalId,
            GeelyVehicleProperty(
                // Preserve the ACTUAL raw header. Normalization lives on Reading and Origin.
                propertyId = raw.propertyId,
                areaId = raw.areaId,
                value = raw.value,
                receiptTimeMillis = nowNanos / 1_000_000L,
                hasStatus = true,
                status = raw.status,
                standardOrigin = origin,
            ),
        )
    }

    fun originMatches(
        origin: GeelyStandardPropertyOrigin,
        managerId: Int,
        rawPropertyId: Int,
        rawAreaId: Int,
    ): Boolean {
        val selection = origin.selection
        return selection.managerSignalId == managerId &&
            halIds[managerId] == rawPropertyId && selection.halPropertyId == rawPropertyId &&
            selection.config.propertyId == rawPropertyId &&
            selection.config.access in listOf(1, 3) &&
            selection.config.propertyTypeName == "java.lang.Integer" &&
            areaFor(selection.config) == rawAreaId && selection.areaId == rawAreaId &&
            timestampValid(
                origin.actualHalTimestampNanos, origin.validatedAtNanos,
                origin.previousTimestampNanos, selection.config.changeMode,
            )
    }
}

/** Public getter decoder, separate from Android objects so missing/wrong metadata is JVM-testable. */
internal object GeelyStandardPropertyDecoder {
    fun config(raw: Any): GeelyStandardPropertyConfig? = GeelyVehicleReadFailures.orUnknown {
        val type = raw.javaClass
        val id = type.getMethod("getPropertyId").invoke(raw) as? Int ?: return null
        val access = type.getMethod("getAccess").invoke(raw) as? Int ?: return null
        val propertyType = type.getMethod("getPropertyType").invoke(raw) as? Class<*> ?: return null
        val areaType = type.getMethod("getAreaType").invoke(raw) as? Int ?: return null
        val global = type.getMethod("isGlobalProperty").invoke(raw) as? Boolean ?: return null
        val areas = type.getMethod("getAreaIds").invoke(raw) as? IntArray ?: return null
        val changeMode = type.getMethod("getChangeMode").invoke(raw) as? Int ?: return null
        GeelyStandardPropertyConfig(id, access, propertyType.name, areaType, global, areas.toList(), changeMode)
    }

    fun property(raw: Any): GeelyStandardRawProperty? = GeelyVehicleReadFailures.orUnknown {
        val type = raw.javaClass
        GeelyStandardRawProperty(
            type.getMethod("getPropertyId").invoke(raw) as? Int ?: return null,
            type.getMethod("getAreaId").invoke(raw) as? Int ?: return null,
            type.getMethod("getStatus").invoke(raw) as? Int ?: return null,
            type.getMethod("getTimestamp").invoke(raw) as? Long ?: return null,
            type.getMethod("getValue").invoke(raw),
        )
    }
}

/** No creation/connection action can run without an affirmative existing permission check. */
internal class GeelyStandardPermissionGate(
    private val granted: () -> Boolean,
    private val onDenied: () -> Unit,
) {
    private var reported = false

    fun <T> withPermission(action: () -> T): T? {
        if (GeelyVehicleReadFailures.orUnknown(granted) != true) {
            if (!reported) {
                reported = true
                runCatching(onDenied)
            }
            return null
        }
        return action()
    }
}
