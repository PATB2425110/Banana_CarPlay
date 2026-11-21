package com.dbtool.carplay.bridge.vehicle

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot

/**
 * `PA_VF_HUD_HudRstForSetgAndData` 10 字节载荷里的**可映射字段**。
 *
 * ⚠️ **只列 DBPlay 已经有的输入，不猜厂商位域含义**。
 * 路名 / 目的地是字符串，本工程尚未掌握它们在 10 字节里的编码方式，
 * 因此**刻意不列**任何 `ROAD_*` 项 —— 宁可缺，不许编。
 */
enum class HudPayloadField {
    /** `CarNavigationSnapshot.maneuverType`（原始转向码）。 */
    MANEUVER_TYPE,

    /** `CarNavigationSnapshot.segmentMeters`（到下一转向距离，米）。 */
    SEGMENT_METERS,

    /** `CarNavigationSnapshot.routeMeters`（剩余总距离，米）。 */
    ROUTE_METERS,

    /** `CarNavigationSnapshot.routeSeconds`（剩余总时间，秒）。 */
    ROUTE_SECONDS,
}

/**
 * 一个位域在 10 字节载荷中的位置。
 *
 * ## 位序（**未验证**）
 *
 * 这里采用最直观的 **LSB-first**：`bitOffset=0` 是该字节的最低位，
 * `bitWidth` 超过 `8-bitOffset` 时向后续字节延续。厂商真实位序
 * （LSB/MSB、大小端、是否有符号）**全部未知**，实车试错后若发现相反，
 * 只需在 [HudPayloadSpec] 里换 `bitOffset` 组合，不必改编解码器。
 *
 * 默认 [HudPayloadSpec.DEFAULT] 不映射任何字段 ⇒ 输出恒为 10 个 `0x00`。
 */
data class HudBitField(
    val byteIndex: Int,
    val bitOffset: Int,
    val bitWidth: Int,
) {
    init {
        require(byteIndex in 0 until HudPayloadCodec.PAYLOAD_BYTES) {
            "byteIndex 越界：$byteIndex（载荷 ${HudPayloadCodec.PAYLOAD_BYTES} 字节）"
        }
        require(bitOffset in 0..7) { "bitOffset 必须在 0..7：$bitOffset" }
        require(bitWidth in 1..32) { "bitWidth 必须在 1..32：$bitWidth" }
        val endBit = byteIndex * BITS_PER_BYTE + bitOffset + bitWidth
        require(endBit <= HudPayloadCodec.PAYLOAD_BYTES * BITS_PER_BYTE) {
            "位域超出 ${HudPayloadCodec.PAYLOAD_BYTES} 字节载荷：结束位 $endBit"
        }
    }

    private companion object {
        const val BITS_PER_BYTE = 8
    }
}

/**
 * 可配置的位域映射表。
 *
 * ## 当前状态：**默认全 0，待实车试错**
 *
 * 10 字节里每个 bit 的厂商语义未知，HudBridge 也从未调用过
 * `setHudRstForSetgAndData`（它只调 `CB_VF_HUD_ActvReq`）。因此本工程
 * **不猜**任何布局：
 *
 * - [DEFAULT] 的 [fields] 为空 ⇒ [HudPayloadCodec.encode] 输出全 0；
 * - 实车确认某字段的字节/位/宽度后，在调用方传入本对象即可，
 *   **不要**改默认值（否则所有车型一起变）；
 * - 新增映射时请同时补 `HudPayloadCodecTest`，把「哪个字段写到了哪一位」钉住。
 *
 * [fields] 里同一个 [HudBitField] 不应被两个字段共用；共用时后写入者覆盖先写入者
 * （Map 迭代顺序），本类不做冲突检测 —— 位域布局本来就是待试错项。
 */
data class HudPayloadSpec(
    val fields: Map<HudPayloadField, HudBitField> = emptyMap(),
) {
    companion object {
        /**
         * 出厂默认：不映射任何字段，10 字节全 `0x00`。
         *
         * TODO(实车试错 2026-10-22)：厂商位域含义未知。实车逐位确认后，
         * 由调用方显式传入映射表；**不要**改这个默认值 —— 否则所有车型一起变。
         */
        val DEFAULT = HudPayloadSpec()
    }
}

/**
 * `PA_VF_HUD_HudRstForSetgAndData` 的 10 字节载荷（`vfhudbyte0..vfhudbyte9`）。
 *
 * 不可变；[toByteArray] 返回副本，调用方随便改都不会污染本对象。
 */
class HudPayload private constructor(private val bytes: ByteArray) {

