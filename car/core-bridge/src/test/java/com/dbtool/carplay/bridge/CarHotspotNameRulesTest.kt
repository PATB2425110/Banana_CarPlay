package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarHotspotNameRules
import com.dbtool.carplay.api.hotspot.CarHotspotNameRules.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地 SSID 判据」的单测（2026-09-30）。
 *
 * ## 为什么这条要钉死
 *
 * 上游只判 SSID **非空**与**不含 `U+0000`**，**不判长度**。超长名字会一路传到
 * `hostapd.conf` / `SoftApConfiguration`，由厂商驱动在某个我们看不见的地方失败——
 * 现象是"iPhone 连不上"，而现场拿不到任何中文提示。
 *
 * ## 最容易写错的一处：字节 ≠ 字符
 *
 * 802.11 的 SSID 上限是 **32 字节**。UTF-8 下汉字占 3 字节 ⇒ 11 个汉字就超了。
 * 用 `String.length` 写这段代码会**静默放过** 11~32 个汉字的名字，
 * 所以下面专门用汉字长度卡边界（`"联" * 10` 通过、`"联" * 11` 判长）。
 */
class CarHotspotNameRulesTest {

    private fun repeatHan(n: Int) = "联".repeat(n)

    @Test
    fun `出厂默认名 DBPlay 合法`() {
        assertNull(CarHotspotNameRules.problemOf("DBPlay"))
    }

    @Test
    fun `空名字不判错（留空在 LOCAL_ONLY_HOTSPOT 下表示随机生成）`() {
        assertNull(
            "空 SSID 的语义随模式变化，属于上游规则，这里不许把它判成错误",
            CarHotspotNameRules.problemOf(""),
        )
    }

    @Test
    fun `恰好 32 字节的 ASCII 名字合法`() {
        val ssid = "a".repeat(32)
        assertEquals(32, CarHotspotNameRules.byteLength(ssid))
        assertNull("32 字节正好是上限，必须放行", CarHotspotNameRules.problemOf(ssid))
    }

    @Test
    fun `33 字节的 ASCII 名字判超长`() {
        val ssid = "a".repeat(33)
        assertEquals(Problem.TOO_LONG, CarHotspotNameRules.problemOf(ssid))
    }

    @Test
    fun `10 个汉字（30 字节）合法`() {
        val ssid = repeatHan(10)
        assertEquals(30, CarHotspotNameRules.byteLength(ssid))
        assertNull(CarHotspotNameRules.problemOf(ssid))
    }

    @Test
    fun `11 个汉字（33 字节）判超长——按字符数写就会漏掉这一条`() {
        val ssid = repeatHan(11)
        assertEquals(11, ssid.length) // 字符数看着"很短"
        assertEquals(33, CarHotspotNameRules.byteLength(ssid)) // 字节数已经超了
        assertEquals(Problem.TOO_LONG, CarHotspotNameRules.problemOf(ssid))
    }

    @Test
    fun `含 U+0000 判非法字符`() {
        assertEquals(Problem.NUL, CarHotspotNameRules.problemOf("DB\u0000Play"))
    }

    @Test
    fun `既含非法字符又超长时优先报非法字符`() {
        // 先删掉非法字符才轮到长度问题 ⇒ 报 NUL 更能指导用户。
        assertEquals(Problem.NUL, CarHotspotNameRules.problemOf("a".repeat(40) + "\u0000"))
    }

    @Test
    fun `describe 文案要能直接告诉用户差多少字节`() {
        val ssid = repeatHan(11)
        val text = CarHotspotNameRules.describe(Problem.TOO_LONG, ssid)
        assertTrue("文案里要有具体字节数：$text", text.contains("33"))
        assertTrue("文案里要有上限：$text", text.contains("32"))
    }

    @Test
    fun `describe 对非法字符给出中文说明`() {
        val text = CarHotspotNameRules.describe(Problem.NUL, "DB\u0000Play")
        assertTrue("文案要是中文：$text", text.contains("非法字符"))
    }
}
