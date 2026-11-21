package com.dbtool.carplay.bridge.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VIN 合法性判据与"从长文本里挖 VIN"。
 *
 * ## 为什么这两条值得单测
 *
 * 它们决定"上报给服务端的 vin 字段是真是假"。错的方向代价不对称：
 *
 * - **漏判**（把命令帮助文本 / 空值当成 VIN）⇒ 后台「DBPlay 车辆」页出现
 *   一台 VIN 是 `usage` 或 `commands` 的车，还会被当成灰度目标；
 * - **误判**（把真 VIN 丢掉）⇒ 车辆档案要么消失、要么每次上报都空着。
 *
 * `CarVinReader.isValid` 是从 DBtool3.0 的 `VinReader.isValidVin` 逐条搬过来的，
 * 因此这里除了钉住行为，也钉住了"那几个占位词确实被挡住"—— 那是它存在的理由。
 */
class CarVinReaderTest {

    // ------------------------------------------------------------------ 合法性

    @Test
    fun `标准 17 位 VIN 通过`() {
        assertTrue(CarVinReader.isValid("LSVAM4187C2184847"))
        assertTrue(CarVinReader.isValid("LFV2A21K5D4123456"))
    }

    @Test
    fun `8 到 24 位的非标准 VIN 容错通过`() {
        // 部分 OEM 的 VIN 只有 8 位、或带 `-` `_`，DBtool3.0 刻意容错。
        assertTrue(CarVinReader.isValid("LSVAM418"))
        assertTrue(CarVinReader.isValid("VIN-12345678"))
        assertTrue(CarVinReader.isValid("ABCDEFGHIJKLMNOPQRSTUVWX")) // 24 位，上限
    }

    @Test
    fun `长度落在 8 到 24 之外一律拒绝`() {
        assertFalse(CarVinReader.isValid("LSVAM41"))                          // 7 位
        assertFalse(CarVinReader.isValid("ABCDEFGHIJKLMNOPQRSTUVWXY"))        // 25 位
        assertFalse(CarVinReader.isValid(""))
        assertFalse(CarVinReader.isValid("   "))
        assertFalse(CarVinReader.isValid(null))
    }

    @Test
    fun `系统属性里的占位值不算 VIN`() {
        assertFalse(CarVinReader.isValid("unknown"))
        assertFalse(CarVinReader.isValid("null"))
        assertFalse(CarVinReader.isValid("n/a"))
        assertFalse(CarVinReader.isValid("UNKNOWN")) // 大小写都要挡
        assertFalse(CarVinReader.isValid("0000000000000000"))
    }

    @Test
    fun `命令帮助文本不算 VIN`() {
        // `cmd car_service` 用错参数时会回 usage，`dumpsys` 被拒会回命令名。
        assertFalse(CarVinReader.isValid("usage: cmd car_service get-property-value"))
        assertFalse(CarVinReader.isValid("commands:"))
        assertFalse(CarVinReader.isValid("help"))
        assertFalse(CarVinReader.isValid("Permission denied"))
    }

    @Test
    fun `含空格或路径分隔符的文本不算 VIN（VIN 是单词）`() {
        assertFalse(CarVinReader.isValid("/sys/class/vehicle/vin"))
        assertFalse(CarVinReader.isValid("LSV AM4187C2184847"))
        assertFalse(CarVinReader.isValid("LSV:AM4187C2184847"))
    }

    // ------------------------------------------------------------------ 提取

    @Test
    fun `从 getprop 输出里取出值`() {
        val out = "LSVAM4187C2184847\n"
        assertEquals("LSVAM4187C2184847", CarVinReader.extractVinCandidate(out))
    }

    @Test
    fun `从 dumpsys 的噪声里挖出 17 位 VIN`() {
        // 真实形状：shell 侧命令已经 `grep -iE 'vin|vehicleId' | head -1`，
        // 所以传到提取器的第一行通常就是 VIN 那一行。
        val out = """
            VIN: LSVAM4187C2184847
              vehicleId: 0x00000001
              make: SAIC-VW
              modelYear: 2019
        """.trimIndent()
        assertEquals("LSVAM4187C2184847", CarVinReader.extractVinCandidate(out))
    }

    @Test
    fun `从 logcat 的 17 位 greps 结果里取值`() {
        assertEquals("LFV2A21K5D4123456", CarVinReader.extractVinCandidate("LFV2A21K5D4123456"))
    }

    @Test
    fun `dumpsys 的字段名不算 VIN（2026-10-07 实测回归）`() {
        // ⚠️ 这是**真实踩过的坑**，不是假想用例：
        //   ① 提取器第一次跑返回了 `vehicleId` —— `dumpsys vehicle` 第一行的字段名。
        //      长度合法（9 位）、全字母数字、无触发词，旧判据放行了它。
        //   ② 挡住字段名之后，又返回了 `0x00000001`（同行的 vehicleId 值）——
        //      它同样长度合法、无触发词。最终定案：**长文本扫描只认标准 17 位**。
        assertFalse(CarVinReader.isValid("vehicleId"))
        assertFalse(CarVinReader.isValid("modelYear"))
        assertFalse(CarVinReader.isValid("make"))
        assertFalse(CarVinReader.isValid("VehicleId")) // 大小写不敏感

        // 噪声在前、真 VIN 在后时：**只找到 17 位那条**，不会被 vehicleId 带偏。
        val out = "vehicleId: 0x00000001\nVIN: LSVAM4187C2184847"
        assertEquals("LSVAM4187C2184847", CarVinReader.extractVinCandidate(out))

        // 只有噪声（没有 17 位）时：返回 null，**不许猜**。
        assertNull(CarVinReader.extractVinCandidate("vehicleId: 0x00000001"))
        assertNull(CarVinReader.extractVinCandidate("make: SAIC-VW\nmodelYear: 2019"))
    }

    @Test
    fun `全是噪声时返回 null 而不是猜一个`() {
        assertNull(CarVinReader.extractVinCandidate(""))
        assertNull(CarVinReader.extractVinCandidate("   "))
        assertNull(CarVinReader.extractVinCandidate(null))
        assertNull(CarVinReader.extractVinCandidate("unknown"))
        assertNull(CarVinReader.extractVinCandidate("Permission denied"))
        assertNull(CarVinReader.extractVinCandidate("No service found"))
    }

    @Test
    fun `跳过前面的假候选继续往后找`() {
        // 前一个是 17 位但含 `00000000` 占位串（合法长度、被占位判据挡），后面才是真 VIN。
        assertEquals(
            "LSVAM4187C2184847",
            CarVinReader.extractVinCandidate("00000000123456789 then LSVAM4187C2184847"),
        )
    }

    @Test
    fun `数字串不会被当 VIN`() {
        // 16 位数字：长度合法、不含 `00000000`、无触发词。在**整值**上下文里
        // （`isValid`）DBtool3.0 也照样放行，这里钉住这个已知行为；
        // 但在**长文本扫描**里必须被 17 位要求挡住——那才是它会出现的场景
        // （`dumpsys` 里的各种 id / 时间戳）。
        assertTrue(CarVinReader.isValid("1234567890123456"))
        assertNull(CarVinReader.extractVinCandidate("1234567890123456"))
    }
}
