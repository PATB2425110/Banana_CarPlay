package com.dbtool.carplay.bridge.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BtLinkReleaseResult] 的解析契约。
 *
 * 核心纪律：**缺字段 / 没有标记行 = 未知，绝不等于成功**。
 * `app_process` 输出里混着 JVM/链接器噪声；把"没解析到"当成功会向用户谎报
 * 「蓝牙已断开」。
 */
class BtLinkReleaseResultTest {

    @Test
    fun `从 JVM 噪声中解析最后一条标记行`() {
        val output = """
            WARNING: linker: unused DT entry
            DBPLAY-BT result=probe adapter=ON adapterState=12 uid=0 sdk=28
            noise in the middle
            DBPLAY-BT result=disconnected adapter=ON adapterState=12 uid=0 sdk=28 connectedBefore=true connectedAfter=false disconnectMethod=ok policyForbidden=false
            DBPLAY-BT exit=0
        """.trimIndent()
        val parsed = BtLinkReleaseResult.parse(output)
        assertEquals("disconnected", parsed?.result)
        assertEquals(true, parsed?.connectedBefore)
        assertEquals(false, parsed?.connectedAfter)
        assertEquals(true, parsed?.adapterOn)
        assertEquals(12, parsed?.adapterState)
        assertEquals(0, parsed?.uid)
        assertEquals("ok", parsed?.disconnectMethod)
        assertTrue(parsed!!.released)
    }

    @Test
    fun `没有标记行时返回 null`() {
        val output = """
            java.lang.ClassNotFoundException: com.dbtool.carplay.bridge.bluetooth.BtLinkReleaseTool
            Aborted
        """.trimIndent()
        assertNull(BtLinkReleaseResult.parse(output))
        assertNull(BtLinkReleaseResult.parse(""))
    }

    @Test
    fun `仍处连接态不算成功`() {
        val parsed = BtLinkReleaseResult.parse(
            "DBPLAY-BT result=disconnected adapter=ON adapterState=12 uid=0 " +
                "connectedBefore=true connectedAfter=true disconnectMethod=ok",
        )
        assertEquals(true, parsed?.connectedAfter)
        assertFalse(parsed!!.released)
    }

    @Test
    fun `适配器 OFF 不算成功`() {
        val parsed = BtLinkReleaseResult.parse(
            "DBPLAY-BT result=disconnected adapter=OFF adapterState=11 uid=0 " +
                "connectedBefore=true connectedAfter=false",
        )
        assertEquals(false, parsed?.adapterOn)
        assertFalse(parsed!!.released)
    }

    @Test
    fun `缺 adapter 字段时不算成功`() {
        val parsed = BtLinkReleaseResult.parse(
            "DBPLAY-BT result=disconnected uid=0 connectedBefore=true connectedAfter=false",
        )
        assertNull(parsed?.adapterOn)
        assertFalse(parsed!!.released)
    }

    @Test
    fun `exception none 归一化为 null`() {
        val parsed = BtLinkReleaseResult.parse(
            "DBPLAY-BT result=probe adapter=ON uid=0 disconnectMethod=ok exception=none",
        )
        assertNull(parsed?.exception)
    }

    @Test
    fun `hidden API 分类被保留`() {
        val parsed = BtLinkReleaseResult.parse(
            "DBPLAY-BT result=probe adapter=ON uid=0 " +
                "disconnectMethod=hidden-api-blocked exception=java.lang.NoSuchMethodException",
        )
        assertEquals("hidden-api-blocked", parsed?.disconnectMethod)
        assertEquals("java.lang.NoSuchMethodException", parsed?.exception)
    }
}
