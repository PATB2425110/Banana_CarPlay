package com.dbtool.carplay.app

import com.dbtool.carplay.bridge.developer.CarMfiBlockStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockGateTest {

    @Test
    fun `isBlocked=true 时 shouldIntercept 返回 true`() {
        assertTrue(BlockGate.shouldIntercept(FakeBlockStore(blocked = true)))
    }

    @Test
    fun `isBlocked=false 时 shouldIntercept 返回 false`() {
        assertFalse(BlockGate.shouldIntercept(FakeBlockStore(blocked = false)))
    }

    @Test
    fun `store 读异常时宁拦勿放并打日志`() {
        val logs = mutableListOf<String>()
        val intercepted = BlockGate.shouldIntercept(ThrowingBlockStore()) { message, error ->
            logs += "$message: ${error.message}"
        }

        assertTrue(intercepted)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("按已封禁处理"))
    }

    @Test
    fun `解封 clear 后 shouldIntercept 返回 false`() {
        val store = FakeBlockStore(blocked = true, reason = "管理员停用")
        assertTrue(BlockGate.shouldIntercept(store))

        store.clear()

        assertFalse(BlockGate.shouldIntercept(store))
    }

    @Test
    fun `reason 为空时封禁页不出现原因行`() {
        assertNull(BlockedPageCopy.reasonLine(""))
        assertFalse(BlockedPageCopy.reasonLine("   ").orEmpty().contains("原因："))
    }

    @Test
    fun `reason 有值时封禁页保留原文`() {
        assertEquals("原因：逾期未续费", BlockedPageCopy.reasonLine(" 逾期未续费 "))
    }

    @Test
    fun `deviceId 只显示前四位与后四位`() {
        assertEquals("ABCD****3456", BlockedPageCopy.maskDeviceId("ABCDEF123456"))
    }

    @Test
    fun `deviceId 缺失时设备行退回 VIN 掩码`() {
        assertEquals(
            "设备编号：VIN L6T***111111",
            BlockedPageCopy.deviceLine("", "L6TTEST1111111111"),
        )
    }
}

private class FakeBlockStore(
    blocked: Boolean = false,
    reason: String = "",
) : CarMfiBlockStore {
    override var isBlocked: Boolean = blocked
        private set

    override var reason: String = reason
        private set

    override fun cache(reason: String) {
        isBlocked = true
        this.reason = reason
    }

    override fun clear() {
        isBlocked = false
        reason = ""
    }
}

private class ThrowingBlockStore : CarMfiBlockStore {
    override val isBlocked: Boolean
        get() = error("prefs 损坏")

    override val reason: String
        get() = ""

    override fun cache(reason: String) = Unit

    override fun clear() = Unit
}
