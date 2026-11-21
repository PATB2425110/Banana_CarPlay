package com.dbtool.carplay.bridge.vehicle

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 10 字节 HUD 载荷编码器。
 *
 * ⚠️ 位域语义**尚未实车验证**，这些用例只钉住三件事：
 * 1. 默认映射必须输出全 0（不猜语义）；
 * 2. 配置了映射表时，字节/位/宽度的写入方式与文档一致；
 * 3. 越界输入不产生越界写入。
 */
class HudPayloadCodecTest {

    @Test
    fun `默认映射输出 10 字节全零`() {
        val snapshot = CarNavigationSnapshot(
            maneuverType = 12,
            segmentMeters = 300,
            routeMeters = 12_000,
            routeSeconds = 900,
        )

        val payload = HudPayloadCodec.encode(snapshot)

        assertArrayEquals(ByteArray(10), payload.toByteArray())
        assertEquals(0, payload.vfhudbyte0)
        assertEquals(0, payload.vfhudbyte5)
        assertEquals(0, payload.vfhudbyte9)
        assertEquals("00 00 00 00 00 00 00 00 00 00", payload.toHex())
    }

    @Test
    fun `配置映射后按 LSB-first 写入对应字节`() {
        val spec = HudPayloadSpec(
            fields = mapOf(
                HudPayloadField.MANEUVER_TYPE to HudBitField(byteIndex = 0, bitOffset = 0, bitWidth = 4),
                HudPayloadField.SEGMENT_METERS to HudBitField(byteIndex = 1, bitOffset = 0, bitWidth = 8),
                HudPayloadField.ROUTE_METERS to HudBitField(byteIndex = 2, bitOffset = 0, bitWidth = 16),
            ),
        )
        val snapshot = CarNavigationSnapshot(
            maneuverType = 5,
            segmentMeters = 200,
            routeMeters = 1234,
        )

        val payload = HudPayloadCodec.encode(snapshot, spec)

        assertEquals(0x05, payload.vfhudbyte0)
        assertEquals(0xC8, payload.vfhudbyte1) // 200
        assertEquals(0xD2, payload.vfhudbyte2) // 1234 = 0x04D2, LSB 在前
        assertEquals(0x04, payload.vfhudbyte3)
        assertEquals(0, payload.vfhudbyte4)
    }

    @Test
    fun `位域可以跨字节`() {
        val spec = HudPayloadSpec(
            fields = mapOf(
                HudPayloadField.MANEUVER_TYPE to HudBitField(byteIndex = 8, bitOffset = 6, bitWidth = 4),
            ),
        )

        val payload = HudPayloadCodec.encode(mapOf(HudPayloadField.MANEUVER_TYPE to 0xF), spec)

        assertEquals(0xC0, payload.vfhudbyte8) // byte8 bit6..7 = 低 2 位
        assertEquals(0x03, payload.vfhudbyte9) // 高 2 位延续到 byte9 bit0..1
    }

    @Test
    fun `空值字段保持 0_不把 null 当 0 之外的东西`() {
        val spec = HudPayloadSpec(
            fields = mapOf(
                HudPayloadField.ROUTE_SECONDS to HudBitField(byteIndex = 4, bitOffset = 0, bitWidth = 16),
            ),
        )

        val payload = HudPayloadCodec.encode(CarNavigationSnapshot(routeSeconds = null), spec)

        assertArrayEquals(ByteArray(10), payload.toByteArray())
    }

    @Test
    fun `数值按无符号夹取_负值归零_超上限取最大值`() {
        val spec = HudPayloadSpec(
            fields = mapOf(
                HudPayloadField.MANEUVER_TYPE to HudBitField(byteIndex = 0, bitOffset = 0, bitWidth = 4),
                HudPayloadField.SEGMENT_METERS to HudBitField(byteIndex = 1, bitOffset = 0, bitWidth = 8),
            ),
        )

        val payload = HudPayloadCodec.encode(
            values = mapOf(
                HudPayloadField.MANEUVER_TYPE to -7,
                HudPayloadField.SEGMENT_METERS to 999,
            ),
            spec = spec,
        )

        assertEquals(0x00, payload.vfhudbyte0)
        assertEquals(0xFF, payload.vfhudbyte1)
    }

    @Test
    fun `越界的位域在构造时就拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudBitField(byteIndex = 10, bitOffset = 0, bitWidth = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HudBitField(byteIndex = 9, bitOffset = 6, bitWidth = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HudBitField(byteIndex = 0, bitOffset = 8, bitWidth = 1)
        }
    }

    @Test
    fun `toByteArray 返回副本_外部修改不污染载荷`() {
        val payload = HudPayloadCodec.encode(CarNavigationSnapshot())

        val copy = payload.toByteArray()
        copy[0] = 0x7F

        assertEquals(0, payload.vfhudbyte0)
    }
}
