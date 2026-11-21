package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0111 / PATCH-0112：识别帧尾部的 **RGD（Route Guidance Display）组件宣告**。
 *
 * ## 为什么这 86 字节值得单独一个测试类
 *
 * 它是整条 HUD 链路的**咽喉**：iPhone 只有在识别帧里看到组件本体（参数 id `0x1E`、
 * componentId `0x2A`=42）才会真正开始下发 `0x5201/0x5202` 导航帧。现场日志链是
 * `RGD 0x1E appended (componentId=0x2a)` → `identify: ACCEPTED by iPhone`
 * → `iAP2 frame msgId=20993/20994` 开始下发。
 *
 * 也就是说：**这 86 字节错一位，HUD 桥能绑上、但永远收不到任何帧**
 * ——表现与"车机没装 hudbridge 服务"一模一样，而日志里看不出区别。
 * 这种故障只能在车上花一轮成本去发现，所以用单测把字节钉死。
 *
 * ## 断言分三层（不是只对一遍 hex）
 *
 * 1. **结构**：参数确实存在、是最后一个、长度自洽（`4 + body == 0x56`）；
 * 2. **语义**：内层 TLV 解出来的 componentId / 组件名 / 各 Max 长度字段；
 * 3. **黄金向量**：整段 86 字节与现场补丁的生成器输出**逐字节一致**。
 *
 * 第 3 层的向量取自 dbplay 现场补丁的生成器 `gen_rgd.py`
 * （`tlv(0x001E, inner)`，`name = b"RouteGuidanceDisplayComponent\x00"`），
 * 是**独立于本项目实现**重新算出来的 —— 所以它能抓到"有人顺手重排字节表"。
 */
class Iap2RgdComponentDeclarationTest {

    // ── 输入 ────────────────────────────────────────────────────────────────

    private val wiredConfig = Iap2IdentificationConfig(
        name = "LIVI",
        modelIdentifier = "LIVI",
        manufacturer = "LIVI",
        serialNumber = "0123456",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 4,
    )

    private val wirelessConfig = Iap2IdentificationConfig(
        name = "LIVI",
        modelIdentifier = "LIVI",
        manufacturer = "LIVI",
        serialNumber = "0123456",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        wireless = Iap2WirelessIdentification(bluetoothMac = "AA:BB:CC:DD:EE:FF", ssid = "LIVI"),
    )

    private fun payloadOf(config: Iap2IdentificationConfig): ByteArray =
        Iap2IdentificationClient.identificationInformation(config).payload

    /** 识别帧尾部那 86 字节（= 一个完整的 CSM 参数：4 字节头 + 82 字节体）。 */
    private fun rgdSliceOf(config: Iap2IdentificationConfig): ByteArray {
        val payload = payloadOf(config)
        assertTrue(
            "识别帧 payload 至少要有 RGD 宣告那么长，实际 ${payload.size} 字节",
            payload.size > RGD_BYTES,
        )
        return payload.copyOfRange(payload.size - RGD_BYTES, payload.size)
    }

    // ── 1. 结构 ─────────────────────────────────────────────────────────────

    @Test
    fun `识别帧尾部必须多出一个 0x1E 的 RGD 组件宣告`() {
        for (config in listOf(wiredConfig, wirelessConfig)) {
            val parameters = Iap2BodyReader
                .of(Iap2IdentificationClient.identificationInformation(config))
                .list()

            val rgd = parameters.singleOrNull { it.id == RGD_PARAMETER_ID }
            assertNotNull(
                "识别帧里必须**恰好一个**参数 0x1E（组件宣告）；" +
                    "少了 ⇒ iPhone 不下发导航帧，多了 ⇒ 组件重复注册",
                rgd,
            )
            assertEquals(
                "组件宣告必须是最后一个参数（追加在原有参数之后）",
                RGD_PARAMETER_ID,
                parameters.last().id,
            )
            assertEquals(
                "参数体长度 = 0x56 - 4 字节头",
                RGD_BYTES - 4,
                rgd!!.payload.size,
            )
        }
    }

    @Test
    fun `识别帧的 messageId 必须仍然是 0x1d01`() {
        for (config in listOf(wiredConfig, wirelessConfig)) {
            assertEquals(
                "追加组件宣告不得顺手改掉 messageId（改了就变成另一种消息）",
                Iap2IdentificationClient.IDENTIFICATION_INFORMATION,
                Iap2IdentificationClient.identificationInformation(config).messageId,
            )
        }
    }

    // ── 2. 语义 ─────────────────────────────────────────────────────────────

