package com.dbtool.carplay.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoCodecConfigDiagnostics] 的单测。
 *
 * 为什么值得钉死：这行日志是"HEVC 整屏偏绿"这类故障**唯一**的取证手段。
 * 现场拿不到原始码流，只能靠它把"流本身是单色 / 高位深"与
 * "本机合成路径丢色度"区分开——一旦字段偏移算错，结论会整体反过来，
 * 而那种错误在真机日志里看起来完全正常。
 *
 * 夹具按 ISO/IEC 14496-15 的 `HEVCDecoderConfigurationRecord`（hvcC）
 * 与 `AVCDecoderConfigurationRecord`（avcC）逐字段构造。
 */
class VideoCodecConfigDiagnosticsTest {

    /**
     * 构造一个合法的 hvcC。
     *
     * @param profileIdc `general_profile_idc`：1=Main，2=Main10
     * @param chromaFormatIdc `chroma_format_idc`：0=单色，1=4:2:0
     * @param bitDepthLuma 亮度位深（8 / 10 / 12）
     * @param bitDepthChroma 色度位深（8 / 10 / 12）
     * @param parameterSets 是否带 VPS/SPS/PPS 数组表。为 false 时 `numOfArrays=0`，
     *   upstream 会因此**完全不设置 `csd-0`**。
     */
    private fun hvcC(
        profileIdc: Int = 1,
        chromaFormatIdc: Int = 1,
        bitDepthLuma: Int = 8,
        bitDepthChroma: Int = 8,
        levelIdc: Int = 120,
        parameterSets: Boolean = true,
    ): ByteArray {
        val record = ByteArray(23)
        record[0] = 1 // configurationVersion
        record[1] = (profileIdc and 0x1f).toByte() // profile_space=0, tier=0
        record[12] = levelIdc.toByte()
        record[16] = (chromaFormatIdc and 0x03).toByte()
        record[17] = (bitDepthLuma - 8).toByte()
        record[18] = (bitDepthChroma - 8).toByte()
        record[22] = if (parameterSets) 3 else 0
        if (!parameterSets) return record
        // 每个数组：1 字节 NAL 类型 + u16 NAL 个数；每项：u16 长度 + 数据。
        return record +
            byteArrayOf(32, 0, 1, 0, 3, 0x40, 0x01, 0x0c) + // VPS，3 字节
            byteArrayOf(33, 0, 1, 0, 4, 0x42, 0x01, 0x01, 0x02) + // SPS，4 字节
            byteArrayOf(34, 0, 1, 0, 2, 0x44, 0x01) // PPS，2 字节
    }

    private fun avcC(profileIdc: Int = 100, levelIdc: Int = 41): ByteArray = byteArrayOf(
        1, // configurationVersion
        profileIdc.toByte(),
        0, // profile_compatibility
        levelIdc.toByte(),
        0xff.toByte(), // 后接 lengthSizeMinusOne + SPS 数量，本诊断不读
    )

    @Test
    fun `Main10 高位深会被点名`() {
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = true,
            codecData = hvcC(profileIdc = 2, chromaFormatIdc = 1, bitDepthLuma = 10, bitDepthChroma = 10),
        )

