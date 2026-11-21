package com.dbtool.carplay.bridge.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EcarxVfhudChannel] 的边界行为。
 *
 * 这几条在 JVM 上跑，**不碰真实 Binder / 反射**：注入 [VfhudAccess] 假实现，
 * 只钉住「失败必须变成三态而不是异常」「activate/deactivate 的调用次数与参数」
 * 「厂商返回值不能丢」。
 */
class EcarxVfhudChannelTest {

    private class FakeAccess(
        private val openFailure: Throwable? = null,
        private val requestFailure: Throwable? = null,
    ) : VfhudAccess {
        var openCount = 0
            private set
        var requestCount = 0
            private set
        val requestedValues = mutableListOf<Int>()

        private val manager = Any()

        override fun open(): Any {
            openCount++
            openFailure?.let { throw it }
            return manager
        }

        override fun request(manager: Any, value: Int): Any? {
            requestCount++
            requestedValues += value
            requestFailure?.let { throw it }
            return FakeApiResult.SUCCEED
        }
    }

    /** 模仿厂商 `ApiResult` 枚举：`toString()` 就是枚举名。 */
    private enum class FakeApiResult {
        SUCCEED,
        FAILED,
    }

    private fun channel(
        access: VfhudAccess,
        logs: MutableList<String> = mutableListOf(),
    ): EcarxVfhudChannel = EcarxVfhudChannel(access = access, logger = logs::add)

    @Test
    fun `初始状态是 Unknown_没有试过不算不可用`() {
        val channel = channel(FakeAccess())
        assertEquals(HudChannelAvailability.Unknown, channel.availability())
    }

    @Test
    fun `反射建链失败返回 Unavailable 而不是抛异常`() {
        val failure = HudChainException(
            HudChainStep.SERVICE_MANAGER,
            ClassNotFoundException("android.os.ServiceManager"),
        )
        val access = FakeAccess(openFailure = failure)
        val channel = channel(access)

        // 关键：这一行不能把异常抛给调用方（主流程只记日志）。
        val availability = channel.probe()

        assertTrue(
            "建链失败必须是 Unavailable，实际=$availability",
            availability is HudChannelAvailability.Unavailable,
        )
        val reason = (availability as HudChannelAvailability.Unavailable).reason
        assertTrue("原因必须点名失败步骤：$reason", reason.contains("ServiceManager"))
        assertEquals("链没建起来就不该发 ActvReq", 0, access.requestCount)
    }

    @Test
    fun `未包装的反射异常也会被边界吞掉`() {
        // 真实建链里 Class.forName / getMethod 抛的是原始异常，不一定是 HudChainException。
        val access = FakeAccess(openFailure = ClassNotFoundException("android.os.ServiceManager"))
        val channel = channel(access)

        val availability = channel.probe()

        assertTrue(availability is HudChannelAvailability.Unavailable)
        val reason = (availability as HudChannelAvailability.Unavailable).reason
        assertTrue("原因应保留异常类型：$reason", reason.contains("ClassNotFoundException"))
    }

    @Test
    fun `activate 与 deactivate 各调用一次且参数为 1 和 0`() {
        val access = FakeAccess()
        val channel = channel(access)

        val activated = channel.activate()
        val deactivated = channel.deactivate()

        assertTrue(activated is HudCallResult.Returned)
        assertTrue(deactivated is HudCallResult.Returned)
        assertEquals(listOf(1, 0), access.requestedValues)
        assertEquals(2, access.requestCount)
        // 同一会话内复用同一条链，不重复 createCar。
        assertEquals(1, access.openCount)
        assertEquals(HudChannelAvailability.Available, channel.availability())
    }

    @Test
    fun `厂商返回值原样保留_不能被丢掉`() {
        val access = FakeAccess()
        val channel = channel(access)

        val result = channel.activate() as HudCallResult.Returned

        assertSame(FakeApiResult.SUCCEED, result.raw)
        assertEquals(1, result.requestValue)
        assertEquals("SUCCEED", result.returnText)
        assertTrue("ApiResult.SUCCEED 应判成功", result.apiSucceeded)
    }

    @Test
    fun `调用失败后变成 Unavailable 并允许下一次重新建链`() {
        val access = FakeAccess(requestFailure = IllegalStateException("binder died"))
        val channel = channel(access)

        val first = channel.activate()
        assertTrue("调用异常必须是 Failed，实际=$first", first is HudCallResult.Failed)
        assertTrue((first as HudCallResult.Failed).reason.contains("CB_VF_HUD_ActvReq(1)"))
        assertTrue(
            "调用失败后应标记不可用",
            channel.availability() is HudChannelAvailability.Unavailable,
        )

        val second = channel.activate()
        assertTrue(second is HudCallResult.Failed)
        assertEquals("失败后必须丢弃旧 manager，下一轮重新建链", 2, access.openCount)
    }

    @Test
    fun `未激活直接关闭也会发出 0`() {
        // 覆盖「上次进程异常退出、HUD 可能还亮着」——会话结束时补一次 0 是安全的。
        val access = FakeAccess()
        val channel = channel(access)

        channel.deactivate()

        assertEquals(listOf(0), access.requestedValues)
    }
}