    @Test
    fun `组件宣告必须带 componentId 42 与组件名（否则 iPhone 不下发导航帧）`() {
        val inner = Iap2ParameterList.parse(rgdSliceOf(wiredConfig).copyOfRange(4, RGD_BYTES))
        val fields = inner.asList().associateBy { it.id }

        assertEquals(
            "0x5201/0x5202 的 COMPONENT_ID 是 42；组件宣告必须报同一个值",
            42,
            u16(fields.getValue(0).payload),
        )

        val name = fields.getValue(1).payload
        assertEquals(
            "组件名必须带 NUL 结尾（29 字符 + 1 字节 0）",
            30,
            name.size,
        )
        assertEquals(
            "组件名必须逐字符一致 —— 与车机侧声明的名字对上，iPhone 才认这个组件",
            "RouteGuidanceDisplayComponent",
            String(name, 0, 29, Charsets.US_ASCII),
        )
        assertEquals("组件名必须以 U+0000 结尾", 0, name[29].toInt())

        assertEquals("MaxCurrentRoadNameLength", 0x0100, u16(fields.getValue(2).payload))
        assertEquals("MaxDestinationNameLength", 0x0100, u16(fields.getValue(3).payload))
        assertEquals("MaxAfterManeuverRoadNameLength", 0x0100, u16(fields.getValue(4).payload))
        assertEquals("MaxManeuverDescriptionLength", 0x0100, u16(fields.getValue(5).payload))
        assertEquals("MaxGuidanceManeuverStorageCapacity", 0x0006, u16(fields.getValue(6).payload))
        assertEquals("MaxLaneGuidanceDescriptionLength", 0x0100, u16(fields.getValue(7).payload))
        assertEquals("MaxLaneGuidanceStorageCapacity", 0x0006, u16(fields.getValue(8).payload))
    }

    // ── 3. 黄金向量 ─────────────────────────────────────────────────────────

    @Test
    fun `组件宣告的 86 字节必须与现场补丁逐字节一致`() {
        assertArrayEquals(
            "字节表取自 dbplay 现场补丁的生成器 gen_rgd.py（已装机验证）；" +
                "⚠️ 位置错一位车机就静默不认 —— 不要「重排/美化」这张表",
            GOLDEN_RGD_BYTES,
            rgdSliceOf(wiredConfig),
        )
    }

    // ── 回归：追加不得破坏原有内容 ──────────────────────────────────────────

    @Test
    fun `追加组件宣告不得破坏原有参数与升序`() {
        for (config in listOf(wiredConfig, wirelessConfig)) {
            val parameters = Iap2BodyReader
                .of(Iap2IdentificationClient.identificationInformation(config))
                .list()
            val ids = parameters.map { it.id }

            assertEquals(
                "CSM 要求参数按 id 升序；0x1E 恰好排在原有最大 id（0x18=24）之后，所以顺序仍然成立",
                ids.sorted(),
                ids,
            )
            assertTrue(
                "0x5201/0x5202/0x5204 的声明不能被这次追加挤掉（PATCH-0039 的车道依赖它）",
                listOf(0x5201, 0x5202, 0x5204).all {
                    it in Iap2BodyReader.of(
                        Iap2IdentificationClient.identificationInformation(config),
                    ).u16List(7)
                },
            )
            assertTrue(
                "身份串必须原样保留（追加只动尾部）",
                "LIVI" == Iap2BodyReader
                    .of(Iap2IdentificationClient.identificationInformation(config))
                    .string(0),
            )
        }
    }

    private fun u16(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)

    private companion object {
        /** 组件宣告参数的 id（`0x1E`）。 */
        const val RGD_PARAMETER_ID = 0x1e

        /** 组件宣告的总字节数（`0x56`）。 */
        const val RGD_BYTES = 0x56

        /**
         * 现场补丁的黄金向量（86 字节）。
         *
         * 由 `gen_rgd.py` 的同一套 `tlv()` 规则独立重算：
         * ```
         * inner  = tlv(0x0000, u16(0x002a))
         *        + tlv(0x0001, b"RouteGuidanceDisplayComponent\x00")
         *        + tlv(0x0002..0x0005, u16(0x0100))
         *        + tlv(0x0006, u16(0x0006))
         *        + tlv(0x0007, u16(0x0100))
         *        + tlv(0x0008, u16(0x0006))
         * tlv_bytes = tlv(0x001e, inner)   # 86 字节
         * ```
         */
        val GOLDEN_RGD_BYTES: ByteArray = (
            "0056001E00060000002A00220001526F75746547756964616E6365446973706C6179436F6D70" +
                "6F6E656E740000060002010000060003010000060004010000060005010000060006000600" +
                "0600070100000600080006"
            ).hexToBytes()
    }
}

/** 十六进制串 → 字节数组（测试内用，长度必须是偶数）。 */
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, got $length" }
    return ByteArray(length / 2) { index ->
        this@hexToBytes.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