        assertTrue(described, described.contains("profile=Main10(2)"))
        assertTrue(described, described.contains("chromaFormat=4:2:0(1)"))
        assertTrue(described, described.contains("bitDepth=10/10"))
        // 关键：必须给出"高位深"这个可执行的怀疑方向。
        assertTrue(described, described.contains("!! high bit depth"))
        assertFalse(described, described.contains("monochrome stream"))
    }

    @Test
    fun `单色流会被点名且不再报高位深`() {
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = true,
            codecData = hvcC(profileIdc = 1, chromaFormatIdc = 0, bitDepthLuma = 8, bitDepthChroma = 8),
        )

        assertTrue(described, described.contains("chromaFormat=monochrome(0)"))
        assertTrue(described, described.contains("bitDepth=8/8"))
        assertTrue(described, described.contains("!! monochrome stream"))
        assertFalse(described, described.contains("high bit depth"))
    }

    @Test
    fun `正常的 Main 8bit 420 不该报任何异常`() {
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = true,
            codecData = hvcC(profileIdc = 1, chromaFormatIdc = 1, bitDepthLuma = 8, bitDepthChroma = 8),
        )

        assertTrue(described, described.contains("profile=Main(1)"))
        assertTrue(described, described.contains("chromaFormat=4:2:0(1)"))
        assertTrue(described, described.contains("csd=[VPS(3),SPS(4),PPS(2)] csdBytes=9"))
        assertFalse(described, described.contains("!!"))
    }

    @Test
    fun `没有参数集时要点明 csd-0 不会被设置`() {
        // 这是"解码器拿不到初始化数据"的唯一可观测信号：
        // upstream 只在提取结果非空时才 setByteBuffer("csd-0", ...)。
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = true,
            codecData = hvcC(parameterSets = false),
        )

        assertTrue(described, described.contains("csd=NONE(numOfArrays=0)"))
        assertTrue(described, described.contains("upstream sets no csd-0"))
    }

    @Test
    fun `只有色度是高位深也要点名`() {
        // 亮度 8 / 色度 10 在实际流里罕见，但一旦出现，
        // "只比亮度"的判断会漏掉它——而色度恰恰是本次故障的主角。
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = true,
            codecData = hvcC(profileIdc = 2, bitDepthLuma = 8, bitDepthChroma = 10),
        )

        assertTrue(described, described.contains("bitDepth=8/10"))
        assertTrue(described, described.contains("!! high bit depth"))
    }

    @Test
    fun `level 与 profileSpace tier 都要读出来`() {
        val record = hvcC(profileIdc = 1, levelIdc = 150)
        record[1] = ((1 shl 6) or (1 shl 5) or 1).toByte() // profileSpace=1, tier=1, profileIdc=1
        val described = VideoCodecConfigDiagnostics.describe(hevc = true, codecData = record)

        assertTrue(described, described.contains("profileSpace=1"))
        assertTrue(described, described.contains("tier=1"))
        assertTrue(described, described.contains("level=150"))
    }

    @Test
    fun `太短的 hvcC 不猜字段`() {
        val described = VideoCodecConfigDiagnostics.describe(hevc = true, codecData = ByteArray(10) { 1 })

        assertTrue(described, described.contains("not an hvcC record"))
        assertFalse(described, described.contains("bitDepth="))
    }

    @Test
    fun `版本号不是 1 的 hvcC 也要拒绝`() {
        val record = hvcC()
        record[0] = 2
        val described = VideoCodecConfigDiagnostics.describe(hevc = true, codecData = record)

        assertTrue(described, described.contains("not an hvcC record"))
    }

    @Test
    fun `avcC 读出 profile 与 level`() {
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = false,
            codecData = avcC(profileIdc = 100, levelIdc = 41),
        )

        assertTrue(described, described.contains("profile=100"))
        assertTrue(described, described.contains("level=41"))
        // H.264 High profile 恒为 8 bit 4:2:0 —— avcC 里没有这两个字段，
        // 但把它显式写出来才能和 HEVC 那行并排比较。
        assertTrue(described, described.contains("bitDepth=8/8"))
        assertTrue(described, described.contains("chromaFormat=4:2:0"))
    }

    @Test
    fun `太短的 avcC 不猜字段`() {
        val described = VideoCodecConfigDiagnostics.describe(hevc = false, codecData = byteArrayOf(1, 2))

        assertTrue(described, described.contains("not an avcC record"))
    }

    @Test
    fun `头部十六字节会带出来以便人工比对`() {
        val described = VideoCodecConfigDiagnostics.describe(
            hevc = true,
            codecData = hvcC(profileIdc = 2, bitDepthLuma = 10, bitDepthChroma = 10),
        )

        // 01=version, 02=profileIdc=2, 之后 10 字节全 0（含 compat/constraint），
        // 第 12 字节 78=levelIdc=120，再 3 字节 0。
        assertTrue(described, described.contains("head=01020000000000000000000078000000"))
        // tail = 第 16..22 字节：chromaFormat=1、位深 2/2（即 10/10）、
        // 平均帧率 0、最后是 numOfArrays=3。
        assertTrue(described, described.contains("tail=01020200000003"))
        assertEquals(
            "head 段固定 16 字节 = 32 个十六进制字符，不多不少",
            32,
            described.substringAfter("head=").substringBefore(" ").length,
        )
    }
}