    val vfhudbyte0: Int get() = bytes[0].toInt() and 0xFF
    val vfhudbyte1: Int get() = bytes[1].toInt() and 0xFF
    val vfhudbyte2: Int get() = bytes[2].toInt() and 0xFF
    val vfhudbyte3: Int get() = bytes[3].toInt() and 0xFF
    val vfhudbyte4: Int get() = bytes[4].toInt() and 0xFF
    val vfhudbyte5: Int get() = bytes[5].toInt() and 0xFF
    val vfhudbyte6: Int get() = bytes[6].toInt() and 0xFF
    val vfhudbyte7: Int get() = bytes[7].toInt() and 0xFF
    val vfhudbyte8: Int get() = bytes[8].toInt() and 0xFF
    val vfhudbyte9: Int get() = bytes[9].toInt() and 0xFF

    fun toByteArray(): ByteArray = bytes.copyOf()

    /** 诊断用十六进制串，如 `00 01 00 …`。 */
    fun toHex(): String = bytes.joinToString(" ") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is HudPayload && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "HudPayload(${toHex()})"

    internal companion object {
        fun of(bytes: ByteArray): HudPayload {
            require(bytes.size == HudPayloadCodec.PAYLOAD_BYTES) {
                "HUD 载荷必须是 ${HudPayloadCodec.PAYLOAD_BYTES} 字节，实际 ${bytes.size}"
            }
            return HudPayload(bytes)
        }
    }
}

/**
 * 把 [CarNavigationSnapshot] 编码成 `PA_VF_HUD_HudRstForSetgAndData` 的 10 字节。
 *
 * ## 边界（2026-10-22 明确不做）
 *
 * - **不调用** `setHudRstForSetgAndData`：位域未知，先只提供纯函数编码；
 *   等实车试错确认布局后再接车控接口（届时由调用方显式开关控制）。
 * - **不冒充百度 LBS**、**不做 display 3 投屏**。
 *
 * ## 未知位域的处理原则
 *
 * 宁可全 0 + TODO，不瞎编含义：
 * - [HudPayloadSpec.DEFAULT] 不映射任何字段；
 * - 未映射的位保持 `0`；
 * - 输入为 `null` 的字段保持 `0`；
 * - 数值按无符号处理并夹到 `[0, 2^bitWidth-1]`；负值夹成 0（有符号语义未知，
 *   实车确认后再扩展，不在这里猜）。
 */
object HudPayloadCodec {

    /** `vfhudbyte0..9` 的固定长度。 */
    const val PAYLOAD_BYTES = 10

    private const val BITS_PER_BYTE = 8

    /** 用快照里的对应字段编码。未映射/空值位保持 0。 */
    fun encode(
        snapshot: CarNavigationSnapshot?,
        spec: HudPayloadSpec = HudPayloadSpec.DEFAULT,
    ): HudPayload = encode(
        values = mapOf(
            HudPayloadField.MANEUVER_TYPE to snapshot?.maneuverType,
            HudPayloadField.SEGMENT_METERS to snapshot?.segmentMeters,
            HudPayloadField.ROUTE_METERS to snapshot?.routeMeters,
            HudPayloadField.ROUTE_SECONDS to snapshot?.routeSeconds,
        ),
        spec = spec,
    )

    /** 直接给字段值编码（单测/试错用，避免为了一个位域构造完整快照）。 */
    fun encode(
        values: Map<HudPayloadField, Int?>,
        spec: HudPayloadSpec = HudPayloadSpec.DEFAULT,
    ): HudPayload {
        val bytes = ByteArray(PAYLOAD_BYTES)
        for ((field, bitField) in spec.fields) {
            val value = values[field] ?: continue
            writeBits(bytes, bitField, value)
        }
        return HudPayload.of(bytes)
    }

    private fun writeBits(target: ByteArray, field: HudBitField, value: Int) {
        val maxValue = if (field.bitWidth == 32) {
            0xFFFF_FFFFL
        } else {
            (1L shl field.bitWidth) - 1L
        }
        val clamped = value.toLong().coerceIn(0L, maxValue)
        for (bit in 0 until field.bitWidth) {
            if ((clamped ushr bit) and 1L == 0L) continue
            val absoluteBit = field.byteIndex * BITS_PER_BYTE + field.bitOffset + bit
            val byteIndex = absoluteBit / BITS_PER_BYTE
            val bitInByte = absoluteBit % BITS_PER_BYTE
            target[byteIndex] = (target[byteIndex].toInt() or (1 shl bitInByte)).toByte()
        }
    }
}
